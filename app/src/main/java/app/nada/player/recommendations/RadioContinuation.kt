package app.nada.player.recommendations

import app.nada.player.data.Track
import app.nada.player.youtube.YouTubeSearchRanking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Null ends this source's pages; an empty page may still have a continuation. */
internal fun interface RadioPages { suspend fun next(): List<Track>? }

/** A station owns its unconsumed candidates and source progress, independently of batch size. */
internal class RadioContinuation(private val anchor: Track, private val pages: (Track) -> RadioPages,
    private val resolve: suspend (Track) -> Track?) {
    private var source = pages(anchor)
    private var primary = true
    private val pending = ArrayDeque<Track>()
    private val ready = linkedMapOf<String, Track>()
    private val original = linkedMapOf<String, Track>()
    private val artists = hashSetOf(ListeningRecommendations.artistKey(anchor.artist))
    private val expanded = hashSetOf(anchor.id)
    private val examined = linkedSetOf<String>()

    suspend fun next(request: RadioRequest, preferences: RecommendationPreferences): List<Track> {
        val excluded = request.excluded.mapTo(hashSetOf(), ListeningRecommendations::recordingKey)
        val excludedIds = request.excluded.mapTo(hashSetOf(), Track::id)
        fun eligible(track: Track) = track.source == anchor.source && track.playableInNada && track.duration in 30..900 &&
            preferences.allows(track) && track.id !in excludedIds && ListeningRecommendations.recordingKey(track) !in excluded &&
            (track.source != "youtube" || YouTubeSearchRanking.originalPreferred(track.title))
        ready.entries.removeAll { !eligible(it.value) }
        if (ready.isNotEmpty()) return ready.values.toList()
        var fetched = 0
        var inspected = 0
        try {
            while (ready.size < 8 && inspected < 16) {
                currentCoroutineContext().ensureActive()
                if (pending.isEmpty()) {
                    if (fetched >= 2) break // Bound network work per refill, never the station's song count.
                    val page = source.next()
                    if (page == null) {
                        val counts = request.recent.groupingBy { ListeningRecommendations.artistKey(it.artist) }.eachCount()
                        val seed = original.values.filter { it.id !in expanded && preferences.allows(it) }.minByOrNull {
                            counts[ListeningRecommendations.artistKey(it.artist)] ?: 0
                        } ?: break
                        expanded += seed.id
                        source = pages(seed)
                        primary = false
                        continue
                    }
                    fetched++
                    pending.addAll(page.take(80).filter { it.id !in examined && it.id != anchor.id && it.duration in 30..900 &&
                        (it.source != "youtube" || YouTubeSearchRanking.originalPreferred(it.title)) })
                    currentCoroutineContext().ensureActive()
                    if (pending.isEmpty()) continue
                }
                val hint = pending.first()
                if (hint.id in examined) { pending.removeFirst(); continue }
                // Keep the hint on a failed/cancelled lookup; partial resolved data is retained too.
                val track = resolve(hint)
                currentCoroutineContext().ensureActive()
                pending.removeFirst(); inspected++
                examined += hint.id
                if (examined.size > 1000) examined.remove(examined.first())
                if (track == null) continue
                val artist = ListeningRecommendations.artistKey(track.artist)
                if (primary) {
                    artists += artist
                    if (original.size < 128) original.putIfAbsent(track.id, track)
                }
                // Only the original station's pages may introduce artists. Fallback mixes stay anchored.
                if (artist in artists && eligible(track)) ready[ListeningRecommendations.recordingKey(track)] = track
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (ready.isEmpty()) throw failure }
        return ready.values.toList()
    }
}
