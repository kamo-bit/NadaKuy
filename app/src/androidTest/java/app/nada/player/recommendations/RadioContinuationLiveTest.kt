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
import app.nada.player.data.Track
import app.nada.player.playback.*
import app.nada.player.youtube.YouTubeCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Real catalogue and decoder; seeks shorten songs, never replace automatic refill or transitions. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class RadioContinuationLiveTest {
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    @Test fun radioContinuesBeyondItsFirstSixAutomaticSongs() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveRadioContinuation") == "true")
        val query = args.getString("radioQuery") ?: "Dewa 19 Kangen"
        val app = ApplicationProvider.getApplicationContext<NadaApplication>()
        val before = app.recommendationSettings.state.value
        val report = File(app.noBackupFilesDir, "radio-continuation-live.txt")
        val evidence = mutableListOf("Query=$query; target=12 decoded songs; 3s head + seek to final 1.2s; second half background")
        fun record(line: String) { evidence += line; report.writeText(evidence.joinToString("\n")) }
        val seed = YouTubeCatalog.search(query).first()
        val activity = ActivityScenario.launch(MainActivity::class.java)
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        val controller = future.get(15, TimeUnit.SECONDS)
        val generated = linkedMapOf<String, Track>()
        val played = mutableListOf<Track>()
        var batches = 0
        fun observe() {
            val added = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it) }
                .filter { it.mediaMetadata.extras?.getBoolean(RadioCommands.AUTOMATIC) == true && it.mediaId !in generated }
            if (added.isNotEmpty()) {
                batches++
                added.forEach { generated[it.mediaId] = it.toTrack() }
                record("Refill=$batches; added=${added.size}; automaticTotal=${generated.size}; current=${controller.currentMediaItemIndex}; " +
                    added.joinToString(" / ") { "${it.mediaId}|${it.mediaMetadata.title}|${it.mediaMetadata.artist}" })
            }
        }
        fun await(label: String, seconds: Long = 90, predicate: () -> Boolean) {
            val until = android.os.SystemClock.elapsedRealtime() + seconds * 1000
            while (android.os.SystemClock.elapsedRealtime() < until) {
                var done = false
                main { observe(); assertNull("Decoder failed: $label", controller.playerError); done = predicate() }
                if (done) return
                Thread.sleep(100)
            }
            main { record("TIMEOUT=$label; state=${controller.playbackState}; id=${controller.currentMediaItem?.mediaId}; index=${controller.currentMediaItemIndex}; count=${controller.mediaItemCount}; loading=${controller.sessionExtras.getBoolean(RadioCommands.LOADING)}; error=${controller.sessionExtras.getString(RadioCommands.ERROR)}") }
            fail("Timed out: $label")
        }
        try {
            lateinit var response: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            main { response = controller.sendCustomCommand(RadioCommands.start, Bundle().apply { putString(QueueCommands.TRACK, seed.toJson().toString()) }) }
            assertEquals(SessionResult.RESULT_SUCCESS, response.get(15, TimeUnit.SECONDS).resultCode)
            await("initial automatic batch") { controller.isPlaying && generated.size >= 4 && !controller.sessionExtras.getBoolean(RadioCommands.LOADING) }
            val firstBatch = generated.keys.toSet()
            for (index in 0 until 12) {
                await("song ${index + 1} decoded") { controller.isPlaying && controller.currentPosition >= 3000 && controller.duration > 5000 }
                main {
                    val track = controller.currentMediaItem!!.toTrack()
                    assertFalse("Recording repeated during continuation", played.any { ListeningRecommendations.recordingKey(it) == ListeningRecommendations.recordingKey(track) })
                    assertEquals("Original station lost", seed.title, controller.sessionExtras.getString(RadioCommands.TITLE))
                    assertTrue(controller.sessionExtras.getBoolean(RadioCommands.AUTOPLAY))
                    played += track
                    record("Played=${index + 1}; ${track.id}|${track.title}|${track.artist}; position=${controller.currentPosition}; upcoming=${controller.upcomingQueue().size}; background=${index >= 6}")
                }
                if (index == 11) break
                if (index == 5) activity.moveToState(Lifecycle.State.CREATED)
                await("automatic next song available after ${index + 1}") { !controller.sessionExtras.getBoolean(RadioCommands.LOADING) && controller.upcomingQueue().size > 1 }
                var expected: String? = null
                main {
                    expected = controller.upcomingQueue()[1].track.id
                    controller.seekTo(controller.duration - 1200)
                }
                await("automatic end transition ${index + 1}", 45) { controller.currentMediaItem?.mediaId == expected && controller.isPlaying && controller.currentPosition > 200 }
            }
            val artists = played.map { ListeningRecommendations.artistKey(it.artist) }.distinct()
            val laterArtists = played.drop(6).map { ListeningRecommendations.artistKey(it.artist) }.distinct()
            assertTrue("No refill after first batch", generated.keys.any { it !in firstBatch })
            assertTrue("Never played beyond initial batch", played.drop(1).any { it.id !in firstBatch })
            assertTrue("Too few artists in extended station", artists.size >= 4)
            assertTrue("Later refills collapsed to one artist", laterArtists.size >= 3)
            assertTrue("Same artist three times consecutively", played.windowed(3).none { group -> group.map { ListeningRecommendations.artistKey(it.artist) }.distinct().size == 1 })
            assertTrue("No successive automatic refills", batches >= 2)
            record("PASS; played=${played.size}; artists=${artists.size}; laterArtists=${laterArtists.size}; refills=$batches; generated=${generated.size}; firstBatch=${firstBatch.size}; playedBeyondFirstBatch=${played.count { it.id !in firstBatch && it.id != seed.id }}")
        } catch (failure: Throwable) {
            record("FAIL=${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        } finally {
            main { controller.pause(); controller.clearMediaItems(); app.recommendationSettings.setAutoplay(before.autoplay); controller.release() }
            activity.close()
        }
    }
}
