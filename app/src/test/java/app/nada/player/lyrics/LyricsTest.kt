package app.nada.player.lyrics

import app.nada.player.data.Track
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun parsesMultipleTimestampsFractionsOffsetsAndEnhancedTags() {
        val result = LrcParser.parse("[ar:Test]\n[offset:+500]\n[00:02.5][00:04.250]<00:02.5>Hello\n[00:06:125]World\n[00:07.00]")
        assertEquals(listOf(2000L, 3750L, 5625L, 6500L), result.map { it.timeMs })
        assertEquals(listOf("Hello", "Hello", "World", ""), result.map { it.text })
    }

    @Test fun combinesSameTimeTranslationsAndSortsOutOfOrderLines() {
        val result = LrcParser.parse("[00:03]Third\n[00:01]First\n[00:01]Translation\n[00:01]First\n[99:99]Invalid")
        assertEquals(2, result.size)
        assertEquals("First\nTranslation", result.first().text)
        assertEquals(1000L, result.first().timeMs)
    }

    @Test fun activeCursorFollowsSeekInBothDirectionsAndHandlesIntroAndEnd() {
        val lines = listOf(LyricLine(1000, "One"), LyricLine(2000, "Two"), LyricLine(8000, "Three"))
        assertEquals(-1, activeLyricIndex(lines, 999))
        assertEquals(0, activeLyricIndex(lines, 1000))
        assertEquals(2, activeLyricIndex(lines, 9000))
        assertEquals(1, activeLyricIndex(lines, 2500))
        assertEquals(-1, activeLyricIndex(emptyList(), 1000))
    }

    @Test fun boundsLargeInputAndRejectsInvalidTimeTags() {
        assertTrue(LrcParser.parse("[00:99]No\n[not time]No").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { LrcParser.parse("x".repeat(128_001)) }
        assertThrows(IllegalArgumentException::class.java) { LrcParser.parse((1..3001).joinToString("\n") { "[00:01]$it" }) }
    }

    private fun record(id: Int, artist: String = "Singer", duration: Int = 120) = JsonParser.parseString("""
        {"id":$id,"trackName":"Our Song","artistName":"$artist","duration":$duration,
        "instrumental":false,"syncedLyrics":"[00:01]Our song","plainLyrics":"Our song"}
    """)
    private val track = Track("song42", "Our Song (Official Audio)", "Singer - Topic", "", 120, "Pop")

    @Test fun choosesMatchingArtistAndRecordingDurationInsteadOfFirstSearchResult() {
        val selected = record(3)
        val match = LyricsMatching.choose(listOf(JsonParser.parseString("null"), JsonParser.parseString("{\"id\":3}"), record(1, "Cover Artist"), record(2, duration = 240), selected), track)!!
        assertSame(selected, match.source)
        val result = match.document
        assertEquals(3L, result!!.id)
        assertTrue(result.synced)
    }

    @Test fun wrongDurationOrUnknownDurationUsesPlainLyricsWithoutInventingTiming() {
        val wrong = LyricsMatching.choose(listOf(record(2, duration = 240)), track)!!.document
        assertFalse(wrong.synced)
        val unknown = LyricsMatching.choose(listOf(record(3)), track.copy(duration = 0))!!.document
        assertFalse(unknown.synced)
        assertEquals(0L, unknown.lines.first().timeMs)
        assertNull(LyricsMatching.choose(listOf(record(1, "Another Singer")), track))
    }
}
