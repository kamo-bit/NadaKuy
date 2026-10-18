package app.nada.player.spotify

import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.data.Track
import app.nada.player.playback.toMediaItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import java.util.UUID

class SpotifyIntegrationTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDirectory = File(base.cacheDir, "spotify-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val isolatedContext: Context = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = testDirectory }
    @After fun cleanUp() { testDirectory.deleteRecursively() }
    @Test fun pkceMatchesPublishedRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SpotifyConfig.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertNotEquals(SpotifyConfig.randomToken(), SpotifyConfig.randomToken())
    }
    @Test fun tokensRoundTripEncryptedOutsideBackup() {
        val store = SpotifyTokenStore(isolatedContext)
        try {
            store.write(JSONObject().put("access_token", "test-access-token").put("refresh_token", "test-refresh-token"))
            assertEquals("test-access-token", store.read()?.getString("access_token"))
            val text = File(isolatedContext.noBackupFilesDir, "spotify-session.enc").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(text.contains("test-access-token")); assertFalse(text.contains("test-refresh-token"))
            store.write(JSONObject().put("access_token", "replacement"))
            assertEquals("replacement", store.read()?.getString("access_token"))
            store.clear(); assertNull(store.read())
        } finally { store.clear() }
    }
    @Test fun malformedEncryptedSessionRequiresReauthorization() {
        val store = SpotifyTokenStore(isolatedContext)
        File(isolatedContext.noBackupFilesDir, "spotify-session.enc").writeText("not-an-encrypted-session")
        assertNull(store.read())
    }
    @Test fun providerIdentitySurvivesSavedLibraryAndLegacyTracks() {
        val spotify = Track("spotify:track:1234567890123456789012", "Official title", "Official artist", "", 200, "", "https://open.spotify.com/track/1234567890123456789012", "spotify")
        assertEquals(spotify, Track.fromSaved(spotify.toJson()))
        val legacy = spotify.toJson().apply { remove("source"); put("id", "AudiusId") }
        assertEquals("audius", Track.fromSaved(legacy).source)
        assertThrows(IllegalStateException::class.java) { spotify.toMediaItem() }
    }
    @Test fun catalogRetainsArtistsAndSkipsUnavailableRecordings() {
        val official = JSONObject().put("id", "1234567890123456789012").put("type", "track").put("name", "Original recording").put("duration_ms", 205000)
            .put("artists", JSONArray().put(JSONObject().put("name", "Artist A")).put(JSONObject().put("name", "Artist B")))
            .put("album", JSONObject().put("images", JSONArray().put(JSONObject().put("url", "https://i.scdn.co/image/example"))))
        val unavailable = JSONObject(official.toString()).put("is_playable", false)
        val episode = JSONObject(official.toString()).put("type", "episode")
        val parsed = SpotifyTracks.parse(JSONArray().put(official).put(unavailable).put(episode).put(JSONObject()))
        assertEquals(1, parsed.size); assertEquals("Artist A, Artist B", parsed.single().artist)
        assertEquals(205, parsed.single().duration); assertEquals("spotify", parsed.single().source)
    }
}
