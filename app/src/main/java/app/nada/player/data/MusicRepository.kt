package app.nada.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String,
    val duration: Int,
    val genre: String,
    val permalink: String = "",
    val source: String = "audius",
) {
    val playableInNada: Boolean get() = when (source) {
        "audius" -> id.matches(Regex("[a-zA-Z0-9]+"))
        "youtube" -> id.startsWith("youtube:") && app.nada.player.youtube.YouTubeLinks.videoId(id) != null
        else -> false
    }
    val streamUrl: String get() {
        check(source == "audius") { "This source requires its own playback resolver" }
        return "https://api.audius.co/v1/tracks/$id/stream?app_name=NadaAndroid"
    }
    fun toJson() = JSONObject().put("id", id).put("title", title).put("artist", artist)
        .put("artwork", artwork).put("duration", duration).put("genre", genre).put("permalink", permalink).put("source", source)

    companion object {
        fun fromSaved(json: JSONObject) = Track(json.getString("id"), json.getString("title"),
            json.getString("artist"), json.optString("artwork"), json.optInt("duration"),
            json.optString("genre"), json.optString("permalink"), json.optString("source", "audius"))
    }
}

data class LocalPlaylist(val id: String, val name: String, val tracks: List<Track>)

class MusicRepository(context: Context) {
    private val preferences = context.getSharedPreferences("nada_library", Context.MODE_PRIVATE)
    var liked: List<Track> = readTracks("liked")
        private set
    var history: List<Track> = readTracks("history")
        private set
    var playlists: List<LocalPlaylist> = runCatching {
        val array = JSONArray(preferences.getString("playlists", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LocalPlaylist(item.getString("id"), item.getString("name"), decodeTracks(item.getJSONArray("tracks")))
        }
    }.getOrDefault(emptyList())
        private set

    val cachedHome: List<Track> get() = readTracks("home")
    val searchHistory: List<String> get() = runCatching {
        val items = JSONArray(preferences.getString("searches", "[]"))
        (0 until items.length()).map { items.getString(it) }
    }.getOrDefault(emptyList())

    fun rememberSearch(query: String) {
        val text = query.trim().take(100)
        if (text.isBlank()) return
        val items = (listOf(text) + searchHistory.filterNot { it.equals(text, ignoreCase = true) }).take(10)
        preferences.edit().putString("searches", JSONArray(items).toString()).apply()
    }

    fun clearSearchHistory() { preferences.edit().remove("searches").apply() }

    fun renamePlaylist(id: String, name: String) {
        val trimmed = name.trim().take(60)
        require(trimmed.isNotBlank())
        playlists = playlists.map { if (it.id == id) it.copy(name = trimmed) else it }
        savePlaylists()
    }

    suspend fun trending(genre: String? = null): List<Track> {
        val result = request("tracks/trending?limit=48&time=week" + (genre?.let { "&genre=${encode(it)}" } ?: ""))
        if (genre == null) saveTracks("home", result)
        return result
    }

    suspend fun search(query: String): List<Track> = request("tracks/search?query=${encode(query)}&limit=60")

    private suspend fun request(endpoint: String): List<Track> = withContext(Dispatchers.IO) {
        val connection = URI("https://api.audius.co/v1/$endpoint&app_name=NadaAndroid").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "NadaAndroid/1.0")
            check(connection.responseCode == 200) { "Katalog belum dapat dimuat. Periksa koneksi, lalu coba lagi." }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(text).getJSONArray("data")
            (0 until data.length()).mapNotNull { index ->
                val item = data.getJSONObject(index)
                if (!item.optBoolean("is_streamable", false) || item.optBoolean("is_stream_gated", true)) return@mapNotNull null
                val user = item.optJSONObject("user") ?: return@mapNotNull null
                val id = item.optString("id")
                if (!id.matches(Regex("[a-zA-Z0-9]+"))) return@mapNotNull null
                Track(id, item.optString("title", "Tanpa judul"), user.optString("name", "Artis"),
                    item.optJSONObject("artwork")?.optString("480x480")?.takeIf { it.startsWith("https://") } ?: "",
                    item.optInt("duration"), item.optString("genre"), item.optString("permalink"))
            }.distinctBy { it.id }
        } finally { connection.disconnect() }
    }

    fun toggleLike(track: Track) {
        liked = if (liked.any { it.id == track.id }) liked.filterNot { it.id == track.id } else listOf(track) + liked
        saveTracks("liked", liked)
    }

    fun rememberPlayed(track: Track) {
        history = (listOf(track) + history.filterNot { it.id == track.id }).take(40)
        saveTracks("history", history)
    }

    fun createPlaylist(name: String): LocalPlaylist {
        val result = LocalPlaylist(UUID.randomUUID().toString(), name.trim().take(60), emptyList())
        require(result.name.isNotBlank())
        playlists = playlists + result
        savePlaylists()
        return result
    }

    fun addToPlaylist(id: String, track: Track) {
        playlists = playlists.map { if (it.id == id) it.copy(tracks = (it.tracks + track).distinctBy(Track::id)) else it }
        savePlaylists()
    }

    fun removeFromPlaylist(id: String, trackId: String) {
        playlists = playlists.map { if (it.id == id) it.copy(tracks = it.tracks.filterNot { song -> song.id == trackId }) else it }
        savePlaylists()
    }

    fun deletePlaylist(id: String) { playlists = playlists.filterNot { it.id == id }; savePlaylists() }

    private fun savePlaylists() {
        val array = JSONArray()
        playlists.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("tracks", encodeTracks(it.tracks))) }
        preferences.edit().putString("playlists", array.toString()).apply()
    }

    private fun readTracks(key: String): List<Track> = runCatching {
        decodeTracks(JSONArray(preferences.getString(key, "[]")))
    }.getOrDefault(emptyList())

    private fun saveTracks(key: String, tracks: List<Track>) = preferences.edit().putString(key, encodeTracks(tracks).toString()).apply()
    private fun decodeTracks(array: JSONArray): List<Track> = (0 until array.length()).map { Track.fromSaved(array.getJSONObject(it)) }
    private fun encodeTracks(tracks: List<Track>) = JSONArray().also { array -> tracks.forEach { array.put(it.toJson()) } }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
