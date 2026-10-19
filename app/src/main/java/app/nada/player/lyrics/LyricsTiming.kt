package app.nada.player.lyrics

import app.nada.player.data.Track
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Recording-specific adjustments. Cached provider data always remains unmodified. */
internal object LyricsTiming {
    internal data class Profile(val trackId: String, val duration: Long, val sourceId: Long,
        val sourceDigest: String, val offsetMs: Long, val lineOffsets: Map<Long, Long> = emptyMap())

    // Compared with vocal onsets at the beginning, middle and end of these exact recordings.
    // Source fingerprints invalidate adjustments when the provider revises its timestamps/text.
    private val profiles = listOf(
        Profile("youtube:by4SYYWlhEs", 262, 36263563,
            "5ff6aaa39794236861688c4303f350fc2cc3ee1356ce5c57bdbfafcc351ea4d1", 800),
        Profile("youtube:LgSLygQdHS4", 257, 34873437,
            "844ab775c288f1f57b0bf14c0c248917b8bb5a41371a8181581fe35137b4268e", 300),
        Profile("youtube:W49ItRK0QlM", 291, 3691064,
            "ed9499eec43a070316b7dca4c7b1965fb25ae68578e54e3a0fc9b310527a49bc", 0),
        Profile("youtube:YKK5_OQiEa4", 304, 21914365,
            "5825eab2076a7bead1de48844ed0a12dcdcf5e68a57242fcd96ff3fd10cf8c97", 600,
            lineOffsets = mapOf(14_040L to 1_100L)),
        Profile("youtube:k1BfsO0mxWQ", 289, 4662600,
            "8e0d7004a66f2ee7119e71c52c19bec1f66339876c65d40a13446a0b762a6736", 0),
        Profile("youtube:pXAfNOZiv8o", 198, 3000148,
            "ffd883e51c5432f0cdbf01badccd9593489bd9ccffc2c3c57ffc64e7165fa241", -250)
    )

    fun preferredSourceId(track: Track): Long? = profiles.firstOrNull {
        it.trackId == track.id && abs(track.duration.toLong() - it.duration) <= 1
    }?.sourceId

    /** Length prefixes preserve line boundaries and arbitrary Unicode text without normalization. */
    internal fun digest(lines: List<LyricLine>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        lines.forEach { line ->
            val text = line.text.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(12).putLong(line.timeMs).putInt(text.size).array())
            hash.update(text)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun apply(track: Track, document: LyricsDocument): LyricsDocument {
        // This source uses another arrangement despite having nearly the same duration.
        if (track.id == "youtube:YKK5_OQiEa4" && abs(track.duration.toLong() - 304) <= 1 &&
            document.id in setOf(4663252L, 4663263L) &&
            digest(document.lines) == "0c57c093ef704d5580346b9888eec6d8762acbc1b7c570cb3da1173dfff64473") {
            return document.copy(synced = false, lines = document.lines.filter { it.text.isNotBlank() }.map { it.copy(timeMs = 0) })
        }
        val profile = profiles.firstOrNull { it.trackId == track.id &&
            abs(track.duration - it.duration) <= 1 && it.sourceId == document.id } ?: return document
        return apply(profile, track, document)
    }

    internal fun apply(profile: Profile, track: Track, document: LyricsDocument): LyricsDocument {
        if (!document.synced || profile.trackId != track.id || abs(track.duration - profile.duration) > 1 ||
            document.id != profile.sourceId || digest(document.lines) != profile.sourceDigest) return document
        if (profile.offsetMs == 0L && profile.lineOffsets.isEmpty()) return document
        val adjusted = document.lines.map { line ->
            line.copy(timeMs = (line.timeMs + (profile.lineOffsets[line.timeMs] ?: profile.offsetMs)).coerceAtLeast(0))
        }
        // A profile may never reorder lines or combine two independent lyric rows.
        if (adjusted.zipWithNext().any { (before, after) -> before.timeMs >= after.timeMs }) return document
        return document.copy(lines = adjusted)
    }
}
