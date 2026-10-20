package app.nada.player.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import app.nada.player.data.Track
import app.nada.player.recommendations.RadioSelection
import app.nada.player.recommendations.RecommendationSettings
import app.nada.player.recommendations.RadioRequest
import app.nada.player.recommendations.ListeningStat
import kotlinx.coroutines.*
import java.util.UUID

object RadioCommands {
    val start = SessionCommand("app.nada.player.RADIO_START", Bundle.EMPTY)
    val retry = SessionCommand("app.nada.player.RADIO_RETRY", Bundle.EMPTY)
    const val QUEUE_ID = "nadaQueueId"
    const val AUTOMATIC = "nadaAutomatic"
    const val TITLE = "radioTitle"
    const val LOADING = "radioLoading"
    const val ERROR = "radioError"
    const val AUTOPLAY = "autoplayEnabled"
}

/** Service-owned refill: a generation owns a queue, and cancelled/late requests cannot append to its replacement. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class RadioAutoplay(private val player: Player, private val preferences: RecommendationSettings,
    private val load: suspend (RadioRequest) -> List<Track>, private val publish: (Bundle) -> Unit,
    private val mediaItem: (Track, String?, Boolean) -> androidx.media3.common.MediaItem = { track, id, automatic -> track.toMediaItem(id, automatic) },
    private val history: () -> List<ListeningStat> = { emptyList() }) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var queueId: String? = null
    private var generation = 0
    private var attempted: String? = null
    private var title: String? = null
    private var anchor: Track? = null
    private var loading = false
    private var error: String? = null
    private var closed = false
    private var repeatMode = player.repeatMode

    init {
        player.addListener(this)
        scope.launch { preferences.state.collect {
            invalidate()
            if (!it.autoplay) title = null
            removeUnwantedAutomatic()
            publishState()
            checkQueue()
        } }
    }

    fun start(track: Track) {
        preferences.setAutoplay(true)
        invalidate()
        queueId = UUID.randomUUID().toString()
        title = track.title
        anchor = track
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        player.setMediaItem(mediaItem(track, queueId, false))
        player.prepare(); player.play()
        publishState()
    }

    fun retry() { invalidate(); publishState(); checkQueue() }

    override fun onEvents(player: Player, events: Player.Events) {
        if (player.repeatMode != repeatMode) { repeatMode = player.repeatMode; invalidate(); publishState() }
        val current = player.currentMediaItem?.mediaMetadata?.extras?.getString(RadioCommands.QUEUE_ID)
        if (current != queueId) { invalidate(); queueId = current; title = null; anchor = player.currentMediaItem?.toTrack(); publishState() }
        if (player.mediaItemCount == 0 && (loading || title != null || error != null || anchor != null)) { invalidate(); title = null; anchor = null; publishState() }
        checkQueue()
    }

    private fun invalidate() {
        generation++; job?.cancel(); job = null; attempted = null; loading = false; error = null
    }

    private fun removeUnwantedAutomatic() {
        for (index in player.mediaItemCount - 1 downTo 0) {
            val item = player.getMediaItemAt(index)
            if (index != player.currentMediaItemIndex && item.mediaMetadata.extras?.getBoolean(RadioCommands.AUTOMATIC) == true &&
                (!preferences.state.value.autoplay || !preferences.state.value.allows(item.toTrack()))) player.removeMediaItem(index)
        }
    }

    private fun checkQueue() {
        if (closed || !preferences.state.value.autoplay || job?.isActive == true || loading || error != null ||
            player.repeatMode != Player.REPEAT_MODE_OFF || player.mediaItemCount == 0 ||
            player.playbackState !in listOf(Player.STATE_READY, Player.STATE_ENDED)) return
        val upcoming = player.upcomingQueue()
        if (upcoming.size > 2) return
        val seed = upcoming.lastOrNull()?.track ?: return
        val origin = anchor ?: player.currentMediaItem?.toTrack()?.also { anchor = it } ?: return
        if (!seed.playableInNada || attempted == seed.id) return
        attempted = seed.id
        val expected = generation
        val token = queueId
        val recent = (maxOf(0, player.mediaItemCount - 10) until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() }
        loading = true; error = null; publishState()
        job = scope.launch {
            try {
                val candidates = load(RadioRequest(origin, seed, recent))
                ensureActive()
                if (closed || generation != expected || token != queueId ||
                    token != player.currentMediaItem?.mediaMetadata?.extras?.getString(RadioCommands.QUEUE_ID) ||
                    player.repeatMode != Player.REPEAT_MODE_OFF || !preferences.state.value.autoplay) return@launch
                // Bound radio history while retaining every manually queued item, including long playlists.
                val obsolete = (0 until player.currentMediaItemIndex).filter {
                    player.getMediaItemAt(it).mediaMetadata.extras?.getBoolean(RadioCommands.AUTOMATIC) == true
                }.dropLast(20)
                obsolete.asReversed().forEach(player::removeMediaItem)
                val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() }
                val accepted = RadioSelection.select(candidates, origin, queue, preferences.state.value, history())
                if (accepted.isEmpty()) error = "Belum ada lagu radio yang cocok. Coba lagi atau pilih lagu awal lain."
                else {
                    val ended = player.playbackState == Player.STATE_ENDED && player.playWhenReady
                    val next = player.mediaItemCount
                    player.addMediaItems(accepted.map { mediaItem(it, token, true) })
                    if (ended) { player.seekToDefaultPosition(next); player.prepare(); player.play() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) error = "Radio belum bisa dimuat. Periksa internet, lalu coba lagi." }
            finally { if (generation == expected) { loading = false; publishState() } }
        }
    }

    private fun publishState() = publish(Bundle().apply {
        putString(RadioCommands.TITLE, title); putString(RadioCommands.ERROR, error)
        putBoolean(RadioCommands.LOADING, loading); putBoolean(RadioCommands.AUTOPLAY, preferences.state.value.autoplay)
    })
    fun close() { closed = true; invalidate(); scope.cancel(); player.removeListener(this) }
}
