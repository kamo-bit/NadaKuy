package app.nada.player.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.SessionCommand

object SleepCommands {
    val set = SessionCommand("app.nada.player.SLEEP_TIMER", Bundle.EMPTY)
    const val MODE = "mode"
    const val DURATION = "durationMs"
    const val DEADLINE = "sleepDeadlineElapsedMs"
    const val END_OF_TRACK = "sleepEndOfTrack"
}

/** The playback service owns the timer, so Activity recreation cannot cancel it. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SleepTimer(private val player: ExoPlayer, private val publish: (Bundle) -> Unit) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var endOfTrack = false
    private val expiration = Runnable {
        if (deadline != 0L) { player.pause(); clear() }
    }

    init { player.addListener(this) }

    fun configure(mode: String?, durationMs: Long): Boolean {
        if (mode !in listOf("off", "end", "duration") || (mode == "duration" && durationMs !in 1000L..3_600_000L)) return false
        clear()
        if (mode == "end") { endOfTrack = true; player.pauseAtEndOfMediaItems = true }
        if (mode == "duration") {
            deadline = SystemClock.elapsedRealtime() + durationMs
            handler.postDelayed(expiration, durationMs)
        }
        publishState()
        return true
    }

    private fun publishState() = publish(Bundle().apply {
        putLong(SleepCommands.DEADLINE, deadline)
        putBoolean(SleepCommands.END_OF_TRACK, endOfTrack)
    })

    private fun clear() {
        handler.removeCallbacks(expiration)
        deadline = 0L; endOfTrack = false
        player.pauseAtEndOfMediaItems = false
        publishState()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (endOfTrack && !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) clear()
    }

    fun close() { clear(); player.removeListener(this) }
}
