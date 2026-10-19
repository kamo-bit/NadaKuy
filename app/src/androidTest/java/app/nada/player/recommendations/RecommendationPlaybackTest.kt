package app.nada.player.recommendations

import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import app.nada.player.data.Track
import app.nada.player.playback.PlaybackService
import app.nada.player.playback.toMediaItem
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class RecommendationPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Playback/count state did not arrive")
    }

    @Test fun actualServiceCountsInBackgroundAndPauseSeekDoNotInflateFrequency() {
        val context = instrumentation.targetContext
        val library = context.getSharedPreferences("nada_library", Context.MODE_PRIVATE)
        val historyBefore = library.getString("history", null)
        val preferences = context.getSharedPreferences("nada_listening", Context.MODE_PRIVATE)
        val countsBefore = preferences.getString("plays", null)
        val file = File(context.cacheDir, "listening-qa.wav")
        val size = 16_000 * 2 * 6
        file.writeBytes(ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(16_000).putInt(32_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        }.array())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var controller: MediaController? = null
        try {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            val player = future.get(10, TimeUnit.SECONDS).also { controller = it }
            val track = Track("youtube:QaListen001", "Listening fixture", "Test Artist", "", 6, "", source = "youtube")
            val store = ListeningHistory.get(context)
            val before = store.state.value.firstOrNull { it.track.id == track.id }?.plays ?: 0
            fun count() = store.state.value.firstOrNull { it.track.id == track.id }?.plays ?: 0
            Thread.sleep(500)
            main { player.stop(); player.clearMediaItems(); player.repeatMode = Player.REPEAT_MODE_ONE
                player.setMediaItem(track.toMediaItem().buildUpon().setUri(file.toUri()).build()); player.prepare(); player.play() }
            await { count() == before + 1 }
            main { player.pause() }
            Thread.sleep(4000)
            assertEquals(before + 1, count())
            main { player.seekTo(5800); player.play() }
            // Closing the Activity doesn't remove the service's observer.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            await { count() == before + 2 }
            assertTrue(preferences.getString("plays", "").orEmpty().contains(track.id))
        } finally {
            main { controller?.let { it.stop(); it.clearMediaItems(); it.repeatMode = Player.REPEAT_MODE_OFF; it.release() } }
            scenario.close()
            library.edit().putString("history", historyBefore).commit()
            preferences.edit().putString("plays", countsBefore).commit()
            file.delete()
        }
    }
}
