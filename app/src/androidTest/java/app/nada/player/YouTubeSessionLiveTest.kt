package app.nada.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.nada.player.data.Track
import app.nada.player.playback.PlaybackService
import app.nada.player.playback.SleepCommands
import app.nada.player.playback.toMediaItem
import app.nada.player.youtube.YouTubeCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in real service journey. No synthetic media or alternate player configuration. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class YouTubeSessionLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var controller: MediaController
    private val evidence: File get() = File(context.cacheDir, "youtube-session-live").apply { mkdirs() }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun log(message: String) = android.util.Log.i("NadaSessionTest", message)
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= 2_000_000) { "Diagnostic output exceeded bound" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }

    private fun await(description: String, timeout: Long = 45_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            main {
                assertNull("Player error during $description: ${controller.playerError?.errorCodeName}", controller.playerError)
                done = condition()
            }
            if (done) return
            Thread.sleep(200)
        }
        fail("Timed out: $description")
    }

    private fun snapshot(stage: String) {
        val dump = shell("dumpsys media.audio_flinger")
        var header = ""
        val ownRows = mutableListOf<String>()
        val counters = mutableListOf<Long>()
        for (line in dump.lineSequence()) {
            if (line.contains("Client") && line.contains("Session") && line.contains("Underruns") && line.contains("Flushed")) {
                header = line
                continue
            }
            if (header.isEmpty()) continue
            val client = header.indexOf("Client")
            val session = header.indexOf("Session")
            val underruns = header.indexOf("Underruns")
            val flushed = header.indexOf("Flushed")
            if (line.length < flushed || line.substring(client, session).trim() != Process.myPid().toString()) continue
            val count = line.substring(underruns, flushed).trim().toLongOrNull()
                ?: error("Unknown AudioFlinger underrun representation")
            ownRows += line
            counters += count
        }
        assertTrue("No owned AudioTrack at $stage", ownRows.isNotEmpty())
        assertTrue("Owned AudioTrack underruns at $stage: $counters", counters.all { it == 0L })
        val selected = dump.lineSequence().filter { it.contains("Normal mixer raw underrun counters") }.toList()
        File(evidence, "$stage-audio.txt").writeText((selected + header + ownRows).joinToString("\n"))
        main {
            log("Stage=$stage; index=${controller.currentMediaItemIndex}; id=${controller.currentMediaItem?.mediaId}; position=${controller.currentPosition}; playing=${controller.isPlaying}; underruns=$counters")
        }
    }

    private fun sample(stage: String, index: Int, seconds: Int = 15) {
        val start = SystemClock.elapsedRealtime()
        await("$stage starts") { controller.currentMediaItemIndex == index && controller.isPlaying }
        val readyMs = SystemClock.elapsedRealtime() - start
        var initial = 0L
        main { initial = controller.currentPosition }
        await("$stage advances ${seconds}s", seconds * 1000L + 20_000) {
            controller.currentMediaItemIndex == index && controller.isPlaying && controller.currentPosition >= initial + seconds * 1000L
        }
        log("Ready=$stage; waitMs=$readyMs; sampledSeconds=$seconds")
        snapshot(stage)
        val id = mainId().removePrefix("youtube:")
        val audio = YouTubeCatalog.resolve(id)
        assertTrue("AAC selection for $stage", audio.codec?.startsWith("mp4a.40.2") == true)
        log("Audio=$stage; codec=${audio.codec}; bitrate=${audio.bitrate}")
    }

    private fun mainId(): String {
        var id = ""
        main { id = controller.currentMediaItem!!.mediaId }
        return id
    }

    @Test fun realQueueTransitionsAndHomeBackground(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSession") == "true")
        assertEquals("Choose YouTube in Nada before this opt-in journey", "youtube",
            context.getSharedPreferences("spotify_connection", Context.MODE_PRIVATE).getString("source", "youtube"))
        val requests = listOf(
            Triple("Coldplay Yellow", "Coldplay", "Yellow"),
            Triple("Adele Hello", "Adele", "Hello"),
            Triple("NOAH Separuh Aku", "NOAH", "Separuh Aku"),
            Triple("Bruno Mars When I Was Your Man", "Bruno Mars", "When I Was Your Man"),
            Triple("Sheila On 7 Hingga Ujung Waktu", "Sheila On 7", "Hingga Ujung Waktu"),
        )
        val tracks = requests.map { (query, artist, title) ->
            YouTubeCatalog.search(query).firstOrNull { it.artist.contains(artist, true) && it.title.contains(title, true) }
                ?: error("No requested artist/title from live catalog: $query")
        }
        tracks.forEachIndexed { index, track -> log("Queue=$index; ${track.id} | ${track.title} | ${track.artist}") }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        var connected = false
        try {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            controller = future.get(10, TimeUnit.SECONDS)
            connected = true
            lateinit var timer: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
            main { timer = controller.sendCustomCommand(SleepCommands.set, Bundle().apply { putString(SleepCommands.MODE, "off") }) }
            assertEquals(0, timer.get(10, TimeUnit.SECONDS).resultCode)
            main {
                controller.stop(); controller.clearMediaItems()
                controller.setMediaItems(tracks.map { it.toMediaItem() })
                controller.repeatMode = Player.REPEAT_MODE_OFF
                controller.shuffleModeEnabled = false
                controller.prepare(); controller.play()
            }
            sample("foreground-first", 0)
            main { controller.seekToNextMediaItem() }
            sample("foreground-next-adele", 1)
            main { controller.seekToNextMediaItem() }
            sample("foreground-next-noah", 2)
            main {
                assertTrue(controller.duration > 30_000)
                controller.seekTo(controller.duration - 5_000)
            }
            sample("automatic-end-to-bruno", 3)
            main { controller.seekToNextMediaItem() }
            sample("foreground-next-sheila", 4)
            main { controller.seekToPreviousMediaItem() }
            sample("foreground-previous-bruno", 3, 8)

            shell("input keyevent 3")
            await("Activity stopped after Home", 10_000) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isEmpty()
            }
            val top = shell("dumpsys activity activities").lineSequence().firstOrNull { it.contains("topResumedActivity=") }.orEmpty()
            assertTrue("No resumed system activity evidence", top.isNotBlank())
            assertFalse("Nada still foreground", top.contains("app.nada.player"))
            File(evidence, "background-activity.txt").writeText(top)
            val service = shell("dumpsys activity services app.nada.player")
            assertTrue("PlaybackService not foreground", service.contains("isForeground=true"))
            File(evidence, "background-service.txt").writeText(service)
            log("Background confirmed: $top")
            sample("background-steady-bruno", 3, 25)
            main { controller.seekToNextMediaItem() }
            sample("background-next-sheila", 4, 20)
            main { controller.seekToPreviousMediaItem() }
            sample("background-previous-bruno", 3, 12)
            main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            await("Nada resumes", 10_000) { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity } }
            sample("returned-to-nada-bruno", 3, 8)
            main { assertEquals(tracks.size, controller.mediaItemCount) }
            log("PASS: five songs, manual next/previous, automatic boundary, Home background, background transitions, return; no owned AudioTrack underruns")
        } finally {
            if (connected) main { controller.pause(); controller.stop(); controller.clearMediaItems(); controller.release() }
            activity.close()
        }
    }
}
