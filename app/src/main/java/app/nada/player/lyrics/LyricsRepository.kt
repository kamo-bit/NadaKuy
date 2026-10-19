package app.nada.player.lyrics

import android.content.Context
import android.util.AtomicFile
import app.nada.player.data.Track
import app.nada.player.data.readBounded
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only metadata is sent to LRCLIB. Audio, account data and signed media URLs never leave this boundary. */
class LyricsRepository(context: Context) {
    private val directory = File(context.noBackupFilesDir, "lyrics").apply { mkdirs() }
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build()

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    suspend fun load(track: Track, refresh: Boolean = false): Pair<LyricsDocument?, Boolean> = withContext(Dispatchers.IO) {
        val file = cacheFile(track)
        val preferredSourceId = LyricsTiming.preferredSourceId(track)
        var cachedMatch: LyricsMatching.Match? = null
        if (!refresh && file.isFile && file.length() <= MAX_BYTES) {
            val cached = runCatching { file.inputStream().use { JsonParser.parseString(it.readBounded(MAX_BYTES).toString(Charsets.UTF_8)) } }
                .getOrNull()?.let { LyricsMatching.choose(listOf(it), track) }
            if (cached != null) {
                cachedMatch = cached
                file.setLastModified(System.currentTimeMillis())
                currentCoroutineContext().ensureActive()
                if (preferredSourceId == null || cached.document.id == preferredSourceId) {
                    return@withContext JapaneseRomaji.convert(cached.document) to true
                }
            }
        }
        val candidates = mutableListOf<JsonElement>()
        cachedMatch?.source?.let { candidates += it }
        var failure: Exception? = null
        suspend fun collect(endpoint: String, title: String = LyricsMatching.title(track), sourceId: Long? = null) {
            try {
                request(endpoint, track, title, sourceId)?.let { response ->
                    val json = JsonParser.parseString(response)
                    if (json.isJsonArray) candidates += json.asJsonArray.toList().take(50) else candidates += json
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure = error }
        }
        fun foundTimed(): Boolean = LyricsMatching.choose(candidates, track)?.document?.let { it.synced || it.instrumental } == true
        try { withTimeout(25_000) {
            preferredSourceId?.let { collect("get", sourceId = it) }
            if (!foundTimed()) collect("get")
            if (!foundTimed()) collect("search")
            if (!foundTimed()) JapaneseRomaji.romanize(LyricsMatching.title(track))?.let { alternate ->
                collect("get", alternate)
                if (!foundTimed()) collect("search", alternate)
            }
        } } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure = IOException("Pencarian lirik melewati batas waktu.", error)
        }
        currentCoroutineContext().ensureActive()
        val match = LyricsMatching.choose(candidates, track) ?: run {
            failure?.let { throw it }
            return@withContext null to false
        }
        val bytes = match.source.toString().toByteArray(Charsets.UTF_8)
        currentCoroutineContext().ensureActive()
        // A failed cache write must not hide successfully fetched lyrics.
        runCatching {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
            trimCache()
        }
        currentCoroutineContext().ensureActive()
        JapaneseRomaji.convert(match.document) to (match.source === cachedMatch?.source)
    }

    private fun cacheFile(track: Track): File {
        val signature = "1\u0000${track.id}\u0000${track.title}\u0000${track.artist}\u0000${track.duration}"
        val key = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }

    private fun trimCache() {
        val files = directory.listFiles { file -> file.isFile && file.name.matches(Regex("[0-9a-f]{64}\\.json")) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= 50 || bytes > 8 * 1024 * 1024) file.delete()
        }
    }

    private suspend fun request(endpoint: String, track: Track, title: String, sourceId: Long? = null): String? {
        val url = HttpUrl.Builder().scheme("https").host("lrclib.net").addPathSegment("api").addPathSegment(endpoint)
            .apply {
                if (sourceId != null) addPathSegment(sourceId.toString()) else {
                    addQueryParameter("track_name", title).addQueryParameter("artist_name", LyricsMatching.artist(track))
                    if (endpoint == "get" && track.duration > 0) addQueryParameter("duration", track.duration.toString())
                }
            }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "NadaKuy/1.6.2 (https://github.com/kamo-bit/NadaKuy)").build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (it.code == 404) null else {
                                check(it.isSuccessful) { "Layanan lirik belum tersedia. Coba lagi nanti." }
                                val bytes = requireNotNull(it.body).byteStream().readBounded(MAX_BYTES)
                                check(bytes.size <= MAX_BYTES) { "Data lirik terlalu besar. Coba lagu lain." }
                                bytes.toString(Charsets.UTF_8)
                            }
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    companion object { private const val MAX_BYTES = 1024 * 1024 }
}
