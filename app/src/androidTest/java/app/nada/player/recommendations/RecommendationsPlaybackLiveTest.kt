package app.nada.player.recommendations

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import app.nada.player.playback.PlaybackService
import app.nada.player.playback.toMediaItem
import app.nada.player.youtube.YouTubeCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in: two real qualified listens intentionally update the local listening profile/history. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class RecommendationsPlaybackLiveTest {
    @Test fun twoCatalogPlaysBecomeAStoredFavorite() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRecommendationPlayback") == "true")
        val track = YouTubeCatalog.search("Dewa 19 Kangen").first()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var controller: MediaController? = null
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(instrumentation.targetContext, SessionToken(instrumentation.targetContext,
                ComponentName(instrumentation.targetContext, PlaybackService::class.java))).buildAsync() }
            val player = future.get(10, TimeUnit.SECONDS).also { controller = it }
            val store = ListeningHistory.get(instrumentation.targetContext)
            val initial = store.state.value.firstOrNull { it.track.id == track.id }?.plays ?: 0
            Thread.sleep(500)
            repeat(2) { index ->
                main { player.stop(); player.clearMediaItems(); player.repeatMode = Player.REPEAT_MODE_OFF
                    player.setMediaItem(track.toMediaItem()); player.prepare(); player.play() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50)
                while ((store.state.value.firstOrNull { it.track.id == track.id }?.plays ?: 0) < initial + index + 1 && System.nanoTime() < deadline) {
                    var error: String? = null
                    main { error = player.playerError?.errorCodeName }
                    assertNull(error, error)
                    Thread.sleep(500)
                }
                assertEquals(initial + index + 1, store.state.value.firstOrNull { it.track.id == track.id }?.plays)
                main { player.pause() }
            }
            assertTrue(ListeningRecommendations.frequent(store.state.value, "youtube").any { it.id == track.id })
        } finally {
            main { controller?.let { it.stop(); it.clearMediaItems(); it.release() } }
            scenario.close()
        }
    }
}
