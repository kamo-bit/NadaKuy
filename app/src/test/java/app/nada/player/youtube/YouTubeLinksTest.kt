package app.nada.player.youtube

import org.junit.Assert.*
import org.junit.Test

class YouTubeLinksTest {
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
