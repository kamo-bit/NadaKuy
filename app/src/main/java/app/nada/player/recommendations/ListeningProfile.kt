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
    private val artistDecorations = Regex("\\s*-\\s*Topic$|VEVO$|\\s+(?:official(?:\\s+channel)?|tv)$", RegexOption.IGNORE_CASE)
    private val recordingDecorations = Regex("[\\[(](?:(?:official\\s*)?(?:music\\s*)?(?:video|audio|lyrics?|mv)|remaster(?:ed)?(?:\\s+\\d{4})?)[\\])]", RegexOption.IGNORE_CASE)
    data class Seed(val track: Track) {
        val artist: String = artistName(track.artist)
        val key: String = artistKey(track.artist)
    }
    private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    fun artistName(value: String): String = value.replace(artistDecorations, "").trim()
    fun artistKey(value: String): String = key(artistName(value))
    fun recordingKey(track: Track): String = artistKey(track.artist) + ":" + key(track.title.replace(recordingDecorations, ""))

    fun frequent(stats: List<ListeningStat>, source: String): List<Track> = stats.filter { it.track.source == source && it.plays >= 2 }
        .sortedWith(compareByDescending<ListeningStat> { it.plays }.thenByDescending { it.lastPlayed }).take(10).map { it.track }

    fun seeds(stats: List<ListeningStat>, history: List<Track>, source: String, preferences: RecommendationPreferences = RecommendationPreferences()): List<Seed> {
        val known = stats.filter { it.track.source == source && preferences.allows(it.track) }
        val tracks = if (known.isNotEmpty()) known.sortedWith(compareByDescending<ListeningStat> { it.plays }.thenByDescending { it.lastPlayed }).map { it.track to it.plays.coerceAtMost(100).toLong() }
            else history.filter { it.source == source && preferences.allows(it) }.map { it to 1L }
        val ranked = tracks.groupBy { artistKey(it.first.artist) }.filterKeys { it.isNotBlank() && it !in setOf("youtube", "artis", "unknownartist") }
            .map { (_, entries) -> entries.first().first to entries.sumOf { it.second } }
            .sortedByDescending { it.second }.map { it.first }
        val recent = known.maxByOrNull { it.lastPlayed }?.track
        // Two enduring favorites plus a recent interest; don't let lifetime counts crowd it out.
        return (ranked.take(2) + listOfNotNull(recent).filter { track -> ranked.any { artistKey(it.artist) == artistKey(track.artist) } } + ranked)
            .distinctBy { artistKey(it.artist) }.take(3).map(::Seed)
    }

    /** Each pool is related to a history seed; discoveries retain that source relationship. */
    fun select(pools: List<List<Track>>, seeds: List<Seed>, heard: List<Track>, source: String, limit: Int = 12,
        preferences: RecommendationPreferences = RecommendationPreferences(), stats: List<ListeningStat> = emptyList()): List<Track> {
        val heardIds = heard.mapTo(mutableSetOf()) { it.id }
        val heardRecordings = heard.mapTo(mutableSetOf(), ::recordingKey)
        val seen = mutableSetOf<String>()
        val eligible = pools.mapIndexed { index, tracks -> tracks.filter {
            seeds.getOrNull(index) != null && it.source == source && preferences.allows(it) &&
                (source != "spotify" || artistKey(it.artist) == seeds[index].key) &&
                (source != "youtube" || app.nada.player.youtube.YouTubeSearchRanking.originalPreferred(it.title)) &&
                it.id !in heardIds && recordingKey(it) !in heardRecordings
        } }
        // Interleave the artists so one large result set cannot crowd out the other.
        val candidates = (0 until (eligible.maxOfOrNull { it.size } ?: 0)).flatMap { index -> eligible.mapNotNull { it.getOrNull(index) } }
            .filter { seen.add(recordingKey(it)) }
        return RecommendationRanking.choose(candidates, emptyList(), stats, limit)
    }
}
