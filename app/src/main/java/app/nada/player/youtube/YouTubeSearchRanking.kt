package app.nada.player.youtube

import app.nada.player.data.Track
import java.text.Normalizer
import java.util.Locale

/** Ranking hints, never an assertion that an uploader owns the original recording. */
internal object YouTubeSearchRanking {
    data class Entry(val track: Track, val verifiedUploader: Boolean = false)

    private data class Version(val title: Regex, val query: Regex = title)
    private val versions = listOf(
        Version(Regex("\\bcover(?:s|ed)?\\b")),
        Version(Regex("(?:\\(|\\[|[-–—:]\\s*)live\\b|\\blive\\s+(?:at|from|in|on|version|performance|session|concert)\\b|\\b(?:konser|concert|tour|festival)\\b"), Regex("\\b(live|konser|concert|tour|festival)\\b")),
        Version(Regex("\\bkaraoke\\b")), Version(Regex("\\binstrumental\\b")),
        Version(Regex("\\bremix\\b")), Version(Regex("\\b(slowed|reverb)\\b")),
        Version(Regex("\\b(sped\\s*up|nightcore)\\b")), Version(Regex("\\b8d\\b")),
        Version(Regex("\\bbass\\s*boost(?:ed)?\\b")),
        Version(Regex("\\b(acoustic|akustik|unplugged)\\b")),
    )
    private val decorations = setOf("official", "audio", "video", "music", "musik", "lagu", "mv", "lyrics", "lyric", "lirik", "version", "cover", "live", "remix", "acoustic", "akustik", "karaoke", "instrumental", "slowed", "reverb", "sped", "up")
    private val marks = Regex("\\p{M}+")
    private val letterDigitBoundary = Regex("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val officialAudio = Regex("\\bofficial\\s+audio\\b")

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
            val versionScore = versions.indices.sumOf { index ->
                val found = versions[index].title.containsMatchIn(title)
                when {
                    requestedVersions[index] -> if (found) 160 else 0
                    found -> -260
                    else -> 0
                }
            }
            val provenance = (if (entry.verifiedUploader) 20 else 0) +
                (if (officialAudio.containsMatchIn(normalize(title))) 12 else 0) +
                (if (entry.track.artist.endsWith(" - Topic", true) || entry.track.artist.endsWith("VEVO", true)) 8 else 0)
            entry.track to (coverage + titleMatch + exactTitle + artistMatch + versionScore + provenance)
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(marks, "").lowercase(Locale.ROOT)
        .replace(letterDigitBoundary, " ")
        .replace(separators, " ").trim()

    private fun tokens(value: String): Set<String> = normalize(value).split(' ').filter(String::isNotBlank).toSet()
}
