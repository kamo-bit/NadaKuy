package app.nada.player.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/** An output-only envelope, independent of user volume and audio-focus ducking. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class TransitionGain : PlayerMessage.Target {
    var sink: TransitionGainSink? = null
    override fun handleMessage(messageType: Int, payload: Any?) {
        val gain = payload as Float
        require(gain.isFinite() && gain in 0f..1f)
        sink?.envelope(gain)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class TransitionGainSink(delegate: AudioSink, gain: TransitionGain) : ForwardingAudioSink(delegate) {
    private var sourceVolume = 1f
    private var envelopeGain = 1f
    init { gain.sink = this }
    override fun setVolume(volume: Float) {
        sourceVolume = volume
        super.setVolume(sourceVolume * envelopeGain)
    }
    fun envelope(gain: Float) {
        envelopeGain = gain
        super.setVolume(sourceVolume * envelopeGain)
    }
}

/** All session seek commands share one cancellable fade; playback intent remains owned by ExoPlayer. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class TransitionPlayer(private val engine: ExoPlayer, private val gain: TransitionGain) : ForwardingSimpleBasePlayer(engine) {
    private val handler = Handler(engine.applicationLooper)
    private var envelope = 1f
    private var ramp: Runnable? = null
    private var pending: Runnable? = null
    private var completion: SettableFuture<Void>? = null
    private var released = false
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && pending == null) fade(1f, 90)
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) { cancelSeek(); cancelRamp() }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                setGain(0f)
                if (engine.isPlaying) fade(1f, 90)
            }
        }
    }
    init { engine.addListener(listener) }

    private fun setGain(value: Float) {
        if (released) return
        envelope = value
        engine.createMessage(gain).setPayload(value).send()
    }
    private fun cancelRamp() { ramp?.let(handler::removeCallbacks); ramp = null }
    private fun fade(target: Float, durationMs: Long, after: (() -> Unit)? = null) {
        cancelRamp()
        val initial = envelope
        val start = SystemClock.uptimeMillis()
        val task = object : Runnable {
            override fun run() {
                if (released) return
                val fraction = ((SystemClock.uptimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                setGain(initial + (target - initial) * fraction)
                if (fraction < 1f) handler.postDelayed(this, 8) else {
                    ramp = null
                    after?.invoke()
                }
            }
        }
        ramp = task
        task.run()
    }
    private fun cancelSeek() {
        val previous = completion
        pending?.let(handler::removeCallbacks)
        pending = null
        completion = null
        previous?.set(null)
    }
    private fun performPending() {
        val action = pending ?: return
        handler.removeCallbacks(action)
        action.run()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex == C.INDEX_UNSET || engine.isPlayingAd) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        // SimpleBasePlayer resolves relative commands against its optimistic state. Preserve that
        // resolved destination when rapid Next requests supersede an earlier delayed seek.
        cancelSeek()
        cancelRamp()
        val result = SettableFuture.create<Void>()
        completion = result
        val action = Runnable {
            pending = null
            completion = null
            setGain(0f)
            try {
                if (mediaItemIndex in 0 until engine.mediaItemCount) engine.seekTo(mediaItemIndex, positionMs)
                result.set(null)
                if (engine.isPlaying) fade(1f, 90)
            } catch (error: Exception) { result.setException(error) }
        }
        pending = action
        if (engine.isPlaying) {
            fade(0f, 64) {
                // Start the quiet interval after the last gain update, even if the UI
                // looper was busy. A fixed deadline could otherwise race the final tick.
                if (pending === action) handler.postDelayed(action, 30)
            }
        } else action.run()
        return result
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) { cancelRamp(); setGain(0f); performPending() }
        return super.handleSetPlayWhenReady(playWhenReady)
    }
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        cancelSeek(); cancelRamp(); setGain(0f)
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }
    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        cancelSeek(); if (engine.isPlaying) fade(1f, 90)
        return super.handleAddMediaItems(index, mediaItems)
    }
    fun insertNext(item: MediaItem) {
        cancelSeek(); if (engine.isPlaying) fade(1f, 90)
        engine.insertNext(item)
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        cancelSeek(); if (engine.isPlaying) fade(1f, 90)
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        cancelSeek(); if (engine.isPlaying) fade(1f, 90)
        return super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
    }
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        cancelSeek(); if (engine.isPlaying) fade(1f, 90)
        return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
    }
    override fun handleStop(): ListenableFuture<*> {
        cancelSeek(); cancelRamp(); setGain(0f)
        return super.handleStop()
    }
    override fun handleRelease(): ListenableFuture<*> {
        cancelSeek(); cancelRamp(); engine.removeListener(listener); released = true
        return super.handleRelease()
    }
}
