package app.nada.player.spotify

import org.junit.Assert.*
import org.junit.Test

class SpotifyCallbackTest {
    private val state = "only-this-pending-attempt"
    @Test fun acceptsOnlyCorrelatedAuthorizationResponse() {
        assertEquals("spotify-code", SpotifyCallback.parse("GET /callback?code=spotify-code&state=$state HTTP/1.1", state)?.code)
    }
    @Test fun rejectsResponseForAnotherAttempt() {
        assertNull(SpotifyCallback.parse("GET /callback?code=stolen&state=another-attempt HTTP/1.1", state))
        assertNull(SpotifyCallback.parse("GET /callback?code=stolen HTTP/1.1", state))
    }
    @Test fun rejectsAmbiguousDuplicateParameters() {
        assertNull(SpotifyCallback.parse("GET /callback?code=one&code=two&state=$state HTTP/1.1", state))
        assertNull(SpotifyCallback.parse("GET /callback?code=one&state=other&state=$state HTTP/1.1", state))
    }
    @Test fun denialIsExplicitAndDoesNotBecomeAToken() {
        val denied = SpotifyCallback.parse("GET /callback?error=access_denied&state=$state HTTP/1.1", state)
        assertEquals("access_denied", denied?.error)
        assertNull(denied?.code)
        assertNull(SpotifyCallback.parse("GET /callback?error=access_denied&code=one&state=$state HTTP/1.1", state))
    }
    @Test fun rejectsUnexpectedDestinationsAndMethods() {
        for (path in listOf("/favicon.ico", "/callback/", "//foreign.example/callback", "http://foreign.example/callback")) {
            assertNull(SpotifyCallback.parse("GET $path?code=one&state=$state HTTP/1.1", state))
        }
        assertNull(SpotifyCallback.parse("POST /callback?code=one&state=$state HTTP/1.1", state))
    }
    @Test fun rejectsMalformedOrOversizeResponses() {
        assertNull(SpotifyCallback.parse("GET /callback?code=&state=$state HTTP/1.1", state))
        assertNull(SpotifyCallback.parse("GET /callback?code=%XX&state=$state HTTP/1.1", state))
        assertNull(SpotifyCallback.parse("GET /callback?code=${"a".repeat(8192)}&state=$state HTTP/1.1", state))
        assertNull(SpotifyCallback.parse("GET /callback?code=one&state=$state#fragment HTTP/1.1", state))
    }
}
