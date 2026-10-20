package app.nada.player.recommendations

import app.nada.player.data.Track
import kotlin.math.ln

/** Rank only related-source candidates. Listening affinity cannot add unrelated music. */
internal object RecommendationRanking {
    private data class Candidate(val order: Int, val track: Track, val artist: String)
    fun choose(candidates: List<Track>, recent: List<Track>, stats: List<ListeningStat>, limit: Int): List<Track> {
        val affinity = stats.groupBy { ListeningRecommendations.artistKey(it.track.artist) }
            .mapValues { (_, entries) -> (ln(1.0 + entries.sumOf { it.plays.toLong().coerceIn(0, 100) }) * 3).coerceAtMost(12.0) }
        val remaining = candidates.take(160).mapIndexed { index, track -> Candidate(index, track, ListeningRecommendations.artistKey(track.artist)) }.toMutableList()
        val window = recent.takeLast(9).map { ListeningRecommendations.artistKey(it.artist) }.toMutableList()
        val selected = mutableListOf<Track>()
        while (remaining.isNotEmpty() && selected.size < limit.coerceIn(0, 80)) {
            val counts = window.groupingBy { it }.eachCount()
            // Prefer a new artist while alternatives exist; a sparse pool must not stop playback.
            val withinQuota = remaining.filter { (counts[it.artist] ?: 0) < 2 }
            val options = withinQuota.ifEmpty { remaining }
            val different = options.filter { it.artist != window.lastOrNull() }
            val next = different.ifEmpty { options }.maxBy {
                160.0 - it.order + (affinity[it.artist] ?: 0.0) - 50 * (counts[it.artist] ?: 0)
            }
            remaining.remove(next)
            selected += next.track
            window += next.artist
            if (window.size > 9) window.removeAt(0)
        }
        return selected
    }
}
