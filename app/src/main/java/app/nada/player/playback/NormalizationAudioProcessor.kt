package app.nada.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class NormalizationAudioProcessor(private val enabled: () -> Boolean) : BaseAudioProcessor() {
    private var normalizer: VolumeNormalization? = null
    private var ended = false
    @Volatile var processedFrames: Long = 0
        private set
    @Volatile var normalizationGain: Double = 1.0
        private set
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8 || inputAudioFormat.sampleRate !in 8000..192000)
            throw UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat // Keep the same chain/latency when the user toggles the setting.
    }
    override fun onFlush(streamMetadata: StreamMetadata) {
        ended = false
        val old = normalizer
        normalizer = if (old?.sampleRate == inputAudioFormat.sampleRate && old.channels == inputAudioFormat.channelCount) old.also { it.flush() }
            else VolumeNormalization(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, old?.gain ?: 1.0)
    }
    fun newStream() { normalizer?.newStream() }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val processor = checkNotNull(normalizer)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val frames = inputBuffer.remaining() / (processor.channels * 2)
        processor.process(inputBuffer, output, enabled())
        processedFrames += frames
        normalizationGain = processor.gain
        output.flip()
    }
    override fun onQueueEndOfStream() { ended = true }
    override fun getOutput(): ByteBuffer {
        val pending = super.getOutput()
        val processor = normalizer ?: return pending
        if (pending.hasRemaining() || !ended || processor.pendingFrames == 0) return pending
        // The pipeline may retain the previous output while signalling EOS. Drain lazily,
        // after it asks for more output, so the reusable buffer cannot overwrite those samples.
        val output = replaceOutputBuffer(processor.pendingFrames * processor.channels * 2)
        processor.drain(output, enabled()); output.flip()
        return super.getOutput()
    }
    override fun isEnded(): Boolean = super.isEnded() && (normalizer?.pendingFrames ?: 0) == 0
    override fun onReset() { normalizer = null; ended = false }
}

/** Encoded passthrough/offload must not skip the PCM processor on external audio routes. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class NormalizationAudioSink(delegate: AudioSink, private val processor: NormalizationAudioProcessor) : ForwardingAudioSink(delegate) {
    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int = if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        processor.newStream()
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }
}
