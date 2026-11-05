package app.nada.player.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.nada.player.downloads.OfflineLibrary

/** One playback configuration for the service and real decoder/AudioTrack verification. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal data class MusicPlayback(val engine: ExoPlayer, val player: TransitionPlayer, val normalization: NormalizationAudioProcessor)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun createMusicPlayback(context: Context): MusicPlayback {
    val gain = TransitionGain()
    val settings = (context.applicationContext as app.nada.player.NadaApplication).playbackSettings
    val normalization = NormalizationAudioProcessor { settings.normalization.value }
    val engine = ExoPlayer.Builder(context,
    object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
            val sink = TransitionGainSink(DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf(normalization))
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                // Music tolerates this modest latency; give AudioTrack more scheduling headroom.
                .setAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(500_000).setMaxPcmBufferDurationUs(1_000_000).build())
                .build(), gain)
            return NormalizationAudioSink(sink, normalization)
        }
    }.setEnableDecoderFallback(true))
    .setMediaSourceFactory(DefaultMediaSourceFactory(OfflineLibrary.get(context).playbackFactory()))
    .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
    .setHandleAudioBecomingNoisy(true)
    .setWakeMode(C.WAKE_MODE_NETWORK)
    .build()
    return MusicPlayback(engine, TransitionPlayer(engine, gain), normalization)
}
