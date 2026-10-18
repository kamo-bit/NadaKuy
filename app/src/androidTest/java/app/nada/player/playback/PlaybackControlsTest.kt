package app.nada.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.data.Track
import app.nada.player.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackControlsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: MediaController
    private lateinit var audio: File
    private lateinit var activity: ActivityScenario<MainActivity>
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(description: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            var satisfied = false
            main { satisfied = condition() }
            if (satisfied) return
            Thread.sleep(50)
        }
        fail("Timed out: $description")
    }

    @Before fun connect() {
        // Generated silence: deterministic playback completion without catalog/network dependencies.
        audio = File(context.cacheDir, "playback-controls.wav")
        val size = 16_000 * 2 * 2
        val buffer = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        audio.writeBytes(buffer.array())
        // Android 15 grants audio focus when the app is visible or already foreground.
        // Exercise the real user launch path rather than starting a background-only test service.
        activity = ActivityScenario.launch(MainActivity::class.java)
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        controller = future.get(10, TimeUnit.SECONDS)
        main { controller.stop(); controller.clearMediaItems(); controller.repeatMode = Player.REPEAT_MODE_OFF; controller.shuffleModeEnabled = false }
    }

    @After fun disconnect() {
        timer("off")
        main { controller.stop(); controller.clearMediaItems(); controller.release() }
        activity.close()
        audio.delete()
    }

    private fun timer(mode: String, duration: Long = 0) {
        lateinit var result: com.google.common.util.concurrent.ListenableFuture<SessionResult>
        main { result = controller.sendCustomCommand(SleepCommands.set, Bundle().apply { putString(SleepCommands.MODE, mode); putLong(SleepCommands.DURATION, duration) }) }
        assertEquals(SessionResult.RESULT_SUCCESS, result.get(10, TimeUnit.SECONDS).resultCode)
    }

    @Test fun sleepTimerPausesAndClearsAtDeadline() {
        main {
            controller.setMediaItem(Track("local2", "Silence", "Test", "", 2, "").toMediaItem().buildUpon().setUri(audio.toUri()).build())
            controller.prepare(); controller.play()
        }
        await("audio starts") { controller.isPlaying }
        timer("duration", 1000)
        await("timer state reaches controller") { controller.sessionExtras.getLong(SleepCommands.DEADLINE) > 0 }
        await("timer pauses and clears") { !controller.playWhenReady && controller.sessionExtras.getLong(SleepCommands.DEADLINE) == 0L }
        main { assertTrue(controller.currentPosition < 1900) }
    }

    @Test fun sleepTimerAtTrackEndDoesNotPlayTheNextTrack() {
        main {
            controller.setMediaItems((0..1).map { Track("end$it", "Silence", "Test", "", 2, "").toMediaItem().buildUpon().setUri(audio.toUri()).build() })
            controller.prepare(); controller.play()
        }
        await("first song starts") { controller.isPlaying }
        timer("end")
        await("end-of-track mode reaches controller") { controller.sessionExtras.getBoolean(SleepCommands.END_OF_TRACK) }
        await("song ending pauses and clears timer") { !controller.playWhenReady && !controller.sessionExtras.getBoolean(SleepCommands.END_OF_TRACK) }
        main { assertFalse(controller.isPlaying) }
    }

    @Test fun cancelledTimerDoesNotInterruptPlayback() {
        main {
            controller.setMediaItem(Track("local3", "Silence", "Test", "", 2, "").toMediaItem().buildUpon().setUri(audio.toUri()).build())
            controller.repeatMode = Player.REPEAT_MODE_ONE
            controller.prepare(); controller.play()
        }
        await("audio starts") { controller.isPlaying }
        timer("duration", 1000)
        timer("off")
        Thread.sleep(1200)
        main { assertTrue(controller.isPlaying); assertEquals(0L, controller.sessionExtras.getLong(SleepCommands.DEADLINE)) }
    }

    @Test fun endedSingleTrackCanBeReplayed() {
        main {
            controller.setMediaItem(Track("local1", "Silence", "Test", "", 2, "").toMediaItem().buildUpon().setUri(audio.toUri()).build())
            controller.prepare(); controller.play()
        }
        await("single item ends") { controller.playbackState == Player.STATE_ENDED }
        main { controller.togglePlayback() }
        await("replay starts near beginning") { controller.isPlaying && controller.currentPosition < 1500 }
    }

    @Test fun rapidNextThenPausePreservesDestinationWithoutLatePlayback() {
        main {
            controller.setMediaItems((0..2).map { Track("rapid$it", "Track $it", "Test", "", 2, "").toMediaItem().buildUpon().setUri(audio.toUri()).build() })
            controller.prepare(); controller.play()
        }
        await("initial audio starts") { controller.isPlaying }
        main { controller.seekToNextMediaItem(); controller.seekToNextMediaItem(); controller.pause() }
        await("latest destination is paused") { controller.currentMediaItemIndex == 2 && !controller.playWhenReady }
        Thread.sleep(250)
        main { assertEquals(2, controller.currentMediaItemIndex); assertFalse(controller.playWhenReady) }
    }

    @Test fun stopAndClearDuringFadeCannotPerformALateSeek() {
        main {
            controller.setMediaItems((0..2).map { Track("stop$it", "Track $it", "Test", "", 2, "").toMediaItem().buildUpon().setUri(audio.toUri()).build() })
            controller.prepare(); controller.play()
        }
        await("audio starts") { controller.isPlaying }
        main { controller.seekToNextMediaItem(); controller.stop(); controller.clearMediaItems() }
        Thread.sleep(250)
        main { assertEquals(0, controller.mediaItemCount); assertFalse(controller.isPlaying); assertEquals(Player.STATE_IDLE, controller.playbackState) }
    }

    @Test fun seekEnvelopePreservesUserVolume() {
        main {
            controller.volume = 0.37f
            controller.setMediaItems((0..1).map { Track("gain$it", "Track $it", "Test", "", 2, "").toMediaItem().buildUpon().setUri(audio.toUri()).build() })
            controller.prepare(); controller.play()
        }
        await("audio starts") { controller.isPlaying }
        main { controller.seekToNextMediaItem() }
        await("next plays") { controller.currentMediaItemIndex == 1 && controller.isPlaying && controller.currentPosition >= 200 }
        main { assertEquals(0.37f, controller.volume, 0.0001f); controller.volume = 1f }
    }

    @Test fun playNextAndQueueSelectionPreserveEffectiveShuffleOrder() {
        main {
            controller.setMediaItems((0..5).map { Track("track$it", "Track $it", "Test", "", 2, "").toMediaItem().buildUpon().setUri(audio.toUri()).build() }, 2, 0)
            controller.shuffleModeEnabled = true
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.prepare()
        }
        await("queue is ready") { controller.playbackState == Player.STATE_READY && controller.mediaItemCount == 6 }
        var originalOrder = emptyList<String>()
        main { originalOrder = controller.upcomingQueue().map { it.track.id } }
        val next = Track("requested7", "Requested next", "Test", "", 2, "")
        lateinit var command: com.google.common.util.concurrent.ListenableFuture<SessionResult>
        main { command = controller.sendCustomCommand(QueueCommands.playNext, Bundle().apply { putString(QueueCommands.TRACK, next.toJson().toString()) }) }
        assertEquals(SessionResult.RESULT_SUCCESS, command.get(10, TimeUnit.SECONDS).resultCode)
        await("requested item is immediately next") { controller.upcomingQueue().getOrNull(1)?.track?.id == next.id }
        main {
            val queue = controller.upcomingQueue()
            assertEquals(7, queue.size)
            assertEquals(originalOrder, queue.filterNot { it.track.id == next.id }.map { it.track.id })
            assertTrue(controller.shuffleModeEnabled)
            val selected = queue[3]
            controller.playQueueItem(selected.mediaIndex)
            assertEquals(selected.track.id, controller.currentMediaItem?.mediaId)
            assertTrue(controller.shuffleModeEnabled)
            controller.pause()
            controller.seekToDefaultPosition(2)
            controller.seekToNextMediaItem()
            assertEquals(next.id, controller.currentMediaItem?.mediaId)
        }
    }
}
