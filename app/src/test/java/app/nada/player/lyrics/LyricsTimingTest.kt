package app.nada.player.lyrics

import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test

class LyricsTimingTest {
    private val track = Track("recording", "Song", "Artist", "", 120, "")
    private val source = LyricsDocument(42, "Song", "Artist", 120.0, false,
        listOf(LyricLine(1000, "First"), LyricLine(4000, ""), LyricLine(8000, "Last", "Reading")), true)
    private fun profile(offset: Long = 500, overrides: Map<Long, Long> = emptyMap()) =
        LyricsTiming.Profile(track.id, 120, source.id, LyricsTiming.digest(source.lines), offset, overrides)

    @Test fun correctedTimesDriveCursorAndLineSeekWithoutChangingTextOrInterludes() {
        val result = LyricsTiming.apply(profile(overrides = mapOf(1000L to 800L)), track, source)
        assertEquals(listOf(1800L, 4500L, 8500L), result.lines.map { it.timeMs })
        assertEquals(source.lines.map { it.text to it.romaji }, result.lines.map { it.text to it.romaji })
        assertEquals(0, activeLyricIndex(result.lines, 4499))
        assertEquals(1, activeLyricIndex(result.lines, 4500))
        assertEquals(2, activeLyricIndex(result.lines, result.lines.last().timeMs))
        assertEquals(0, activeLyricIndex(result.lines, 2000))
        assertEquals(1000L, source.lines.first().timeMs)
    }

    @Test fun changedRecordingSourceDurationOrSourceContentInvalidatesCorrection() {
        val calibration = profile()
        assertSame(source, LyricsTiming.apply(calibration, track.copy(id = "another recording"), source))
        assertSame(source, LyricsTiming.apply(calibration, track.copy(duration = 130), source))
        val anotherSource = source.copy(id = 43)
        assertSame(anotherSource, LyricsTiming.apply(calibration, track, anotherSource))
        val revised = source.copy(lines = source.lines.map { it.copy(timeMs = it.timeMs + 100) })
        assertSame(revised, LyricsTiming.apply(calibration, track, revised))
        val differentText = source.copy(lines = source.lines.map { it.copy(text = it.text + "!") })
        assertSame(differentText, LyricsTiming.apply(calibration, track, differentText))
        val plain = source.copy(synced = false)
        assertSame(plain, LyricsTiming.apply(calibration, track, plain))
    }

    @Test fun repeatedPreparationNeverAccumulatesOffsetAndRawCacheCanBeReused() {
        val calibration = profile()
        val prepared = LyricsTiming.apply(calibration, track, source)
        assertSame(prepared, LyricsTiming.apply(calibration, track, prepared))
        assertEquals(prepared, LyricsTiming.apply(calibration, track, source))
    }

    @Test fun advancingLyricsCannotCrossRowsOrCreateNegativeTimes() {
        val advanced = LyricsTiming.apply(profile(-500), track, source)
        assertEquals(listOf(500L, 3500L, 7500L), advanced.lines.map { it.timeMs })
        assertSame(source, LyricsTiming.apply(profile(overrides = mapOf(1000L to 6000L)), track, source))
        val zero = LyricsTiming.apply(profile(-1500), track, source)
        assertEquals(0L, zero.lines.first().timeMs)
    }

    @Test fun fingerprintsPreserveBoundariesAndIgnoreOnlyDerivedReading() {
        assertEquals(LyricsTiming.digest(source.lines), LyricsTiming.digest(source.lines.map { it.copy(romaji = "Other") }))
        assertNotEquals(LyricsTiming.digest(listOf(LyricLine(0, "a\nb"))),
            LyricsTiming.digest(listOf(LyricLine(0, "a"), LyricLine(0, "b"))))
        assertNull(LyricsTiming.preferredSourceId(track))
        assertEquals(21914365L, LyricsTiming.preferredSourceId(track.copy(id = "youtube:YKK5_OQiEa4", duration = 304)))
    }
}
