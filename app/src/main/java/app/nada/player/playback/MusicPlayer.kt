package app.nada.player.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.nada.player.youtube.YouTubeDataSource

/** One playback configuration for the service and real decoder/AudioTrack verification. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal data class MusicPlayback(val engine: ExoPlayer, val player: TransitionPlayer)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun createMusicPlayback(context: Context): MusicPlayback {
    val gain = TransitionGain()
    val engine = ExoPlayer.Builder(context,
    object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            TransitionGainSink(DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                // Music tolerates this modest latency; give AudioTrack more scheduling headroom.
                .setAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(500_000).setMaxPcmBufferDurationUs(1_000_000).build())
                .build(), gain)
    }.setEnableDecoderFallback(true))
    .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory {
        YouTubeDataSource(DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(25_000)
            .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"))
            .createDataSource())
    }))
    .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
    .setHandleAudioBecomingNoisy(true)
    .setWakeMode(C.WAKE_MODE_NETWORK)
    .build()
    return MusicPlayback(engine, TransitionPlayer(engine, gain))
}
