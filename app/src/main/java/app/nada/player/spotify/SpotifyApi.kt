package app.nada.player.spotify

import app.nada.player.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class SpotifyApi(private val auth: SpotifyAuth) {
    suspend fun search(query: String): List<Track> {
        val json = get("search?q=${encode(query)}&type=track&limit=10")
        return SpotifyTracks.parse(json.optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray())
    }
    suspend fun queue(): SpotifyQueue {
        val json = get("me/player/queue")
        val current = json.optJSONObject("currently_playing")?.let { SpotifyTracks.parse(JSONArray().put(it)).firstOrNull() }
        val upcoming = SpotifyTracks.parse(json.optJSONArray("queue") ?: JSONArray()).take(50)
        return SpotifyQueue(current, upcoming)
    }
    suspend fun get(path: String): JSONObject {
        var token = auth.token()
        repeat(2) { attempt ->
            val response = withContext(Dispatchers.IO) {
                val connection = URI("https://api.spotify.com/v1/$path").toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Accept", "application/json")
                    val status = connection.responseCode
                    val body = if (status == 200) connection.inputStream.bufferedReader().use { it.readText() } else ""
                    status to body
                } finally { connection.disconnect() }
            }
            when (response.first) {
                200 -> return JSONObject(response.second)
                204 -> return JSONObject()
                401 -> if (attempt == 0) token = auth.token(forceRefresh = true) else error("Sesi Spotify berakhir. Hubungkan kembali akunmu.")
                403 -> error("Spotify membatasi akses aplikasi ini. Periksa akun Premium dan Users and Access di Dashboard.")
                429 -> error("Batas permintaan Spotify tercapai. Tunggu sebentar lalu coba lagi.")
                else -> error("Spotify belum dapat dimuat. Periksa koneksi lalu coba lagi.")
            }
        }
        error("Hubungkan kembali akun Spotify.")
    }
}

data class SpotifyQueue(val current: Track?, val upcoming: List<Track>)

object SpotifyTracks {
    fun parse(items: JSONArray): List<Track> = (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id")
        if (!id.matches(Regex("[a-zA-Z0-9]{22}")) || item.optString("type", "track") != "track" || (item.has("is_playable") && !item.optBoolean("is_playable"))) return@mapNotNull null
        val artists = item.optJSONArray("artists") ?: return@mapNotNull null
        val artist = (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }.joinToString(", ")
        val artwork = item.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
        Track("spotify:track:$id", item.optString("name", "Tanpa judul"), artist,
            artwork.takeIf { it.startsWith("https://") }.orEmpty(), (item.optLong("duration_ms") / 1000).toInt(), "",
            "https://open.spotify.com/track/$id", "spotify")
    }
}
