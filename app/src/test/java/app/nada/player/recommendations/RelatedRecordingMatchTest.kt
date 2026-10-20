package app.nada.player.recommendations

import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test

class RelatedRecordingMatchTest {
    private fun song(id: String, title: String = "One More Night", artist: String = "Maroon 5") = Track(id, title, artist, "", 220, "YouTube Music", source = "youtube")
    @Test fun aPerformerNamedInACoverTitleCannotReplaceTheActualArtist() {
        val original = song("original")
        val cover = song("cover", "(Maroon5) One More Night - Sungha Jung", "Sungha Jung")
        assertEquals(original, RelatedRecordingMatch(original).select(listOf(cover, original)))
        assertNull(RelatedRecordingMatch(original).select(listOf(cover)))
    }
    @Test fun aLabelUploadCanResolveTheArtistCreditedInItsTitle() {
        val hint = song("video", "The Rock - Munajat Cinta (Official Music Video)", "GP Records")
        val match = song("song", "Munajat Cinta", "The Rock")
        val matching = RelatedRecordingMatch(hint)
        assertFalse(matching.query.contains("GP Records"))
        assertEquals(match, matching.select(listOf(match)))
        assertNull(matching.select(listOf(match.copy(duration = 500))))
    }
    @Test fun tributeBackingAndReinterpretationsAreExcludedBeforeMatchingIdentity() {
        val hint = song("original", "Rude", "MAGIC!")
        val alternative = song("other", "Rude (Bossa Nova Version) [Originally Performed by MAGIC!]", "MAGIC!")
        assertNull(RelatedRecordingMatch(hint).select(listOf(alternative)))
        assertFalse(app.nada.player.youtube.YouTubeSearchRanking.originalPreferred("Hey, Soul Sister (made popular by Train) [backing version]"))
    }
}
