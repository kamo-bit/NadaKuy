package app.nada.player.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.time.Instant

data class LibrarySnapshot(
    val liked: List<Track>, val history: List<Track>,
    val playlists: List<LocalPlaylist>, val searches: List<String>,
)

data class CollectionBackup(val exportedAt: String, val collection: LibrarySnapshot)

/** The portable format contains collection metadata only, never sessions or media URLs. */
object LibraryBackup {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    private val gson = Gson()

    fun encode(collection: LibrarySnapshot, exportedAt: String = Instant.now().toString()): ByteArray {
        val document = JsonObject().apply {
            addProperty("format", "nadakuy.collection")
            addProperty("version", 1)
            addProperty("exportedAt", exportedAt)
            add("collection", gson.toJsonTree(collection))
        }
        val bytes = gson.toJson(document).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Koleksi terlalu besar untuk satu backup (maksimal 8 MB)." }
        // Every file we write must also satisfy the import contract.
        decode(bytes)
        return bytes
    }

    fun read(input: InputStream): CollectionBackup {
        val bytes = input.readBounded(MAX_BYTES)
        require(bytes.size <= MAX_BYTES) { "File backup terlalu besar. Batasnya 8 MB." }
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): CollectionBackup {
        require(bytes.size <= MAX_BYTES) { "File backup terlalu besar. Batasnya 8 MB." }
        try {
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(root.string("format", 64) == "nadakuy.collection") { "File ini bukan backup koleksi NadaKuy." }
            require(root.get("version")?.toString() == "1") { "Versi backup belum didukung. Perbarui NadaKuy terlebih dahulu." }
            val date = root.string("exportedAt", 64)
            Instant.parse(date)
            val data = root.getAsJsonObject("collection")
            var entries = 0
            fun tracks(array: JsonArray): List<Track> {
                entries += array.size()
                require(entries <= MAX_ENTRIES) { "Backup berisi terlalu banyak lagu (maksimal 20.000 entri)." }
                return array.map { value ->
                    val item = value.asJsonObject
                    val duration = item.get("duration")?.toString()?.toIntOrNull()
                    require(duration != null && duration >= 0) { "Durasi lagu dalam backup tidak valid." }
                    Track(item.string("id", 256), item.string("title", 4096), item.string("artist", 4096),
                        item.url("artwork"), duration, item.string("genre", 1024, true), item.url("permalink"),
                        item.string("source", 32)).also { track ->
                        require(track.playableInNada || (track.source == "spotify" && track.id.matches(Regex("spotify:track:[A-Za-z0-9]+")))) {
                            "Identitas atau sumber lagu dalam backup tidak didukung."
                        }
                    }
                }.distinctBy(Track::id)
            }
            val liked = tracks(data.getAsJsonArray("liked"))
            val history = tracks(data.getAsJsonArray("history")).take(40)
            val playlistData = data.getAsJsonArray("playlists")
            require(playlistData.size() <= 1000) { "Backup berisi terlalu banyak playlist (maksimal 1.000)." }
            val playlists = playlistData.map { value ->
                val item = value.asJsonObject
                LocalPlaylist(item.string("id", 256), item.string("name", 60), tracks(item.getAsJsonArray("tracks")))
            }
            require(playlists.map { it.id }.distinct().size == playlists.size) { "Identitas playlist dalam backup terduplikasi." }
            val searches = data.getAsJsonArray("searches")
            require(searches.size() <= 10) { "Riwayat pencarian dalam backup tidak valid." }
            val queries = searches.map { item ->
                require(item.isJsonPrimitive && item.asJsonPrimitive.isString)
                item.asString.also { require(it.isNotBlank() && it.length <= 100) }
            }.distinctBy { it.lowercase() }
            return CollectionBackup(date, LibrarySnapshot(liked, history, playlists, queries))
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("File backup tidak lengkap atau rusak. Pilih file backup NadaKuy yang lain.", error)
        }
    }

    fun merge(current: LibrarySnapshot, incoming: LibrarySnapshot): LibrarySnapshot {
        val incomingById = incoming.playlists.associateBy { it.id }
        val playlists = current.playlists.map { playlist ->
            val extra = incomingById[playlist.id]
            if (extra == null) playlist else playlist.copy(tracks = (playlist.tracks + extra.tracks).distinctBy(Track::id))
        } + incoming.playlists.filter { extra -> current.playlists.none { it.id == extra.id } }
        return LibrarySnapshot(
            (current.liked + incoming.liked).distinctBy(Track::id),
            (current.history + incoming.history).distinctBy(Track::id).take(40), playlists,
            (current.searches + incoming.searches).distinctBy { it.lowercase() }.take(10),
        )
    }

    private fun JsonObject.string(key: String, limit: Int, empty: Boolean = false): String {
        val item = get(key)
        require(item != null && item.isJsonPrimitive && item.asJsonPrimitive.isString) { "Kolom $key dalam backup tidak valid." }
        return item.asString.also { require(it.length <= limit && (empty || it.isNotBlank())) { "Kolom $key dalam backup tidak valid." } }
    }

    private fun JsonObject.url(key: String): String = string(key, 8192, true).also { value ->
        require(value.isEmpty() || value.startsWith("/") || runCatching {
            val uri = java.net.URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)) { "Tautan lagu dalam backup tidak valid." }
    }
}
