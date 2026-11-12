package app.nada.player.playback

import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.data.Track
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackQueueTraversalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engines = mutableListOf<ExoPlayer>()
    private val track = Track("repeat123", "Repeated recording", "Singer", "", 180, "Pop")
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun engine(size: Int): ExoPlayer = ExoPlayer.Builder(context).build().also { player ->
        engines += player
        player.setMediaItems((0 until size).map { track.toMediaItem(entryId = "entry-$it") })
    }

    private class ObservedPlayer(player: Player) : ForwardingPlayer(player) {
        var nextReads = 0
        var metadataReads = 0
        override fun getCurrentTimeline(): Timeline = object : ForwardingTimeline(super.getCurrentTimeline()) {
            override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
                nextReads++
                return super.getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
            }
        }
        override fun getMediaItemAt(index: Int): MediaItem {
            metadataReads++
            return super.getMediaItemAt(index)
        }
    }

    @After fun cleanup() = main { engines.forEach(ExoPlayer::release) }

    @Test fun refillInspectionOfSixHundredItemsReadsOnlyFiveIndexesAndNoTrackMetadata() = main {
        val player = engine(600)
        val observed = ObservedPlayer(player)
        assertEquals(listOf(0, 1, 2, 3, 4), observed.upcomingQueueIndexes(limit = 5))
        assertEquals(4, observed.nextReads)
        assertEquals(0, observed.metadataReads)

        player.seekToDefaultPosition(596)
        assertEquals(listOf(596, 597, 598, 599), observed.upcomingQueueIndexes(limit = 5))
        assertEquals(0, observed.metadataReads)
    }

    @Test fun boundedTraversalKeepsShuffleOrderAndStopsAtTheSameRepeatBoundaryAsTheFullQueue() = main {
        val player = engine(6)
        player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(3, 1, 5, 0, 4, 2), 1L))
        player.shuffleModeEnabled = true
        player.seekToDefaultPosition(1)
        for (repeatMode in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) {
            player.repeatMode = repeatMode
            val expected = if (repeatMode == Player.REPEAT_MODE_ALL) listOf(1, 5, 0, 4, 2, 3) else listOf(1, 5, 0, 4, 2)
            assertEquals(expected, player.upcomingQueue().map { it.mediaIndex })
            assertEquals(expected.take(3), player.upcomingQueueIndexes(limit = 3))
            assertEquals(expected, player.upcomingQueueIndexes(limit = 20))
        }
    }

    @Test fun repeatAllVisitsDuplicateRecordingOccurrencesOnceAndClearHasNoUpcomingItems() = main {
        val player = engine(3)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.seekToDefaultPosition(2)
        val queue = player.upcomingQueue()
        assertEquals(listOf("entry-2", "entry-0", "entry-1"), queue.map { it.entryId })
        assertEquals(listOf(track.id, track.id, track.id), queue.map { it.track.id })
        assertEquals(listOf(2, 0, 1), player.upcomingQueueIndexes(limit = 5))
        player.clearMediaItems()
        assertTrue(player.upcomingQueueIndexes(limit = 5).isEmpty())
        assertTrue(player.upcomingQueue().isEmpty())
    }
}
