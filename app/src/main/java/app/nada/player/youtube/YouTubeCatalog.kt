package app.nada.player.youtube

import app.nada.player.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Stable identities only. Signed media URLs belong to the resolver, never saved collections. */
object YouTubeLinks {
    fun videoId(value: String): String? {
        val raw = value.removePrefix("youtube:")
        if (raw.matches(Regex("[A-Za-z0-9_-]{11}"))) return raw
        return runCatching {
            val uri = URI(value)
            if (uri.scheme !in listOf("https", "http") || uri.userInfo != null || uri.port != -1) return null
            val host = uri.host?.lowercase() ?: return null
            val parts = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
            val id = when (host) {
                "youtu.be" -> parts.singleOrNull()
                "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> when {
                    parts.firstOrNull() in listOf("shorts", "embed", "live") -> parts.getOrNull(1)
                    uri.path == "/watch" -> uri.rawQuery.orEmpty().split('&').mapNotNull {
                        val pair = it.split('=', limit = 2)
                        if (pair.size == 2 && pair[0] == "v") URLDecoder.decode(pair[1], "UTF-8") else null
                    }.singleOrNull()
                    else -> null
                }
                else -> null
            }
            id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        }.getOrNull()
    }
    fun watch(id: String) = "https://www.youtube.com/watch?v=$id"

    fun artwork(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.scheme != "https" || uri.host !in listOf("yt3.googleusercontent.com", "lh3.googleusercontent.com")) return url
        val size = Regex("=w(\\d+)-h(\\d+)(?=-|$)").find(url) ?: return url
        if ((size.groupValues[1].toIntOrNull() ?: 544) >= 544 || (size.groupValues[2].toIntOrNull() ?: 544) >= 544) return url
        return url.replaceRange(size.range, "=w544-h544")
    }
}

private class YouTubeDownloader : Downloader() {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name); values.forEach { builder.addHeader(name, it) }
        }
        val body = request.dataToSend()?.toRequestBody()
        builder.method(request.httpMethod(), body ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null)
        return client.newCall(builder.build()).execute().use { result ->
            if (result.code == 429) throw ReCaptchaException("YouTube membatasi permintaan dari jaringan ini.", request.url())
            val bytes = result.body?.source()?.let { source ->
                val buffer = okio.Buffer()
                while (buffer.size <= MAX_BODY && !source.exhausted()) {
                    if (source.read(buffer, minOf(65_536L, MAX_BODY + 1 - buffer.size)) == -1L) break
                }
                if (buffer.size > MAX_BODY) throw IOException("Respons katalog terlalu besar.")
                buffer.readUtf8()
            }.orEmpty()
            Response(result.code, result.message, result.headers.toMultimap(), bytes, result.request.url.toString())
        }
    }
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val MAX_BODY = 12L * 1024 * 1024
    }
}

object YouTubeCatalog {
    private val engineLock = Any()
    private var initialized = false
    private data class SearchEntry(val at: Long, val tracks: List<Track>)
    data class Audio(val url: String, val expiresAt: Long, val bitrate: Int, val codec: String?)
    private val searches = LinkedHashMap<String, SearchEntry>()
    private val audio = LinkedHashMap<String, Audio>()

    private fun initialize() {
        if (!initialized) {
            NewPipe.init(YouTubeDownloader(), Localization("en", "US"), ContentCountry("ID"))
            initialized = true
        }
    }

    suspend fun search(query: String, videos: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        synchronized(engineLock) {
            initialize()
            val term = query.trim().take(150)
            if (term.isEmpty()) return@synchronized emptyList()
            val key = "$videos:$term"
            searches[key]?.takeIf { System.currentTimeMillis() - it.at < 300_000 }?.let { return@synchronized it.tracks }
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(term, listOf(if (videos) "videos" else "music_songs"), "")
            val info = SearchInfo.getInfo(service, handler)
            val entries = info.relatedItems.filterIsInstance<StreamInfoItem>().take(40).mapNotNull { item ->
                val id = YouTubeLinks.videoId(item.url) ?: return@mapNotNull null
                if (item.duration <= 0) return@mapNotNull null // Live streams need different queue/timer semantics.
                YouTubeSearchRanking.Entry(Track("youtube:$id", item.name, item.uploaderName.orEmpty().ifBlank { "YouTube" },
                    YouTubeLinks.artwork(item.thumbnails.maxByOrNull { it.height }?.url.orEmpty()), item.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    if (videos) "YouTube" else "YouTube Music", YouTubeLinks.watch(id), "youtube"), item.isUploaderVerified)
            }.distinctBy { it.track.id }
            val tracks = YouTubeSearchRanking.rank(term, entries)
            searches[key] = SearchEntry(System.currentTimeMillis(), tracks)
            while (searches.size > 24) searches.remove(searches.keys.first())
            tracks
        }
    }

    /** Called on Media3's loader thread, including for queue transitions and post-restart playback. */
    fun resolve(id: String, refresh: Boolean = false): Audio = synchronized(engineLock) {
        require(YouTubeLinks.videoId(id) == id) { "ID video tidak valid." }
        initialize()
        val now = System.currentTimeMillis()
        if (!refresh) audio[id]?.takeIf { it.expiresAt > now + 60_000 }?.let { return@synchronized it }
        audio.remove(id)
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeLinks.watch(id))
        val stream = YouTubeAudioSelection.best(info.audioStreams)
            ?: throw IOException("Audio YouTube belum tersedia untuk video ini. Pilih lagu lain.")
        val url = stream.content
        require(URI(url).scheme == "https") { "URL audio tidak didukung." }
        val expiry = URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("expire=") }
            ?.substringAfter('=')?.toLongOrNull()?.times(1000)
        val result = Audio(url, minOf(expiry ?: Long.MAX_VALUE, now + 10 * 60_000), YouTubeAudioSelection.bitrate(stream), stream.codec)
        audio[id] = result
        while (audio.size > 24) audio.remove(audio.keys.first())
        result
    }

    fun message(error: Throwable): String = when {
        generateSequence(error) { it.cause }.any { it is ReCaptchaException } -> "YouTube membatasi jaringan ini. Tunggu sebentar atau coba jaringan lain."
        error is java.net.UnknownHostException || error is java.net.SocketTimeoutException -> "YouTube belum terjangkau. Periksa internet lalu coba lagi."
        else -> "YouTube belum dapat dimuat. Coba lagi atau pilih sumber YouTube lainnya."
    }
}
