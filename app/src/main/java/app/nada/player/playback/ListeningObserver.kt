package app.nada.player.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.nada.player.data.Track
import app.nada.player.recommendations.QualifiedListening

/** The service keeps counting qualified plays when the Activity is no longer on screen. */
internal class ListeningObserver(private val player: Player, private val record: (Track) -> Unit) : Player.Listener {
    private val counter = QualifiedListening()
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable { override fun run() { sample(); schedule() } }
    init { player.addListener(this); sample(); schedule() }
    private fun sample(restart: Boolean = false) {
        counter.sample(player.currentMediaItem?.toTrack(), player.isPlaying, SystemClock.elapsedRealtime(), restart)?.let(record)
    }
    private fun schedule() { handler.removeCallbacks(poll); if (player.isPlaying) handler.postDelayed(poll, 1000) }
    override fun onEvents(player: Player, events: Player.Events) { sample(); schedule() }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { sample(restart = true) }
    fun close() {
        counter.sample(null, false, SystemClock.elapsedRealtime())?.let(record)
        handler.removeCallbacks(poll); player.removeListener(this)
    }
}
