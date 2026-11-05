package app.nada.player.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessingPipeline
import com.google.common.collect.ImmutableList
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import app.nada.player.NadaApplication
import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.*

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class NormalizationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            var done = false; main { done = condition() }; if (done) return; Thread.sleep(50)
        }
        fail("Timed out: $label")
    }
    @Test fun preferenceDefaultsOnPersistsAndCanBeDisabled() {
        val name = "normalization-test-${UUID.randomUUID()}"
        try {
            val settings = PlaybackSettings(context, name)
            assertTrue(settings.normalization.value)
            settings.setNormalization(false); assertFalse(PlaybackSettings(context, name).normalization.value)
            settings.setNormalization(true); assertTrue(PlaybackSettings(context, name).normalization.value)
        } finally { context.deleteSharedPreferences(name) }
    }
    @Test fun encodedPassthroughAndOffloadCannotBypassProcessingEvenWhenTheRouteSupportsThem() {
        val platform = java.lang.reflect.Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            when (method.name) {
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "supportsFormat" -> true
                else -> error("Unexpected platform call: ${method.name}")
            }
        } as AudioSink
        val sink = NormalizationAudioSink(platform, NormalizationAudioProcessor { true })
        val aac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        assertTrue(platform.supportsFormat(aac))
        assertFalse(sink.supportsFormat(aac)); assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(aac))
        assertEquals(AudioOffloadSupport.DEFAULT_UNSUPPORTED, sink.getFormatOffloadSupport(aac))
        assertTrue(sink.supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelCount(2).build()))
    }
    @Test fun audioProcessorRetainsShortStreamTailsAndDropsBufferedAudioOnSeek() {
        val processor = NormalizationAudioProcessor { false }
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder()).apply { repeat(80) { putShort(it.toShort()) }; flip() }
        processor.queueInput(input); assertFalse(processor.output.hasRemaining())
        processor.queueEndOfStream()
        val output = processor.output
        assertEquals(160, output.remaining()); repeat(80) { assertEquals(it.toShort(), output.short) }
        assertTrue(processor.isEnded)
        processor.flush(AudioProcessor.StreamMetadata(1_000_000))
        processor.queueInput(ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder()))
        processor.flush(AudioProcessor.StreamMetadata(2_000_000))
        processor.queueEndOfStream(); assertFalse(processor.output.hasRemaining()); assertTrue(processor.isEnded)
        processor.reset()
    }
    @Test fun processorRunsAtBothMusicSampleRatesAndRejectsUnsupportedFormats() {
        for (rate in listOf(44100, 48000)) for (channels in listOf(1, 2)) {
            val processor = NormalizationAudioProcessor { true }
            assertEquals(rate, processor.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)).sampleRate)
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val input = ByteBuffer.allocateDirect(rate * channels * 2).order(ByteOrder.nativeOrder())
            processor.queueInput(input)
            val bytes = processor.output.remaining()
            processor.queueEndOfStream()
            assertEquals(rate * channels * 2, bytes + processor.output.remaining())
            assertEquals(rate.toLong(), processor.processedFrames)
        }
        assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            NormalizationAudioProcessor { true }.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        }
    }
    @Test fun media3PipelineDrainsEosWithoutOverwritingBackpressuredOutputOrDroppingTail() {
        for (frames in listOf(3, 480, 481, 1024, 4096)) {
            val pipeline = AudioProcessingPipeline(ImmutableList.of(NormalizationAudioProcessor { false }))
            pipeline.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
            pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder()).apply {
                repeat(frames * 2) { putShort((it % 32767).toShort()) }; flip()
            }
            pipeline.queueInput(input)
            // The sink can signal EOS before it has consumed the pipeline's output.
            pipeline.queueEndOfStream()
            var count = 0
            repeat(10) {
                val output = pipeline.output
                while (output.hasRemaining()) assertEquals((count++ % 32767).toShort(), output.short)
                if (pipeline.isEnded) return@repeat
            }
            assertTrue(pipeline.isEnded); assertEquals(frames * 2, count)
            pipeline.reset()
        }
    }
    private fun wav(name: String, peak: Double, seconds: Int): File {
        val rate = 48_000; val bytes = rate * seconds * 2 * 2
        val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(rate).putInt(rate * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(bytes)
        repeat(rate * seconds) { frame ->
            val sample = (sin(2 * PI * 440 * frame / rate) * peak * 32767).roundToInt().toShort()
            buffer.putShort(sample).putShort(sample)
        }
        return File(context.cacheDir, name).also { it.writeBytes(buffer.array()) }
    }
    @Test fun realDecoderLevelsBothTracksAndTogglesWithoutChangingUserVolume() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("normalizationAudio") == "true")
        val settings = (context.applicationContext as NadaApplication).playbackSettings
        val old = settings.normalization.value
        val quiet = wav("normalization-quiet.wav", 0.1, 8)
        val loud = wav("normalization-loud.wav", 0.6, 8)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        lateinit var playback: MusicPlayback
        main {
            settings.setNormalization(true)
            playback = createMusicPlayback(context)
            playback.player.volume = 0.37f
            playback.player.setMediaItems(listOf(quiet, loud).mapIndexed { i, file ->
                Track("normalization$i", file.name, "Fixture", "", 8, "").toMediaItem().buildUpon().setUri(file.toUri()).build()
            })
            playback.player.prepare(); playback.player.play()
        }
        try {
            await("quiet audio really processed") { assertNull(playback.engine.playerError); playback.engine.isPlaying && playback.normalization.processedFrames > 192_000 && playback.normalization.normalizationGain > 1.4 }
            main { assertEquals(0.37f, playback.engine.volume, 0.0001f) }
            await("automatic transition and loud audio level") { assertNull(playback.engine.playerError); playback.engine.currentMediaItemIndex == 1 && playback.engine.currentPosition > 2000 && playback.normalization.normalizationGain < 0.5 }
            main { settings.setNormalization(false) }
            await("bypass ramp reaches unity") { abs(playback.normalization.normalizationGain - 1.0) < 0.001 }
            main { assertTrue(playback.engine.isPlaying); assertEquals(0.37f, playback.engine.volume, 0.0001f); settings.setNormalization(true) }
            main { playback.player.seekTo(0, 3000) }
            await("seek and previous track remain playable") { playback.engine.isPlaying && playback.engine.currentMediaItemIndex == 0 && playback.engine.currentPosition > 3200 }
        } finally {
            main { playback.player.release(); settings.setNormalization(old) }
            activity.close(); quiet.delete(); loud.delete()
        }
    }
}
