package app.nada.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.youtube.YouTubeCatalog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in real network smoke test; ordinary regression runs do not consume external services. */
@RunWith(AndroidJUnit4::class)
class YouTubeLiveTest {
    @Test fun publicMusicSearchAndAudioWithoutCredentials() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveYouTube") == "true")
        val songs = YouTubeCatalog.search("Dewa 19 Kangen")
        assertTrue("YouTube Music returned no playable songs", songs.isNotEmpty())
        assertTrue(songs.all { it.source == "youtube" && it.playableInNada })
        songs.take(5).forEach { android.util.Log.i("NadaYouTubeTest", "Song: ${it.id} | ${it.title} | ${it.artist}") }
        val selected = songs.first()
        val audio = YouTubeCatalog.resolve(selected.id.removePrefix("youtube:"))
        val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(audio.url).header("Range", "bytes=0-65535").build()).execute().use { response ->
            assertTrue("Media HTTP status ${response.code}", response.code in listOf(200, 206))
            val data = response.body!!.source().readByteArray(4096)
            assertTrue("Media response was empty", data.isNotEmpty())
            android.util.Log.i("NadaYouTubeTest", "Media bytes=${data.size}; HTTP=${response.code}; type=${response.header("Content-Type")}")
        }
        val videos = YouTubeCatalog.search("Dewa 19 Kangen official", videos = true)
        assertTrue("YouTube video search returned no videos", videos.isNotEmpty())
        videos.take(3).forEach { android.util.Log.i("NadaYouTubeTest", "Video: ${it.id} | ${it.title} | ${it.artist}") }
    }
}
