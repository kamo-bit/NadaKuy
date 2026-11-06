package app.nada.player.youtube

import app.nada.player.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import org.schabi.newpipe.extractor.Page
import app.nada.player.recommendations.RadioPages
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.ContentAvailability
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

    internal fun playlistUrl(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.userInfo != null || uri.port != -1) return null
        val isPlaylistLink = when (uri.host?.lowercase()) {
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> uri.path in setOf("/playlist", "/watch")
            "youtu.be" -> videoId(value.trim()) != null
            else -> false
        }
        if (!isPlaylistLink) return null
        val id = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "list" }
            .singleOrNull()?.substringAfter('=', "") ?: return null
        if (!id.matches(Regex("[A-Za-z0-9_-]{10,100}"))) return null
        return "https://www.youtube.com/playlist?list=$id"
    }

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

internal data class YouTubePlaylistPreview(
    val name: String,
    val tracks: List<Track>,
    val skipped: Int,
    val incomplete: Boolean,
)

object YouTubeCatalog {
    private const val PLAYLIST_ENTRY_LIMIT = 500
    private const val PLAYLIST_PAGE_LIMIT = 5
    private val engineLock = Any()
    private var initialized = false
    private data class SearchEntry(val at: Long, val tracks: List<Track>)
    data class Audio(val url: String, val expiresAt: Long, val bitrate: Int, val codec: String?)
    private val searches = LinkedHashMap<String, SearchEntry>()
    private val audio = LinkedHashMap<String, Audio>()
    private val relatedCache = LinkedHashMap<String, List<Track>>()

    private fun initialize() {
        if (!initialized) {
            NewPipe.init(YouTubeDownloader(), Localization("en", "US"), ContentCountry("ID"))
            initialized = true
        }
    }

    suspend fun search(query: String, videos: Boolean = false, refresh: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        synchronized(engineLock) {
            initialize()
            val term = query.trim().take(150)
            if (term.isEmpty()) return@synchronized emptyList()
            val key = "$videos:$term"
            if (!refresh) searches[key]?.takeIf { System.currentTimeMillis() - it.at in 0 until 300_000 }?.let { return@synchronized it.tracks }
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(term, listOf(if (videos) "videos" else "music_songs"), "")
            val info = SearchInfo.getInfo(service, handler)
            val entries = entries(info.relatedItems, videos)
            val tracks = YouTubeSearchRanking.rank(term, entries)
            // Empty upstream responses may be transient; a user retry must query the catalog again.
            if (tracks.isNotEmpty()) searches[key] = SearchEntry(System.currentTimeMillis(), tracks)
            while (searches.size > 24) searches.remove(searches.keys.first())
            tracks
        }
    }

    private fun entries(items: List<InfoItem>, videos: Boolean, limit: Int = 40): List<YouTubeSearchRanking.Entry> =
        items.filterIsInstance<StreamInfoItem>().take(limit).mapNotNull { item ->
            item.toCatalogTrack(videos)?.let { YouTubeSearchRanking.Entry(it, item.isUploaderVerified) }
        }.distinctBy { it.track.id }

    private fun StreamInfoItem.toCatalogTrack(videos: Boolean): Track? {
        val id = YouTubeLinks.videoId(url) ?: return null
        if (duration <= 0) return null // Live streams need different queue/timer semantics.
        val title = name.orEmpty().takeIf(String::isNotBlank) ?: return null
        return Track("youtube:$id", title, uploaderName.orEmpty().ifBlank { "YouTube" },
            YouTubeLinks.artwork(thumbnails.maxByOrNull { it.height }?.url.orEmpty()),
            duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            if (videos) "YouTube" else "YouTube Music", YouTubeLinks.watch(id), "youtube")
    }

