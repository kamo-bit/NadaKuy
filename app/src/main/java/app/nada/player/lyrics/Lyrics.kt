package app.nada.player.lyrics

import app.nada.player.data.Track
import com.google.gson.JsonElement
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String, val romaji: String? = null)
data class LyricsDocument(val id: Long, val title: String, val artist: String, val duration: Double,
    val instrumental: Boolean, val lines: List<LyricLine>, val synced: Boolean,
    val romanized: Boolean = false, val chosen: Boolean = false)
data class LyricsState(val trackId: String? = null, val loading: Boolean = false,
    val document: LyricsDocument? = null, val cached: Boolean = false, val error: String? = null)

/** A search result offered in the lyrics browser. [document] already reflects whether timestamps fit this track. */
data class LyricsCandidate(val document: LyricsDocument, val album: String, val timedAvailable: Boolean,
    val durationDifference: Double?, val source: JsonElement)
data class LyricsSearchState(val trackId: String? = null, val query: String = "", val loading: Boolean = false,
    val results: List<LyricsCandidate> = emptyList(), val searched: Boolean = false, val error: String? = null)

object LrcParser {
    private val time = Regex("\\[(\\d{1,4}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d{1,8})]", RegexOption.IGNORE_CASE)
    private val wordTime = Regex("<\\d{1,4}:[0-5]\\d(?:[.:]\\d{1,3})?>")

    fun parse(value: String): List<LyricLine> {
        require(value.length <= 128_000) { "Lirik terlalu panjang." }
        val advance = offset.find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = value.lineSequence().flatMap { raw ->
            val stamps = time.findAll(raw).toList()
            if (stamps.isEmpty()) emptySequence() else {
                val text = wordTime.replace(raw.substring(stamps.last().range.last + 1), "").trim()
                require(text.length <= 2000) { "Baris lirik terlalu panjang." }
                stamps.asSequence().map { stamp ->
                    val minutes = stamp.groupValues[1].toLong()
                    val seconds = stamp.groupValues[2].toLong()
                    val millis = stamp.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
                    LyricLine((minutes * 60_000 + seconds * 1000 + millis - advance).coerceAtLeast(0), text)
                }
            }
        }.take(3001).toList()
        require(lines.size <= 3000) { "Terlalu banyak baris lirik." }
        return lines.groupBy { it.timeMs }.toSortedMap().map { (time, entries) ->
            LyricLine(time, entries.map { it.text }.distinct().joinToString("\n"))
        }
    }
}

/** Last timestamp at or before the actual player position; seeking needs no retained cursor. */
fun activeLyricIndex(lines: List<LyricLine>, position: Long): Int {
    var low = 0
    var high = lines.lastIndex
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (lines[middle].timeMs <= position) low = middle + 1 else high = middle - 1
    }
    return high
}

object LyricsMatching {
    data class Match(val document: LyricsDocument, val source: JsonElement)
    fun title(track: Track): String = track.title.replace(
        Regex("[\\[(](?:official\\s*)?(?:music\\s*)?(?:video|audio|lyrics?|visuali[sz]er|mv)[\\])]", RegexOption.IGNORE_CASE), "")
        .trim().replace(Regex("\\s+"), " ")
    fun artist(track: Track): String = track.artist.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "").trim()
    private fun key(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    fun query(track: Track): String = listOf(title(track), artist(track)).filter(String::isNotBlank).joinToString(" ")

    fun choose(values: List<JsonElement>, track: Track): Match? {
        val preferredSourceId = LyricsTiming.preferredSourceId(track)
        val titles = listOfNotNull(key(title(track)), JapaneseRomaji.romanize(title(track))?.let(::key)).toSet()
        return values.mapNotNull { parse(it, track, titles) }
            .sortedWith(compareBy<Pair<Double, Match>> { if (it.second.document.synced) 0 else 1 }
                .thenBy { if (it.second.document.id == preferredSourceId) 0 else 1 }.thenBy { it.first })
            .firstOrNull()?.second
    }

    /** A record the listener picked; it replaces the title/artist check, while the duration check still guards timestamps. */
    fun selected(item: JsonElement, track: Track): Match? = parse(item, track, titles = null)?.second
        ?.let { it.copy(document = it.document.copy(chosen = true)) }

    fun candidates(values: List<JsonElement>, track: Track): List<LyricsCandidate> = values.mapNotNull { item ->
        val (distance, match) = parse(item, track, titles = null) ?: return@mapNotNull null
        val json = item.asJsonObject
        val album = runCatching { json.get("albumName")?.takeUnless { it.isJsonNull }?.asString }.getOrNull().orEmpty()
        val timed = runCatching { json.get("syncedLyrics")?.takeUnless { it.isJsonNull }?.asString }.getOrNull().orEmpty().isNotBlank()
        LyricsCandidate(match.document, album.take(200), timed, distance.takeIf { it != Double.MAX_VALUE }, item)
    }.distinctBy { it.document.id }
        .sortedWith(compareBy<LyricsCandidate> { if (it.document.synced) 0 else 1 }.thenBy { it.durationDifference ?: Double.MAX_VALUE })

    /** [titles] holds the accepted title keys; null skips the identity check for a record the listener picked. */
    private fun parse(item: JsonElement, track: Track, titles: Set<String>?): Pair<Double, Match>? =
        runCatching {
            val json = item.asJsonObject
            val title = json.get("trackName").asString
            val artist = json.get("artistName").asString
            val duration = json.get("duration").asDouble
            if (titles != null && (key(title) !in titles || key(artist) != key(LyricsMatching.artist(track)))) return@runCatching null
            if (!duration.isFinite() || duration < 0) return@runCatching null
            val distance = if (track.duration > 0) abs(track.duration - duration) else Double.MAX_VALUE
            val instrumental = json.get("instrumental")?.asBoolean == true && distance <= 4
            val timed = json.get("syncedLyrics")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val plain = json.get("plainLyrics")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            require(plain.length <= 128_000)
            val synced = timed.isNotBlank() && distance <= 4
            val lines = when {
                synced -> LrcParser.parse(timed)
                // A picked record without plain text keeps its words but drops timestamps that do not fit this recording.
                titles == null && plain.isBlank() && timed.isNotBlank() ->
                    LrcParser.parse(timed).filter { it.text.isNotBlank() }.map { it.copy(timeMs = 0) }
                else -> plain.lineSequence().map { it.trim() }.filter(String::isNotEmpty).take(3001).map { LyricLine(0, it) }.toList()
            }
            require(lines.size <= 3000 && lines.all { it.text.length <= 2000 })
            if (!instrumental && lines.none { it.text.isNotBlank() }) return@runCatching null
            val id = json.get("id").asLong
            require(id > 0)
            val document = LyricsTiming.apply(track, LyricsDocument(id, title, artist, duration, instrumental, lines, synced && lines.isNotEmpty()))
            distance to Match(document, item)
        }.getOrNull()
}
