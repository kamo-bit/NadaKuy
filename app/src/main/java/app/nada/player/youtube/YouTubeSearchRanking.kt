package app.nada.player.youtube

import app.nada.player.data.Track
import java.text.Normalizer
import java.util.Locale

/** Ranking hints, never an assertion that an uploader owns the original recording. */
internal object YouTubeSearchRanking {
    data class Entry(val track: Track, val verifiedUploader: Boolean = false)

    private data class Version(val title: Regex, val query: Regex = title)
    private val versions = listOf(
        Version(Regex("\\bcover(?:s|ed)?\\b|カバー|歌ってみた")),
        Version(Regex("(?:\\(|\\[|[-–—:]\\s*)live\\b|\\blive\\s+(?:at|from|in|on|version|performance|session|concert)\\b|\\b(?:konser|concert|tour|festival|the first take)\\b"), Regex("(?:^live$|\\blive\\s*$|\\blive\\s+(?:at|from|in|on|version|performance|session)\\b|\\b(?:konser|concert|tour|festival|the first take)\\b)")),
        Version(Regex("\\bkaraoke\\b")), Version(Regex("\\binstrumental\\b")),
        Version(Regex("\\bremix\\b")), Version(Regex("\\b(slowed|reverb)\\b")),
        Version(Regex("\\b(sped\\s*up|nightcore)\\b")), Version(Regex("\\b8d\\b")),
        Version(Regex("\\bbass\\s*boost(?:ed)?\\b")),
        Version(Regex("\\b(acoustic|akustik|unplugged)\\b")),
        Version(Regex("\\b(reaction|tutorial|shorts?|medley|compilation|full\\s+album|mashup)\\b")),
    )
    private val decorations = setOf("official", "audio", "video", "music", "musik", "lagu", "mv", "lyrics", "lyric", "lirik", "version", "cover", "live", "remix", "acoustic", "akustik", "karaoke", "instrumental", "slowed", "reverb", "sped", "up")
    private val marks = Regex("\\p{M}+")
    private val letterDigitBoundary = Regex("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val officialAudio = Regex("\\bofficial\\s+(?:music\\s+)?(?:audio|video)\\b")

    private data class Scored(val track: Track, val coverage: Int, val titleFocus: Int, val requested: Int, val unexpected: Int, val relevance: Int, val provenance: Int)

    fun originalPreferred(query: String): Boolean = versions.none { it.query.containsMatchIn(query.lowercase(Locale.ROOT)) }

    fun rank(query: String, entries: List<Entry>): List<Track> {
        val wanted = tokens(query) - decorations
        val requestedVersions = versions.map { it.query.containsMatchIn(query.lowercase(Locale.ROOT)) }
        return entries.map { entry ->
            val title = entry.track.title.lowercase(Locale.ROOT)
            val titleTokens = tokens(title)
            val artist = tokens(entry.track.artist)
            val combined = titleTokens + artist
            // Query relevance dominates, with artist matches resolving ambiguous song names.
            val coverage = if (wanted.isEmpty()) 0 else 1000 * wanted.count { it in combined } / wanted.size
            // A song called Kangen outranks unrelated songs by Kangen Band.
            val titleMatch = if (wanted.isEmpty()) 0 else 250 * wanted.count { it in titleTokens } / wanted.size
            val exactTitle = if (wanted.isNotEmpty() && titleTokens - decorations == wanted) 120 else 0
            val artistWeight = if (wanted.size > 1) 240 else 40
            val artistMatch = if (wanted.isEmpty()) 0 else artistWeight * wanted.count { it in artist } / wanted.size
            val foundVersions = versions.map { it.title.containsMatchIn(title) }
            val requested = versions.indices.count { requestedVersions[it] && foundVersions[it] }
            val unexpected = versions.indices.count { !requestedVersions[it] && foundVersions[it] }
            val provenance = (if (entry.verifiedUploader) 8 else 0) +
                (if (officialAudio.containsMatchIn(normalize(title))) 24 else 0) +
                (if (entry.track.artist.endsWith(" - Topic", true)) 80 else 0) +
                (if (entry.track.artist.endsWith("VEVO", true)) 64 else 0)
            Scored(entry.track, coverage, if (wanted.size == 1 && titleMatch > 0) 1 else 0, requested, unexpected, titleMatch + exactTitle + artistMatch, provenance)
        }.sortedWith(compareByDescending<Scored> { it.coverage }
            .thenByDescending { it.titleFocus }
            .thenByDescending { it.requested }.thenBy { it.unexpected }
            .thenByDescending { it.relevance + it.provenance }).map { it.track }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(marks, "").lowercase(Locale.ROOT)
        .replace(letterDigitBoundary, " ")
        .replace(separators, " ").trim()

    private fun tokens(value: String): Set<String> = normalize(value).split(' ').filter(String::isNotBlank).toSet()
}
