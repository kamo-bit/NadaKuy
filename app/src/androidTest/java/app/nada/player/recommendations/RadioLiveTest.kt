package app.nada.player.recommendations

import android.content.ComponentName
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import app.nada.player.NadaApplication
import app.nada.player.playback.*
import app.nada.player.youtube.YouTubeCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class RadioLiveTest {
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun await(label: String, seconds: Long = 90, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < until) {
            var done = false; main { done = predicate() }
            if (done) return
            Thread.sleep(100)
        }
        fail("Timed out: $label")
    }
    @Test fun realRadioRespectsFeedbackSleepTimerAndAutomaticBackgroundTransition() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRadio") == "true")
        val app = ApplicationProvider.getApplicationContext<NadaApplication>()
        val settings = app.recommendationSettings
        val before = settings.state.value
        val evidence = mutableListOf<String>()
        val seed = YouTubeCatalog.search("Dewa 19 Kangen").first()
        val activity = ActivityScenario.launch(MainActivity::class.java)
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        val controller = future.get(15, TimeUnit.SECONDS)
        var blocked: String? = null
        var disliked: app.nada.player.data.Track? = null
        fun command(command: androidx.media3.session.SessionCommand, args: Bundle = Bundle.EMPTY) {
            lateinit var response: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            main { response = controller.sendCustomCommand(command, args) }
            assertEquals(SessionResult.RESULT_SUCCESS, response.get(15, TimeUnit.SECONDS).resultCode)
        }
        try {
            command(RadioCommands.start, Bundle().apply { putString(QueueCommands.TRACK, seed.toJson().toString()) })
            await("radio starts and fills") { controller.isPlaying && controller.mediaItemCount >= 3 && !controller.sessionExtras.getBoolean(RadioCommands.LOADING) }
            main {
                assertEquals(seed.title, controller.sessionExtras.getString(RadioCommands.TITLE))
                evidence += "Seed=${seed.id} | ${seed.title} | ${seed.artist}"
                evidence += controller.upcomingQueue().drop(1).map { "Radio=${it.track.id} | ${it.track.title} | ${it.track.artist}" }
            }
            var nextId: String? = null
            main { nextId = controller.getMediaItemAt(1).mediaId; controller.seekToNext() }
            await("next plays") { controller.currentMediaItem?.mediaId == nextId && controller.isPlaying && controller.currentPosition > 200 }
            main { evidence += "Next=$nextId; playing=${controller.isPlaying}"; controller.seekToPrevious() }
            await("previous returns to seed") { controller.currentMediaItem?.mediaId == seed.id && controller.isPlaying }
            main {
                evidence += "Previous=${controller.currentMediaItem?.mediaId}; playing=${controller.isPlaying}"
                disliked = controller.upcomingQueue().first { it.automatic }.track
                settings.toggleDislike(disliked!!)
            }
            await("disliked song removed") { controller.upcomingQueue().none { it.automatic && ListeningRecommendations.recordingKey(it.track) == ListeningRecommendations.recordingKey(disliked!!) } }
            main { settings.toggleDislike(disliked!!); disliked = null }
            await("refill after restoring") { !controller.sessionExtras.getBoolean(RadioCommands.LOADING) && controller.mediaItemCount >= 2 }
            main {
                blocked = controller.upcomingQueue().first { it.automatic }.track.artist
                settings.toggleArtist(blocked!!)
            }
            await("blocked artist removed") { controller.upcomingQueue().none { it.automatic && settings.state.value.blocks(it.track.artist) } }
            main { settings.toggleArtist(blocked!!); blocked = null }
            await("refill restored") { !controller.sessionExtras.getBoolean(RadioCommands.LOADING) && controller.mediaItemCount >= 2 }
            command(SleepCommands.set, Bundle().apply { putString(SleepCommands.MODE, "end") })
            await("sleep and autoplay extras coexist", 10) { controller.sessionExtras.getBoolean(SleepCommands.END_OF_TRACK) && controller.sessionExtras.getBoolean(RadioCommands.AUTOPLAY) }
            val firstId = seed.id
            main { controller.seekTo((controller.duration - 700).coerceAtLeast(0)) }
            await("end-of-track sleep pauses", 20) { !controller.playWhenReady && !controller.sessionExtras.getBoolean(SleepCommands.END_OF_TRACK) }
            main { assertEquals(firstId, controller.currentMediaItem?.mediaId) }
            command(SleepCommands.set, Bundle().apply { putString(SleepCommands.MODE, "off") })
            main { controller.seekTo((controller.duration - 1000).coerceAtLeast(0)); controller.play() }
            activity.moveToState(Lifecycle.State.CREATED)
            await("automatic background transition", 45) { controller.currentMediaItem?.mediaId != firstId && controller.isPlaying && controller.currentPosition > 500 }
            main {
                evidence += "Background transition=${controller.currentMediaItem?.mediaId}; position=${controller.currentPosition}; playing=${controller.isPlaying}"
                assertTrue(controller.currentMediaItem?.mediaMetadata?.extras?.getBoolean(RadioCommands.AUTOMATIC) == true)
                controller.pause()
                settings.setAutoplay(false)
            }
            await("autoplay off removes future automatic songs", 10) { controller.upcomingQueue().drop(1).none { it.automatic } }
            File(app.noBackupFilesDir, "radio-live.txt").writeText(evidence.joinToString("\n"))
        } finally {
            main {
                disliked?.let { if (settings.state.value.dislikes(it) && !before.dislikes(it)) settings.toggleDislike(it) }
                blocked?.let { if (settings.state.value.blocks(it) && !before.blocks(it)) settings.toggleArtist(it) }
                controller.pause(); controller.clearMediaItems(); settings.setAutoplay(before.autoplay); controller.release()
            }
            activity.close()
        }
    }
}
