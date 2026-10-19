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

    private fun result(id: Int, title: String, artist: String, duration: Int, synced: String?, plain: String?) = JsonParser.parseString("""
        {"id":$id,"trackName":"$title","artistName":"$artist","albumName":"Album $id","duration":$duration,"instrumental":false,
        "syncedLyrics":${synced?.let { "\"$it\"" } ?: "null"},"plainLyrics":${plain?.let { "\"$it\"" } ?: "null"}}
    """)

    @Test fun pickedRecordSkipsTitleAndArtistCheckButStillGuardsTimestampsWithDuration() {
        val picked = LyricsMatching.selected(result(7, "Our Song - Remastered", "Singer feat. Friend", 121, "[00:01]Our song", "Our song"), track)!!
        assertTrue(picked.document.chosen)
        assertTrue(picked.document.synced)
        val longer = LyricsMatching.selected(result(8, "Our Song", "Singer", 135, "[00:01]Our song", "Our song"), track)!!.document
        assertFalse(longer.synced)
        assertEquals(0L, longer.lines.single().timeMs)
        assertNull(LyricsMatching.choose(listOf(result(7, "Our Song - Remastered", "Singer feat. Friend", 121, "[00:01]Our song", "Our song")), track))
    }

    @Test fun pickedTimedOnlyRecordWithWrongDurationKeepsWordsWithoutTiming() {
        val document = LyricsMatching.selected(result(9, "Other", "Band", 300, "[00:01]First\\n[00:03]\\n[00:05]Second", null), track)!!.document
        assertFalse(document.synced)
        assertEquals(listOf("First", "Second"), document.lines.map { it.text })
        assertTrue(document.lines.all { it.timeMs == 0L })
        assertNull(LyricsMatching.choose(listOf(result(9, "Our Song", "Singer", 300, "[00:01]First", null)), track))
    }

    @Test fun browserCandidatesPutUsableTimestampsFirstThenClosestDurationAndDropDuplicates() {
        val results = LyricsMatching.candidates(listOf(
            result(1, "A", "X", 150, null, "plain"),
            result(2, "B", "X", 160, "[00:01]timed", "timed"),
            result(3, "C", "X", 123, "[00:01]timed", "timed"),
            result(4, "D", "X", 120, "[00:01]timed", "timed"),
            result(4, "D", "X", 120, "[00:01]timed", "timed"),
            JsonParser.parseString("{\"id\":5}"),
        ), track)
        assertEquals(listOf(4L, 3L, 1L, 2L), results.map { it.document.id })
        assertTrue(results[0].document.synced)
        assertEquals(0.0, results[0].durationDifference!!, 0.0)
        assertTrue(results.last().timedAvailable)
        assertFalse(results.last().document.synced)
        assertEquals("Album 4", results[0].album)
        assertNull(LyricsMatching.candidates(listOf(result(1, "A", "X", 150, null, "plain")), track.copy(duration = 0)).single().durationDifference)
    }

    @Test fun browserQueryUsesCleanTitleAndArtist() {
        assertEquals("Our Song Singer", LyricsMatching.query(track))
    }
}
