package app.nada.player.playback

import android.content.ComponentName
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import app.nada.player.MusicState
import app.nada.player.MusicViewModel
import app.nada.player.PlaybackProgress
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in production ViewModel/service journey using two existing offline recordings. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackProgressLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(label: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Timed out: $label")
    }

    @Test fun progressDoesNotReplaceContentOrQueueAndResumesAfterBackground() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("performancePlayback") == "true")
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: MusicViewModel
        scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future.get(15, TimeUnit.SECONDS)
        var originalItems = emptyList<MediaItem>()
        var originalIndex = 0
        var originalPosition = 0L
        var originalShuffle = false
        var originalRepeat = Player.REPEAT_MODE_OFF
        var originalAutoplay = false
        main {
            originalItems = (0 until controller.mediaItemCount).map(controller::getMediaItemAt)
            originalIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
            originalPosition = controller.currentPosition
            originalShuffle = controller.shuffleModeEnabled
            originalRepeat = controller.repeatMode
            originalAutoplay = model.state.recommendationPreferences.autoplay
        }
        try {
            await("offline index") { model.state.offline.loaded }
            var recordings = emptyList<app.nada.player.data.Track>()
            main { recordings = model.state.offline.tracks.filter { it.ready }.mapNotNull { it.track }.take(2) }
            assumeTrue("Two downloaded recordings are required", recordings.size == 2)
            main { model.setAutoplay(false); model.play(recordings) }
            await("offline playback and home settling", 90_000) {
                controller.isPlaying && model.state.queue.size == 2 && model.progress.position > 1000 &&
                    !model.state.loading && !model.state.recommendationsLoading
            }
            lateinit var content: MusicState
            lateinit var queue: List<QueuedTrack>
            var position = 0L
            main { content = model.state; queue = content.queue; position = model.progress.position }
            await("three progress ticks") { model.progress.position > position + 2000 }
            main {
                assertSame("Progress must not replace content state", content, model.state)
                assertSame("Progress must not rebuild queue", queue, model.state.queue)
                model.togglePlayback()
            }
            await("paused") { !controller.isPlaying && !model.state.playing }
            main { model.seek(20_000) }
            await("paused seek") { model.progress.position in 19_900..20_100 }
            main { assertSame("Seeking within a song must retain queue", queue, model.state.queue); model.setSleepTimer(5) }
            await("session extras") { model.state.sleepDeadline > SystemClock.elapsedRealtime() && model.progress.sleepRemainingSeconds > 0 }
            main { assertSame(queue, model.state.queue); model.setSleepTimer(0); model.next() }
            await("next item snapshot") { model.state.current?.id == recordings[1].id && model.state.queue.firstOrNull()?.track?.id == recordings[1].id }
            main {
                assertNotSame(queue, model.state.queue)
                model.previous()
            }
            await("previous item snapshot") { model.state.current?.id == recordings[0].id }
            main { model.seek(0); if (!controller.playWhenReady) model.togglePlayback() }
            await("playing before background") { controller.isPlaying && model.progress.position > 1000 }
            scenario.moveToState(Lifecycle.State.CREATED)
            lateinit var hiddenProgress: PlaybackProgress
            var hiddenPosition = 0L
            main { hiddenProgress = model.progress; hiddenPosition = controller.currentPosition }
            Thread.sleep(2000)
            main {
                assertTrue("Playback must continue in background", controller.currentPosition > hiddenPosition + 1500)
                assertSame("UI polling must stop in background", hiddenProgress, model.progress)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("foreground progress catches up") { model.progress.position > hiddenPosition + 1500 }
            main { assertNull(controller.playerError); model.togglePlayback() }
            await("final pause") { !controller.isPlaying }
        } finally {
            main {
                controller.pause()
                controller.setMediaItems(originalItems, originalIndex, originalPosition)
                controller.shuffleModeEnabled = originalShuffle
                controller.repeatMode = originalRepeat
                if (originalItems.isNotEmpty()) controller.prepare()
                model.setSleepTimer(0)
                model.setAutoplay(originalAutoplay)
                controller.release()
            }
            scenario.close()
        }
    }
}
