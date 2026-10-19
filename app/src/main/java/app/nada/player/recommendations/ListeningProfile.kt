package app.nada.player.recommendations

import app.nada.player.data.Track
import java.text.Normalizer
import java.util.Locale

data class ListeningStat(val track: Track, val plays: Int, val lastPlayed: Long)

/** Counts elapsed playing time, never the seek position or time spent paused/buffering. */
internal class QualifiedListening {
    private var track: Track? = null
    private var sampledAt = 0L
    private var playing = false
    private var listened = 0L
    private var counted = false

    fun sample(next: Track?, isPlaying: Boolean, now: Long, newSession: Boolean = false): Track? {
        if (playing) listened += (now - sampledAt).coerceAtLeast(0)
        val qualified = track?.takeIf { !counted && listened >= threshold(it) }
        if (qualified != null) counted = true
        if (track?.id != next?.id || newSession) {
            track = next; listened = 0; counted = false
        } else if (next != null) track = next
        sampledAt = now; playing = isPlaying && next != null
        return qualified
    }

    private fun threshold(track: Track): Long = if (track.duration > 0) minOf(30_000L, track.duration * 500L).coerceAtLeast(1000) else 30_000L
}

internal object ListeningRecommendations {
    data class Seed(val artist: String, val key: String)
    private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    fun artistKey(value: String): String = key(value.replace(Regex("\\s*-\\s*Topic$|VEVO$", RegexOption.IGNORE_CASE), ""))

    fun frequent(stats: List<ListeningStat>, source: String): List<Track> = stats.filter { it.track.source == source && it.plays >= 2 }
        .sortedWith(compareByDescending<ListeningStat> { it.plays }.thenByDescending { it.lastPlayed }).take(10).map { it.track }

    fun seeds(stats: List<ListeningStat>, history: List<Track>, source: String): List<Seed> {
        val known = stats.filter { it.track.source == source }
        val tracks = if (known.isNotEmpty()) known.map { it.track to it.plays.toLong() } else history.filter { it.source == source }.map { it to 1L }
        return tracks.groupBy { artistKey(it.first.artist) }.filterKeys { it.isNotBlank() && it !in setOf("youtube", "artis", "unknownartist") }
            .map { (id, entries) -> Triple(id, entries.first().first.artist.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "").trim(), entries.sumOf { it.second }) }
            .sortedByDescending { it.third }.take(2).map { Seed(it.second, it.first) }
    }

    /** Keep recommendations from the selected artists; don't re-suggest another upload of a heard song. */
    fun select(pools: List<List<Track>>, seeds: List<Seed>, heard: List<Track>, source: String): List<Track> {
        val heardIds = heard.mapTo(mutableSetOf()) { it.id }
        fun recording(track: Track) = artistKey(track.artist) + ":" + key(track.title.replace(
            Regex("[\\[(](?:official\\s*)?(?:music\\s*)?(?:video|audio|lyrics?|mv)[\\])]", RegexOption.IGNORE_CASE), ""))
        val heardRecordings = heard.mapTo(mutableSetOf(), ::recording)
        val seen = mutableSetOf<String>()
        val eligible = pools.mapIndexed { index, tracks -> tracks.filter {
            it.source == source && artistKey(it.artist) == seeds.getOrNull(index)?.key && it.id !in heardIds && recording(it) !in heardRecordings
        } }
        // Interleave the artists so one large result set cannot crowd out the other.
        return (0 until (eligible.maxOfOrNull { it.size } ?: 0)).flatMap { index -> eligible.mapNotNull { it.getOrNull(index) } }
            .filter { seen.add(recording(it)) }.take(12)
    }
}
