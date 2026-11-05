package app.nada.player.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import kotlinx.coroutines.*

/** The service's real engine owns persisted state, including the effective shuffle permutation. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class QueuePersistence(private val player: ExoPlayer, private val store: QueueStore,
    private val station: () -> RadioStation?, private val restoreStation: (RadioStation?) -> Unit,
    private val canRestore: () -> Boolean = { true }, private val restored: () -> Unit = {},
    private val loadQueue: suspend () -> SavedQueue? = store::load) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initializing = true
    private var changedBeforeRestore = false
    private var applying = false
    private var entries = emptyList<SavedQueueEntry>()
    private var order = emptyList<Int>()
    init {
        player.addListener(this)
        scope.launch {
            val saved = loadQueue()
            if (!changedBeforeRestore && player.mediaItemCount == 0 && canRestore() && saved != null) {
                applying = true
                try {
                    player.playWhenReady = false
                    player.setMediaItems(saved.entries.map { it.track.toMediaItem(it.queueId, it.automatic, it.entryId) }, saved.current, saved.positionMs)
                    player.setShuffleOrder(DefaultShuffleOrder(saved.order.toIntArray(), 0))
                    player.shuffleModeEnabled = saved.shuffle
                    player.repeatMode = saved.repeat
                    restoreStation(saved.station)
                } finally { applying = false }
                restored()
            }
            initializing = false
            refreshStructure()
            if (changedBeforeRestore || player.mediaItemCount > 0) checkpoint()
        }
        scope.launch { while (isActive) { delay(2000); if (player.isPlaying) checkpoint() } }
    }
    fun userQueueCommand() { if (initializing && !applying) changedBeforeRestore = true }
    override fun onEvents(player: Player, events: Player.Events) {
        if (applying) return
        if (initializing) { if (events.contains(Player.EVENT_TIMELINE_CHANGED)) changedBeforeRestore = true; return }
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) refreshStructure()
        checkpoint()
    }
    private fun refreshStructure() {
        entries = (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index).let {
            SavedQueueEntry(it.mediaMetadata.extras?.getString(QueueCommands.ENTRY_ID).orEmpty(),
                it.mediaMetadata.extras?.getString(RadioCommands.QUEUE_ID), it.mediaMetadata.extras?.getBoolean(RadioCommands.AUTOMATIC) == true, it.toTrack())
        } }
        order = player.queueIndexes(shuffled = true)
    }
    fun checkpoint() {
        if (initializing || applying) return
        if (player.mediaItemCount == 0) { store.save(null); return }
        val index = player.currentMediaItemIndex
        if (entries.size != player.mediaItemCount || entries.getOrNull(index)?.entryId !=
            player.currentMediaItem?.mediaMetadata?.extras?.getString(QueueCommands.ENTRY_ID)) refreshStructure()
        if (index !in entries.indices) return
        store.save(SavedQueue(entries, order, index, if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0),
            player.shuffleModeEnabled, player.repeatMode, station()))
    }
    fun close() { checkpoint(); player.removeListener(this); scope.cancel() }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun Player.queueIndexes(shuffled: Boolean = shuffleModeEnabled): List<Int> {
    val result = mutableListOf<Int>()
    var index = currentTimeline.getFirstWindowIndex(shuffled)
    while (index != C.INDEX_UNSET && result.size < mediaItemCount) {
        result += index
        index = currentTimeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffled)
    }
    return result
}
