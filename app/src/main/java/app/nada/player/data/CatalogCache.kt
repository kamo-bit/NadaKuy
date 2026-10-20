package app.nada.player.data

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Public catalog metadata only; media URLs, account data and listening counts never belong here. */
internal data class CatalogQuery(val source: String, val videos: Boolean, val query: String) {
    init { require(source in setOf("youtube", "audius")) }
    val key: String get() = JSONObject().put("source", source).put("videos", videos).put("query", query).toString()
}

internal class CatalogCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val at: Long, val tracks: List<Track>)
    private val lock = Any()

    /** Publish saved data before refreshing it. A failed refresh leaves the caller's visible data intact. */
    suspend fun load(query: CatalogQuery, refresh: Boolean = false, cached: (List<Track>) -> Unit = {},
        fetch: suspend () -> List<Track>): List<Track> {
        val saved = withContext(Dispatchers.IO) { read(query) }
        saved?.let { cached(it.tracks) }
        if (!refresh && saved != null && now() - saved.at in 0 until FRESH_MS) return saved.tracks
        val tracks = fetch().distinctBy(Track::id).take(MAX_TRACKS)
        currentCoroutineContext().ensureActive()
        if (tracks.isEmpty() && saved != null) throw IOException("Katalog belum mengembalikan lagu. Coba segarkan lagi.")
        // Empty responses may be transient and must not poison subsequent retries.
        if (tracks.isNotEmpty()) withContext(Dispatchers.IO) { write(query, tracks) }
        return tracks
    }

    private fun file(query: CatalogQuery): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(query.key.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun read(query: CatalogQuery): Entry? = synchronized(lock) {
        val file = file(query)
        try {
            val bytes = AtomicFile(file).openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_BYTES) {
                    val size = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                    if (size < 0) break
                    output.write(buffer, 0, size)
                }
                if (output.size() > MAX_BYTES) return@synchronized null
                output.toByteArray()
            }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.optInt("version") != 1 || json.optString("key") != query.key) return@synchronized null
            val at = json.getLong("at")
            if (now() - at !in 0 until RETAIN_MS) return@synchronized null
            val data = json.getJSONArray("tracks")
            if (data.length() !in 1..MAX_TRACKS) return@synchronized null
            val tracks = (0 until data.length()).map { Track.fromSaved(data.getJSONObject(it)) }
            if (tracks.any { it.source != query.source || it.id.isBlank() }) return@synchronized null
            Entry(at, tracks.distinctBy(Track::id))
        } catch (_: IOException) { null }
        catch (_: org.json.JSONException) { null }
    }

    private fun write(query: CatalogQuery, tracks: List<Track>) = synchronized(lock) {
        if (tracks.any { it.source != query.source }) return@synchronized
        val json = JSONObject().put("version", 1).put("key", query.key).put("at", now())
            .put("tracks", JSONArray().also { array -> tracks.forEach { array.put(it.toJson()) } })
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return@synchronized
        val atomic = AtomicFile(file(query))
        var stream: java.io.FileOutputStream? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
            directory.listFiles { item -> item.name.matches(Regex("[a-f0-9]{64}\\.json")) }
                ?.sortedByDescending(File::lastModified)?.drop(MAX_ENTRIES)?.forEach { AtomicFile(it).delete() }
        } catch (_: IOException) {
            // A full/evicted cache must not turn a successful catalog request into a playback error.
            if (stream != null) atomic.failWrite(stream)
        }
    }

    companion object {
        const val FRESH_MS = 6 * 60 * 60 * 1000L
        private const val RETAIN_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_BYTES = 256 * 1024
        private const val MAX_TRACKS = 80
        private const val MAX_ENTRIES = 12
    }
}
