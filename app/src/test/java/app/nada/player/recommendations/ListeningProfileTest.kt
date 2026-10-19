package app.nada.player.recommendations

import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test

class ListeningProfileTest {
    private fun song(id: String, artist: String = "Singer", title: String = id, duration: Int = 180, source: String = "youtube") =
        Track(id, title, artist, "", duration, "", source = source)

    @Test fun pauseResumeDoesNotCountPausedTimeOrDuplicateAPlay() {
        val track = song("one")
        val counter = QualifiedListening()
        assertNull(counter.sample(track, true, 1000))
        assertNull(counter.sample(track, false, 11_000))
        assertNull(counter.sample(track, true, 111_000))
        assertNull(counter.sample(track, true, 130_999))
        assertEquals(track, counter.sample(track, true, 131_000))
        assertNull(counter.sample(track, true, 200_000))
    }

    @Test fun skipsAndMetadataChangesCannotBecomeAdditionalListensButReplayCan() {
        val counter = QualifiedListening()
        val first = song("first")
        val next = song("next")
        counter.sample(first, true, 0)
        assertNull(counter.sample(next, true, 5000))
        assertNull(counter.sample(next.copy(title = "Updated title"), true, 10_000))
        assertEquals(next.copy(title = "Updated title"), counter.sample(next, true, 35_000, newSession = true))
        assertNull(counter.sample(next, true, 64_999))
        assertEquals(next, counter.sample(next, true, 65_000))
    }

    @Test fun shortTracksRequireHalfTheirDurationAndIdleTimeDoesNotCount() {
        val counter = QualifiedListening()
        val track = song("short", duration = 10)
        counter.sample(track, false, 0)
        assertNull(counter.sample(track, true, 100_000))
        assertNull(counter.sample(track, true, 104_999))
        assertEquals(track, counter.sample(null, false, 105_000))
    }

    @Test fun favoritesUsePlayCountsAcrossSongsAndSeparateSources() {
        val frequent = song("frequent", "First - Topic")
        val recent = song("recent", "Second")
        val stats = listOf(ListeningStat(recent, 1, 999), ListeningStat(frequent, 8, 1), ListeningStat(song("spotify", "Third", source = "spotify"), 100, 1000))
        assertEquals(listOf(frequent), ListeningRecommendations.frequent(stats, "youtube"))
        assertEquals(listOf("First", "Second"), ListeningRecommendations.seeds(stats, emptyList(), "youtube").map { it.artist })
        assertEquals(listOf("Second"), ListeningRecommendations.seeds(emptyList(), listOf(recent), "youtube").map { it.artist })
        assertTrue(ListeningRecommendations.seeds(emptyList(), emptyList(), "youtube").isEmpty())
    }

    @Test fun suggestionsExcludeHeardDuplicateAndUnrelatedSongsAndInterleaveArtists() {
        val heard = song("heard", "Singer", "Old Song")
        val a = song("a", "Singer - Topic", "New Song")
        val b = song("b", "Other", "Other Song")
        val c = song("c", "Singer", "Another Song")
        val seeds = ListeningRecommendations.seeds(listOf(ListeningStat(heard, 5, 1), ListeningStat(b, 2, 1)), emptyList(), "youtube")
        val result = ListeningRecommendations.select(listOf(listOf(heard.copy(id = "reupload", title = "Old Song (Official Audio)"), a,
            a.copy(id = "duplicate"), c, song("wrong", "Unrelated")), listOf(b.copy(id = "new-b", title = "Fresh Song"))), seeds, listOf(heard), "youtube")
        assertEquals(listOf("a", "new-b", "c"), result.map { it.id })
    }
}
