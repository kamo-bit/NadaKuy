package app.nada.player.recommendations

import android.content.Context
import androidx.core.content.edit
import app.nada.player.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Local choices affect automatic suggestions, never explicit searches, collections or user queues. */
data class RecommendationPreferences(
    val disliked: List<Track> = emptyList(), val blockedArtists: Map<String, String> = emptyMap(), val autoplay: Boolean = false,
) {
    private val dislikedKeys = disliked.mapTo(hashSetOf(), ListeningRecommendations::recordingKey)
    fun dislikes(track: Track) = ListeningRecommendations.recordingKey(track) in dislikedKeys
    fun blocks(artist: String) = ListeningRecommendations.artistKey(artist) in blockedArtists
    fun allows(track: Track) = !dislikes(track) && !blocks(track.artist)
}

internal class RecommendationSettings(context: Context, name: String = "nada_recommendations") {
    private val storage = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    private fun read(): RecommendationPreferences = runCatching {
        val text = storage.getString("choices", "{}").orEmpty()
        require(text.length <= 1_048_576 && text.toByteArray(Charsets.UTF_8).size <= 1_048_576)
        val json = JSONObject(text)
        val disliked = json.optJSONArray("disliked") ?: JSONArray()
        val artists = json.optJSONArray("artists") ?: JSONArray()
        RecommendationPreferences((0 until minOf(disliked.length(), 500)).mapNotNull {
            runCatching { Track.fromSaved(disliked.getJSONObject(it)).takeIf(::valid) }.getOrNull()
        }.distinctBy(ListeningRecommendations::recordingKey), (0 until minOf(artists.length(), 200)).mapNotNull {
            artists.optString(it).takeIf { artist -> artist.length in 1..250 }?.let { artist -> ListeningRecommendations.artistKey(artist) to artist }
        }.filter { it.first.isNotBlank() }.toMap(), json.optBoolean("autoplay", false))
    }.getOrDefault(RecommendationPreferences())

    fun toggleDislike(track: Track) {
        if (!valid(track)) return
        val old = mutable.value
        val others = old.disliked.filterNot { ListeningRecommendations.recordingKey(it) == ListeningRecommendations.recordingKey(track) }
        save(old.copy(disliked = if (old.dislikes(track)) others else (listOf(track) + others).take(500)))
    }
    fun toggleArtist(artist: String) {
        val key = ListeningRecommendations.artistKey(artist)
        if (key.isBlank() || artist.length !in 1..250) return
        val old = mutable.value
        save(old.copy(blockedArtists = if (key in old.blockedArtists) old.blockedArtists - key
            else (linkedMapOf(key to artist) + old.blockedArtists).entries.take(200).associate { it.toPair() }))
    }
    fun setAutoplay(enabled: Boolean) = save(mutable.value.copy(autoplay = enabled))

    private fun valid(track: Track) = track.id.length in 1..128 && track.title.length in 1..500 && track.artist.length in 1..250 &&
        track.artwork.length <= 2048 && track.permalink.length <= 2048 && track.genre.length <= 100
    private fun save(next: RecommendationPreferences) {
        val data = JSONObject().put("autoplay", next.autoplay)
            .put("disliked", JSONArray().also { array -> next.disliked.forEach { array.put(it.toJson()) } })
            .put("artists", JSONArray(next.blockedArtists.values.toList())).toString()
        if (data.toByteArray(Charsets.UTF_8).size > 1_048_576) return
        storage.edit { putString("choices", data) }
        mutable.value = next
    }
}
