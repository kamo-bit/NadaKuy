package app.nada.player.youtube

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nada.player.data.Track
import app.nada.player.playback.toMediaItem
import app.nada.player.playback.toTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeIdentityTest {
    @Test fun queueAndSavedTrackKeepSourceWithoutPersistingSignedMedia() {
        val track = Track("youtube:RYGgSRtYxd0", "Kangen", "Dewa 19", "", 300, "YouTube Music",
            "https://www.youtube.com/watch?v=RYGgSRtYxd0", "youtube")
        val saved = Track.fromSaved(track.toJson())
        val item = saved.toMediaItem()
        assertEquals(track, item.toTrack())
        assertEquals("nada-youtube", item.localConfiguration!!.uri.scheme)
        assertFalse(saved.toJson().toString().contains("googlevideo"))
        assertTrue(saved.playableInNada)
    }
    @Test fun spotifyAndMalformedVideoAreNotSentToLocalResolver() {
        val track = Track("spotify:track:abc", "Title", "Artist", "", 1, "", "", "spotify")
        assertFalse(track.playableInNada)
        assertFalse(track.copy(source = "youtube", id = "youtube:bad").playableInNada)
    }
}
