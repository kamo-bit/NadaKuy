package app.nada.player.recommendations

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import app.nada.player.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One application-scoped owner for local counts shared by the playback service and UI. */
class ListeningHistory private constructor(context: Application) {
    private val preferences = context.getSharedPreferences("nada_listening", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    private fun read(): List<ListeningStat> = runCatching {
        val value = preferences.getString("plays", "[]").orEmpty()
        require(value.length <= 1_048_576)
        require(value.toByteArray(Charsets.UTF_8).size <= 1_048_576)
        val array = JSONArray(value)
        (0 until minOf(array.length(), 500)).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            val track = Track.fromSaved(item.getJSONObject("track"))
            require(valid(track))
            val plays = item.getInt("plays"); val lastPlayed = item.getLong("lastPlayed")
            require(plays in 1..1_000_000 && lastPlayed >= 0)
            ListeningStat(track, plays, lastPlayed)
        }.getOrNull() }.distinctBy { it.track.id }
    }.getOrDefault(emptyList())

    @Synchronized fun record(track: Track, at: Long = System.currentTimeMillis()) {
        if (!valid(track)) return
        val previous = mutable.value
        val count = previous.firstOrNull { it.track.id == track.id }?.plays ?: 0
        val next = (listOf(ListeningStat(track, (count + 1).coerceAtMost(1_000_000), at.coerceAtLeast(0))) + previous.filterNot { it.track.id == track.id })
            .sortedByDescending { it.lastPlayed }.take(500)
        val retained = mutableListOf<ListeningStat>()
        val array = JSONArray()
        var characters = 2
        for (item in next) {
            val json = JSONObject().put("track", item.track.toJson()).put("plays", item.plays).put("lastPlayed", item.lastPlayed)
            val length = json.toString().toByteArray(Charsets.UTF_8).size + 1
            if (characters + length > 1_048_576) break
            characters += length; retained += item; array.put(json)
        }
        preferences.edit { putString("plays", array.toString()) }
        mutable.value = retained
    }

    private fun valid(track: Track) = (track.playableInNada || (track.source == "spotify" && track.id.matches(Regex("spotify:track:[A-Za-z0-9]+")))) &&
        track.id.length <= 128 && track.title.length in 1..500 && track.artist.length in 1..250 && track.artwork.length <= 2048 && track.permalink.length <= 2048 && track.genre.length <= 100

    companion object {
        @Volatile private var instance: ListeningHistory? = null
        fun get(context: Context): ListeningHistory = instance ?: synchronized(this) {
            instance ?: ListeningHistory(context.applicationContext as Application).also { instance = it }
        }
    }
}
