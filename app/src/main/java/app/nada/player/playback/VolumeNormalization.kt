package app.nada.player.playback

import java.nio.ByteBuffer
import kotlin.math.*

/** Streaming RMS levelling, not an integrated LUFS/ReplayGain measurement of a whole recording. */
internal class VolumeNormalization(val sampleRate: Int, val channels: Int, initialGain: Double = 1.0) {
    init { require(sampleRate in 8000..192000 && channels in 1..8) }
    val delayFrames = sampleRate / 100 // 10 ms lookahead; bounded independently of input-buffer size.
    private val capacity = delayFrames + 1
    private val samples = ShortArray(capacity * channels)
    private val peaks = DoubleArray(capacity + 1)
    private val indexes = LongArray(capacity + 1)
    private var head = 0
    private var tail = 0
    private var written = 0L
    private var emitted = 0L
    private var blockEnergy = 0.0
    private var blockFrames = 0
    private var meanEnergy = 0.0
    private var measured = false
    private var desiredGain = 1.0
    var gain = initialGain
        private set
    private var limiter = 1.0
    private val up = 1 - exp(-1.0 / (sampleRate * 3.0))
    private val down = 1 - exp(-1.0 / (sampleRate * 0.5))
    private val bypass = 1 - exp(-1.0 / (sampleRate * 0.05))
    private val attack = 1 - exp(-1.0 / (sampleRate * 0.001))
    private val release = 1 - exp(-1.0 / (sampleRate * 0.1))
    val pendingFrames: Int get() = (written - emitted).toInt()

    fun newStream() { blockEnergy = 0.0; blockFrames = 0; meanEnergy = 0.0; measured = false; desiredGain = 1.0 }
    /** A seek discards delayed samples, while preserving the current level to avoid a volume jump. */
    fun flush() { head = 0; tail = 0; written = 0; emitted = 0; blockEnergy = 0.0; blockFrames = 0 }

    fun process(input: ByteBuffer, output: ByteBuffer, enabled: Boolean) {
        require(input.remaining() % (channels * 2) == 0)
        while (input.hasRemaining()) {
            val slot = (written % capacity).toInt() * channels
            var peak = 0.0
            var energy = 0.0
            for (channel in 0 until channels) {
                val sample = input.short
                samples[slot + channel] = sample
                val value = sample / 32768.0
                peak = max(peak, abs(value)); energy += value * value
            }
            blockEnergy += energy / channels; blockFrames++
            if (blockFrames >= sampleRate / 10) {
                val block = blockEnergy / blockFrames
                if (block >= 0.00001) { // Gate at -50 dBFS: silence/noise must not drive a boost.
                    meanEnergy = if (measured) meanEnergy + (block - meanEnergy) * (1 - exp(-0.1 / 3.0)) else block
                    measured = true
                    desiredGain = (10.0.pow(-18.0 / 20) / sqrt(meanEnergy)).coerceIn(0.251188643, 1.995262315)
                }
                blockEnergy = 0.0; blockFrames = 0
            }
            while (head != tail && indexes[head] < emitted) head = (head + 1) % peaks.size
            while (head != tail && peaks[(tail - 1 + peaks.size) % peaks.size] <= peak) tail = (tail - 1 + peaks.size) % peaks.size
            peaks[tail] = peak; indexes[tail] = written; tail = (tail + 1) % peaks.size
            written++
            if (pendingFrames > delayFrames) emit(output, enabled)
        }
    }
    fun drain(output: ByteBuffer, enabled: Boolean) { while (pendingFrames > 0) emit(output, enabled) }
    private fun emit(output: ByteBuffer, enabled: Boolean) {
        while (head != tail && indexes[head] < emitted) head = (head + 1) % peaks.size
        val target = if (enabled) desiredGain else 1.0
        gain += (target - gain) * if (!enabled) bypass else if (target < gain) down else up
        if (abs(target - gain) < 0.000001) gain = target
        val ceiling = if (enabled) 0.891250938 else 1.0 // -1 dBFS sample headroom.
        val futurePeak = if (head != tail) peaks[head] else 0.0
        val limit = if (futurePeak > 0) min(1.0, ceiling / (futurePeak * gain)) else 1.0
        limiter += (limit - limiter) * if (limit < limiter) attack else release
        if (abs(1.0 - limiter) < 0.000001) limiter = 1.0
        val slot = (emitted % capacity).toInt() * channels
        var actualPeak = 0.0
        for (c in 0 until channels) actualPeak = max(actualPeak, abs(samples[slot + c] / 32768.0))
        // This guard covers the first/last lookahead window and extreme impulses without integer overflow.
        val applied = min(gain * limiter, if (actualPeak > 0) ceiling / actualPeak else gain * limiter)
        for (c in 0 until channels) output.putShort((samples[slot + c] * applied).roundToInt().coerceIn(-32768, 32767).toShort())
        emitted++
    }
}
