package app.nada.player.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.SessionCommand
import app.nada.player.data.Track

data class QueuedTrack(val mediaIndex: Int, val track: Track)

object QueueCommands {
    val playNext = SessionCommand("app.nada.player.PLAY_NEXT", Bundle.EMPTY)
    const val TRACK = "track"
}

fun Track.toMediaItem(): MediaItem {
    check(playableInNada) { "Unsupported playback identity" }
    val uri = if (source == "youtube") "nada-youtube://watch/${id.removePrefix("youtube:")}" else streamUrl
    return MediaItem.Builder().setMediaId(id).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist)
        .setArtworkUri(artwork.takeIf(String::isNotBlank)?.toUri())
        .setExtras(Bundle().apply { putInt("duration", duration); putString("genre", genre); putString("permalink", permalink); putString("source", source) }).build()).build()
}

fun MediaItem.toTrack(): Track = Track(mediaId, mediaMetadata.title?.toString() ?: "Tanpa judul",
    mediaMetadata.artist?.toString() ?: "Artis", mediaMetadata.artworkUri?.toString() ?: "",
    mediaMetadata.extras?.getInt("duration") ?: 0, mediaMetadata.extras?.getString("genre") ?: "",
    mediaMetadata.extras?.getString("permalink") ?: "", mediaMetadata.extras?.getString("source") ?: "audius")

fun Player.togglePlayback() {
    if (isPlaying) pause() else {
        if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
        if (playerError != null) prepare()
        play()
    }
}

fun Player.playQueueItem(mediaIndex: Int) {
    if (mediaIndex !in 0 until mediaItemCount) return
    seekToDefaultPosition(mediaIndex)
    if (playerError != null) prepare()
    play()
}

/** Current item and upcoming playback order, with each queue entry appearing once. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
fun Player.upcomingQueue(): List<QueuedTrack> {
    if (currentTimeline.isEmpty) return emptyList()
    val indexes = mutableListOf<Int>()
    var index = currentMediaItemIndex
    val queueRepeat = if (repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    while (index != C.INDEX_UNSET && index !in indexes) {
        indexes += index
        index = currentTimeline.getNextWindowIndex(index, queueRepeat, shuffleModeEnabled)
    }
    return indexes.map { QueuedTrack(it, getMediaItemAt(it).toTrack()) }
}

/** The service owns both insertion and the shuffle permutation used by ExoPlayer. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
fun ExoPlayer.insertNext(item: MediaItem) {
    val insertion = currentMediaItemIndex + 1
    val shuffleOrder = mutableListOf<Int>()
    if (shuffleModeEnabled) {
        var index = currentTimeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET) {
            shuffleOrder += index
            index = currentTimeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
    }
    val afterCurrent = shuffleOrder.indexOf(currentMediaItemIndex) + 1
    addMediaItem(insertion, item)
    if (shuffleModeEnabled) {
        val shifted = shuffleOrder.map { if (it >= insertion) it + 1 else it }.toMutableList()
        shifted.add(afterCurrent, insertion)
        setShuffleOrder(DefaultShuffleOrder(shifted.toIntArray(), System.nanoTime()))
    }
}
