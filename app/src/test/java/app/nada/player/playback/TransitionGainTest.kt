package app.nada.player.playback

import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class TransitionGainTest {
    @Test fun envelopeMultipliesOutputWithoutChangingSourceVolume() {
        val volumes = mutableListOf<Float>()
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            if (method.name == "setVolume") volumes += args!![0] as Float
            null
        } as AudioSink
        val gain = TransitionGain()
        val sink = TransitionGainSink(delegate, gain)
        sink.setVolume(0.6f)
        gain.handleMessage(0, 0.5f)
        sink.setVolume(0.2f) // User volume or audio-focus ducking changes while fading.
        gain.handleMessage(0, 1f)
        assertEquals(listOf(0.6f, 0.3f, 0.1f, 0.2f), volumes)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidEnvelopeGain() {
        TransitionGain().handleMessage(0, Float.NaN)
    }
}
