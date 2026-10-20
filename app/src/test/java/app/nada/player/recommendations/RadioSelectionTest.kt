package app.nada.player.recommendations

import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test

class RadioSelectionTest {
    private fun song(id: String, artist: String = "Singer", title: String = id) = Track(id, title, artist, "", 180, "Pop")
    @Test fun feedbackFiltersSeedsAndSavedPoolsWithoutChangingTheInputCollection() {
        val disliked = song("one")
        val blocked = song("two", "Other - Topic")
        val allowed = song("three")
        val preferences = RecommendationPreferences(listOf(disliked), mapOf("other" to "Other"))
        val collection = listOf(disliked, blocked, allowed)
        val seeds = ListeningRecommendations.seeds(emptyList(), collection, "audius", preferences)
        assertEquals(listOf("Singer"), seeds.map { it.artist })
        assertEquals(listOf(allowed), ListeningRecommendations.select(listOf(collection), seeds, emptyList(), "audius", preferences = preferences))
        assertEquals(3, collection.size)
    }
    @Test fun dislikeMatchesAnotherUploadAndArtistBlockMatchesTopicAndVevoNames() {
        val track = song("one", "Singer - Topic", "Song (Official Audio)")
        val preferences = RecommendationPreferences(listOf(track), mapOf("other" to "Other"))
        assertFalse(preferences.allows(song("another", "Singer", "Song")))
        assertFalse(preferences.allows(song("two", "OtherVEVO")))
        assertTrue(preferences.allows(song("three", "Singer", "New Song")))
    }
    @Test fun radioAcceptsOtherArtistsButExcludesQueueDuplicatesBlockedTracksAndWrongSource() {
        val seed = song("seed")
        val heard = song("heard", title = "Old Song")
        val next = song("next", "Other", "New Song")
        val candidates = listOf(seed, heard.copy(id = "reupload"), next, next.copy(id = "duplicate"),
            song("blocked", "Blocked"), song("long").copy(duration = 5000), song("other").copy(source = "spotify"))
        assertEquals(listOf(next), RadioSelection.select(candidates, seed, listOf(seed, heard), RecommendationPreferences(blockedArtists = mapOf("blocked" to "Blocked"))))
    }
    @Test fun radioBatchesAreBoundedAndRemainInCatalogOrder() {
        val tracks = (0 until 30).map { song("id$it") }
        assertEquals(tracks.take(6), RadioSelection.select(tracks, song("seed"), emptyList(), RecommendationPreferences()))
        assertEquals(tracks.drop(6).take(6), RadioSelection.select(tracks, song("seed"), tracks.take(6), RecommendationPreferences()))
    }
}
