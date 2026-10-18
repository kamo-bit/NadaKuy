package app.nada.player.playback

import android.content.ComponentName
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Generated tones through the real service, for bounded host-loopback comparison. */
@RunWith(AndroidJUnit4::class)
class TransitionToneTest {
    @Test fun directAndroidToneControl() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("transitionTone") == "true")
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val rate = 44_100
        val samples = ShortArray(rate * 3 * 2) { index ->
            val frame = index / 2
            val edge = minOf(1.0, frame / (rate * .05), (rate * 3 - 1 - frame) / (rate * .05))
            (sin(2 * PI * 440 * frame / rate) * edge * 6000).toInt().toShort()
        }
        val audio = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(samples.size * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
        try {
            assertEquals(samples.size, audio.write(samples, 0, samples.size))
            audio.play(); Thread.sleep(4000)
        } finally { audio.release(); activity.close() }
    }
    @Test fun realServiceToneTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("transitionTone") == "true")
        val context = instrumentation.targetContext
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(description: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < end) {
                var ready = false
                main { ready = condition() }
                if (ready) return
                Thread.sleep(30)
            }
            fail(description)
        }
        val tones = listOf(440, 660).map { frequency ->
            File(context.cacheDir, "transition-$frequency.wav").apply {
                val rate = 44_100
                val frames = rate * 10
                val dataSize = frames * 4
                val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
                bytes.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
                    .put("data".toByteArray()).putInt(dataSize)
                repeat(frames) { frame ->
                    val edge = minOf(1.0, frame / (rate * 0.05), (frames - 1 - frame) / (rate * 0.05))
                    val sample = (sin(2 * PI * frequency * frame / rate) * edge * 6_000).toInt().toShort()
                    bytes.putShort(sample).putShort(sample)
                }
                writeBytes(bytes.array())
            }
        }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controls = future.get(10, TimeUnit.SECONDS)
        try {
            main {
                controls.setMediaItems(tones.map { MediaItem.fromUri(it.toUri()) })
                controls.prepare(); controls.play()
            }
            await("first tone plays") { controls.isPlaying && controls.currentPosition > 300 }
            fun transition(label: String, index: Int, action: () -> Unit) {
                Thread.sleep(2500)
                android.util.Log.i("NadaToneTest", "$label command at ${SystemClock.elapsedRealtime()}")
                main(action)
                await(label) { assertNull(controls.playerError); controls.currentMediaItemIndex == index && controls.isPlaying }
            }
            transition("next", 1) { controls.seekToNextMediaItem() }
            transition("previous", 0) { controls.seekToPreviousMediaItem() }
            transition("seek", 0) { controls.seekTo(5500) }
            transition("automatic", 1) { controls.seekTo(9400) }
            Thread.sleep(2500)
        } finally {
            main { controls.stop(); controls.clearMediaItems(); controls.release() }
            activity.close(); tones.forEach(File::delete)
        }
    }
}
