package app.nada.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Process
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import app.nada.player.data.Track
import app.nada.player.youtube.YouTubeCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt in twice with an external force-stop between stages. Leaves a real, paused queue for manual QA. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class QueueResumeLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(label: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < until) {
            var done = false; main { done = condition() }; if (done) return; Thread.sleep(100)
        }
        fail("Timed out: $label")
    }
    private fun identity(controller: MediaController) = JSONObject().apply {
        put("pid", Process.myPid()); put("index", controller.currentMediaItemIndex)
        put("position", controller.currentPosition); put("shuffle", controller.shuffleModeEnabled); put("repeat", controller.repeatMode)
        put("order", org.json.JSONArray(controller.upcomingQueue().map { it.entryId }))
        put("tracks", org.json.JSONArray(controller.upcomingQueue().map { it.track.id }))
        put("radio", controller.sessionExtras.getString(RadioCommands.TITLE))
    }
    @Test fun resumeAcrossARealProcessRestart() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("queueResume")
        assumeTrue(stage in listOf("seed", "verify"))
        assertEquals("Select YouTube first", "youtube", context.getSharedPreferences("spotify_connection", Context.MODE_PRIVATE).getString("source", "youtube"))
        val expectedFile = File(context.cacheDir, "queue-resume-expected.json")
        val activity = ActivityScenario.launch(MainActivity::class.java)
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            if (stage == "seed") {
                val requests = listOf(Triple("Dewa 19 Kangen", "Dewa", "Kangen"), Triple("Sheila On 7 Dan", "Sheila", "Dan"),
                    Triple("NOAH Separuh Aku", "NOAH", "Separuh"), Triple("Bruno Mars The Lazy Song", "Bruno", "Lazy"))
                val tracks = requests.map { (query, artist, title) -> YouTubeCatalog.search(query).firstOrNull {
                    it.artist.contains(artist, true) && it.title.contains(title, true)
                } ?: error("Original recording not found: $query") }
                main {
                    controller.pause(); controller.repeatMode = Player.REPEAT_MODE_ALL
                    controller.setMediaItems((tracks + tracks.first()).map { it.toMediaItem("resume-live") }, 1, 0)
                    controller.shuffleModeEnabled = true; controller.prepare(); controller.play()
                }
                await("real song decodes") { controller.isPlaying && controller.currentPosition > 2500 }
                main { controller.seekTo(42_000) }
                await("real seek") { controller.isPlaying && controller.currentPosition >= 42_000 }
                main { controller.pause() }
                await("paused seek checkpoint") { !controller.playWhenReady && controller.currentPosition in 42_000..50_000 }
                Thread.sleep(1000)
                main { expectedFile.writeText(identity(controller).toString(2)) }
            } else {
                val expected = JSONObject(expectedFile.readText())
                await("saved queue returns") { controller.mediaItemCount == 5 }
                main {
                    val restored = identity(controller)
                    assertNotEquals("The host must force-stop between stages", expected.getInt("pid"), Process.myPid())
                    assertFalse(controller.playWhenReady); assertFalse(controller.isPlaying)
                    assertEquals(expected.getInt("index"), controller.currentMediaItemIndex)
                    assertEquals(expected.getLong("position"), controller.currentPosition)
                    assertEquals(expected.getBoolean("shuffle"), controller.shuffleModeEnabled)
                    assertEquals(expected.getInt("repeat"), controller.repeatMode)
                    assertEquals(expected.getJSONArray("order").toString(), restored.getJSONArray("order").toString())
                    assertEquals(expected.getJSONArray("tracks").toString(), restored.getJSONArray("tracks").toString())
                    File(context.cacheDir, "queue-resume-restored.json").writeText(restored.toString(2))
                    controller.togglePlayback()
                }
                await("restored stream resolves and plays") { controller.isPlaying && controller.currentPosition > expected.getLong("position") + 3000 }
                main { controller.seekToNextMediaItem() }
                await("next real recording plays") { controller.isPlaying && controller.currentMediaItemIndex != expected.getInt("index") && controller.currentPosition > 2000 }
                main { controller.pause() }
            }
        } finally { main { controller.release() }; activity.close() }
    }
}
