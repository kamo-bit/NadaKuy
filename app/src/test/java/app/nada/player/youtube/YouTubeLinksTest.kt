package app.nada.player.youtube

import org.junit.Assert.*
import org.junit.Test

class YouTubeLinksTest {
    @Test fun playlistLinksResolveToOneCanonicalPublicRequest() {
        val id = "PL12345678901234567890123456789012"
        val expected = "https://www.youtube.com/playlist?list=$id"
        listOf(expected, "  https://music.youtube.com/playlist?list=$id&si=share  ",
            "https://m.youtube.com/watch?v=RYGgSRtYxd0&list=$id&index=4", "https://youtu.be/RYGgSRtYxd0?list=$id"
        ).forEach { assertEquals(it, expected, YouTubeLinks.playlistUrl(it)) }
    }

    @Test fun playlistLinksRejectPrivateShortcutsAndUntrustedTargets() {
        val id = "PL12345678901234567890123456789012"
        listOf("https://www.youtube.com/playlist?list=WL", "https://www.youtube.com/watch?v=RYGgSRtYxd0",
            "https://www.youtube.com/playlist?list=$id&list=$id", "https://youtube.com.example.org/playlist?list=$id",
            "https://user@youtube.com/playlist?list=$id", "https://youtube.com:8443/playlist?list=$id",
            "https://www.youtube.com/redirect?list=$id", "https://www.youtube.com/playlist?list=$id%26v=RYGgSRtYxd0"
        ).forEach { assertNull(it, YouTubeLinks.playlistUrl(it)) }
    }

    @Test fun preservesSameVideoAcrossPublicLinkForms() {
        val id = "RYGgSRtYxd0"
        listOf("youtube:$id", "https://youtu.be/$id?si=share", "https://music.youtube.com/watch?v=$id&list=album",
            "https://www.youtube.com/shorts/$id", "https://m.youtube.com/watch?v=$id").forEach {
            assertEquals(it, id, YouTubeLinks.videoId(it))
        }
    }
    @Test fun rejectsAmbiguousAndForeignTargets() {
        listOf("https://youtube.com.evil.example/watch?v=RYGgSRtYxd0", "https://www.youtube.com/watch?v=RYGgSRtYxd0&v=YKK5_OQiEa4",
            "https://user@youtube.com/watch?v=RYGgSRtYxd0", "file:///watch?v=RYGgSRtYxd0",
            "https://youtube.com:8443/watch?v=RYGgSRtYxd0", "https://youtube.com/watch?v=short").forEach {
            assertNull(it, YouTubeLinks.videoId(it))
        }
    }
    @Test fun artworkSizeChangesOnlyKnownGoogleImageFormat() {
        assertEquals("https://yt3.googleusercontent.com/cover=w544-h544-l90-rj", YouTubeLinks.artwork("https://yt3.googleusercontent.com/cover=w120-h120-l90-rj"))
        val large = "https://lh3.googleusercontent.com/cover=w1200-h1200-l90-rj"
        assertEquals(large, YouTubeLinks.artwork(large))
        val foreign = "https://images.example/cover=w120-h120-l90-rj"
        assertEquals(foreign, YouTubeLinks.artwork(foreign))
    }
}
