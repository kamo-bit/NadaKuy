package app.nada.player.playback

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class VolumeNormalizationTest {
    private val rate = 48_000
    private fun tone(amplitude: Double, seconds: Int = 16, channels: Int = 2) = ShortArray(rate * seconds * channels) { i ->
        (sin(2 * PI * 440 * (i / channels) / rate) * amplitude * 32767).roundToInt().toShort()
    }
    private fun process(samples: ShortArray, enabled: Boolean = true, chunks: Int = 2048, channels: Int = 2): ShortArray {
        val normalizer = VolumeNormalization(rate, channels)
        val output = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        var index = 0
        while (index < samples.size) {
            val size = min(chunks * channels, samples.size - index)
            val input = ByteBuffer.allocate(size * 2).order(ByteOrder.nativeOrder())
            repeat(size) { input.putShort(samples[index++]) }; input.flip()
            normalizer.process(input, output, enabled)
        }
        normalizer.drain(output, enabled)
        assertEquals(samples.size * 2, output.position())
        output.flip()
        return ShortArray(samples.size) { output.short }
    }
    private fun rms(data: ShortArray, start: Int = data.size * 3 / 4): Double = sqrt(data.drop(start).sumOf { (it / 32768.0).pow(2) } / (data.size - start))
    @Test fun quietAndLoudRecordingsConvergeToTheSameLevelWithinBoostLimits() {
        val quiet = process(tone(0.13))
        val loud = process(tone(0.6))
        val difference = abs(20 * log10(rms(quiet) / rms(loud)))
        assertTrue("Level difference $difference dB", difference < 0.7)
        assertEquals(-18.0, 20 * log10(rms(loud)), 0.4)
    }
    @Test fun peakProtectionCoversSuddenLoudAttacksAndTheFinalDelayedSamples() {
        val quiet = tone(0.08, 12)
        val attack = ShortArray(rate * 2) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val result = process(quiet + attack)
        assertTrue(result.maxOf { abs(it.toInt()) } <= ceil(32768 * 10.0.pow(-1.0 / 20)).toInt())
        assertTrue(result.takeLast(100).any { it != 0.toShort() })
    }
    @Test fun disabledProcessingIsBitExactAndRetainsEveryFrameForShortStreams() {
        listOf(2, 42, 960, 1920, 4000).forEach { length ->
            val input = ShortArray(length) { ((it * 7919) % 65536 - 32768).toShort() }
            assertArrayEquals(input, process(input, enabled = false, chunks = 7))
        }
    }
    @Test fun silenceAndLowNoiseDoNotCauseAmplification() {
        val input = ShortArray(rate * 2 * 10) { if (it % 2 == 0) 4 else -4 }
        assertArrayEquals(input, process(input))
        assertArrayEquals(ShortArray(rate * 2), process(ShortArray(rate * 2)))
    }
    @Test fun resultDoesNotDependOnDecoderChunkBoundariesAndStereoBalanceIsPreserved() {
        val input = tone(0.6, 6).also { for (i in 1 until it.size step 2) it[i] = (it[i] / 2).toShort() }
        val result = process(input, chunks = 137)
        assertArrayEquals(result, process(input, chunks = 4096))
        for (i in result.indices step 2) assertTrue(abs(result[i] / 2 - result[i + 1]) <= 1)
    }
    @Test fun gainChangesAreGradualAndSeekFlushPreservesGainButDiscardsOldAudio() {
        val processor = VolumeNormalization(rate, 1)
        val input = ByteBuffer.allocate(rate * 4 * 2).order(ByteOrder.nativeOrder())
        repeat(rate * 4) { input.putShort(12_000) }; input.flip()
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.nativeOrder())
        processor.process(input, output, true)
        output.flip()
        val data = ShortArray(output.remaining() / 2) { output.short }
        assertTrue((1 until data.size).maxOf { abs(data[it] - data[it - 1]) } <= 2)
        val gain = processor.gain
        processor.flush(); assertEquals(0, processor.pendingFrames); assertEquals(gain, processor.gain, 0.0)
        processor.newStream(); assertEquals(gain, processor.gain, 0.0)
        val silent = ByteBuffer.allocate(rate * 2).order(ByteOrder.nativeOrder())
        val silentOutput = ByteBuffer.allocate(rate * 2).order(ByteOrder.nativeOrder())
        processor.process(silent, silentOutput, true); processor.drain(silentOutput, true)
        assertTrue(silentOutput.array().all { it == 0.toByte() })
    }
}