    internal suspend fun playlistPreview(url: String): YouTubePlaylistPreview = withContext(Dispatchers.IO) {
        val requestContext = currentCoroutineContext()
        val service = ServiceList.YouTube
        val info = synchronized(engineLock) {
            requestContext.ensureActive()
            initialize()
            PlaylistInfo.getInfo(service, url)
        }
        val tracks = linkedMapOf<String, Track>()
        val seenPages = mutableListOf<Page>()
        var items = info.relatedItems
        var next = info.nextPage
        var examined = 0
        var incomplete = false
        while (true) {
            requestContext.ensureActive()
            val remaining = PLAYLIST_ENTRY_LIMIT - examined
            val batch = items.take(remaining)
            for (item in batch) {
                when (item.contentAvailability) {
                    ContentAvailability.AVAILABLE, ContentAvailability.UNKNOWN -> Unit
                    else -> continue
                }
                val track = item.toCatalogTrack(videos = true) ?: continue
                tracks.putIfAbsent(track.id, track)
            }
            examined += batch.size
            if (items.size > batch.size) {
                incomplete = true
                break
            }
            val cursor = next?.takeIf(Page::isValid) ?: break
            if (examined == PLAYLIST_ENTRY_LIMIT || seenPages.size >= PLAYLIST_PAGE_LIMIT - 1 ||
                seenPages.any { samePage(it, cursor) }) {
                incomplete = true
                break
            }
            val page = try {
                synchronized(engineLock) {
                    requestContext.ensureActive()
                    PlaylistInfo.getMoreItems(service, url, cursor)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                incomplete = true
                break
            }
            seenPages.add(cursor)
            items = page.items
            next = page.nextPage
        }
        YouTubePlaylistPreview(info.name.orEmpty().trim().take(60).ifBlank { "Playlist YouTube" },
            tracks.values.toList(), examined - tracks.size, incomplete)
    }

    private fun samePage(a: Page, b: Page): Boolean =
        a.url == b.url && a.id == b.id && a.ids == b.ids && a.cookies == b.cookies && a.body.contentEquals(b.body)

    private fun rememberRelated(id: String, items: List<InfoItem>) {
        val tracks = YouTubeSearchRanking.rank("", entries(items, false))
        if (tracks.isNotEmpty()) relatedCache[id] = tracks
        while (relatedCache.size > 12) relatedCache.remove(relatedCache.keys.first())
    }

    /** Anonymous music mix; retain upstream order rather than turning it into an artist search. */
    suspend fun radio(track: Track): List<Track> = withContext(Dispatchers.IO) {
        synchronized(engineLock) {
            initialize()
            val id = requireNotNull(YouTubeLinks.videoId(track.id))
            val mix = PlaylistInfo.getInfo(ServiceList.YouTube, "${YouTubeLinks.watch(id)}&list=RDAMVM$id")
            entries(mix.relatedItems, false).map { it.track }
        }
    }

    /** The opaque upstream cursor lives only in this source, and advances after a successful fetch. */
    internal fun radioPages(track: Track): RadioPages {
        val id = requireNotNull(YouTubeLinks.videoId(track.id))
        val url = "${YouTubeLinks.watch(id)}&list=RDAMVM$id"
        var started = false
        var cursor: Page? = null
        val previous = ArrayDeque<Page>()
        return RadioPages {
            if (started && cursor == null) null else {
                val selected = cursor
                val result = withContext(Dispatchers.IO) {
                    synchronized(engineLock) {
                        initialize()
                        if (selected != null) {
                            val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, selected)
                            entries(page.items, false, 80).map { it.track } to page.nextPage
                        } else {
                            try {
                                val mix = PlaylistInfo.getInfo(ServiceList.YouTube, url)
                                entries(mix.relatedItems, false, 80).map { it.track } to mix.nextPage
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                val stream = StreamInfo.getInfo(ServiceList.YouTube, YouTubeLinks.watch(id))
                                entries(stream.relatedItems.orEmpty(), false, 80).map { it.track } to null
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                selected?.let { previous.addLast(it); if (previous.size > 8) previous.removeFirst() }
                cursor = result.second?.takeIf { Page.isValid(it) && previous.none { old -> samePage(old, it) } }
                started = true
                result.first
            }
        }
    }

    suspend fun related(track: Track): List<Track> = withContext(Dispatchers.IO) {
        synchronized(engineLock) {
            val id = requireNotNull(YouTubeLinks.videoId(track.id))
            relatedCache[id]?.let { return@synchronized it }
            initialize()
            val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeLinks.watch(id))
            rememberRelated(id, info.relatedItems.orEmpty())
            relatedCache[id].orEmpty()
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
        rememberRelated(id, info.relatedItems.orEmpty())
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
