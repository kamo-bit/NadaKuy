package app.nada.player.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.ByteArrayDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.data.Track
import app.nada.player.recommendations.RecommendationSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class QueuePersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "queue-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val stores = mutableListOf<QueueStore>()
    private val engines = mutableListOf<ExoPlayer>()
    private val attachments = mutableListOf<QueuePersistence>()
    private val seed = Track("seed123", "Seed", "Original", "", 180, "Pop")
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun engine(): ExoPlayer {
        val size = 16_000 * 2 * 60
        val wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            putInt(16_000).putInt(32_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        }.array()
        return ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory { ByteArrayDataSource(wav) }).build().also { engines += it }
    }
    private fun store() = QueueStore(directory).also { stores += it }
    private fun await(label: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            var done = false; main { done = condition() }; if (done) return; Thread.sleep(25)
        }
        fail("Timed out: $label")
    }
    private fun snapshot() = SavedQueue((0..3).map {
        SavedQueueEntry("occurrence-$it", "station", it > 0, seed.copy(id = if (it == 3) seed.id else "song$it", artist = "Artist$it"))
    }, listOf(2, 0, 3, 1), 3, 12_345, true, Player.REPEAT_MODE_ALL, RadioStation(seed, "station", "Radio · Seed"))
    @After fun clean() {
        main { attachments.forEach { it.close() }; engines.forEach { it.release() } }
        runBlocking { withTimeout(10_000) { stores.forEach { it.close() } } }
        directory.deleteRecursively()
    }
    @Test fun storeReopensWithLatestCheckpointWithoutRewritingThePlaylist() = runBlocking {
        val store = store(); val saved = snapshot()
        store.save(saved); store.awaitWrites()
        val structure = File(directory, "queue.json").readBytes()
        store.save(saved.copy(current = 1, positionMs = 52_000)); store.awaitWrites()
        assertArrayEquals(structure, File(directory, "queue.json").readBytes())
        assertEquals(saved.copy(current = 1, positionMs = 52_000), store().load())
        store.save(null); store.awaitWrites()
        assertNull(store().load())
        assertFalse(File(directory, "queue.json").exists())
    }
    @Test fun mismatchedCheckpointCannotApplyToAnotherQueueAndMalformedDataIsReported() = runBlocking {
        val store = store(); val saved = snapshot()
        store.save(saved); store.awaitWrites()
        File(directory, "position.json").writeText("""{"revision":"other","entryId":"occurrence-0","positionMs":999}""")
        assertEquals(saved, store().load())
        File(directory, "queue.json").writeText("""{"version":100}""")
        val damaged = store(); assertNull(damaged.load()); assertNotNull(damaged.error.value)
        damaged.save(saved); damaged.awaitWrites()
        assertNull(damaged.error.value); assertEquals(saved, store().load())
    }
    @Test fun failedStorageKeepsLiveMemoryAndReportsFailureWithoutThrowing() = runBlocking {
        val blocked = File(directory, "blocked").also { it.writeText("a file, not a directory") }
        val store = QueueStore(blocked).also { stores += it }
        store.save(snapshot()); store.awaitWrites()
        assertNotNull(store.error.value); assertEquals(snapshot(), store.load())
        blocked.delete(); blocked.mkdirs()
        store.save(snapshot().copy(positionMs = 44_000)); store.awaitWrites()
        assertNull(store.error.value)
    }
    @Test fun unsupportedExternalQueueCannotBeRestoredAsAnAppSong() = runBlocking {
        val store = store()
        store.save(snapshot()); store.awaitWrites()
        store.save(snapshot().copy(entries = snapshot().entries.map { it.copy(entryId = "") })); store.awaitWrites()
        assertNotNull(store.error.value); assertNull(store().load())
    }
    @Test fun serviceRestoresExactShufflePositionAndStationWhileRemainingIdleAndPaused() {
        val disk = store(); val saved = snapshot()
        runBlocking { disk.save(saved); disk.awaitWrites() }
        lateinit var player: ExoPlayer
        var station: RadioStation? = null
        var restored = false
        main {
            player = engine()
            attachments += QueuePersistence(player, store(), { station }, { station = it }, restored = { restored = true })
        }
        await("restore") { restored }
        main {
            assertEquals(saved.entries.map { it.entryId }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.extras!!.getString(QueueCommands.ENTRY_ID) })
            assertEquals(saved.order, player.queueIndexes(true))
            assertEquals(saved.current, player.currentMediaItemIndex); assertEquals(saved.positionMs, player.currentPosition)
            assertTrue(player.shuffleModeEnabled); assertEquals(saved.repeat, player.repeatMode)
            assertEquals(saved.station, station); assertFalse(player.playWhenReady); assertEquals(Player.STATE_IDLE, player.playbackState)
            assertEquals(listOf(true, true, true, false), player.upcomingQueue().map { it.automatic })
        }
    }
    @Test fun aUserReplacementOrClearWinsOverALateRestore() {
        val gate = CompletableDeferred<SavedQueue?>()
        lateinit var player: ExoPlayer
        lateinit var persistence: QueuePersistence
        main {
            player = engine()
            persistence = QueuePersistence(player, store(), { null }, {}, loadQueue = { gate.await() }).also { attachments += it }
            persistence.userQueueCommand()
            player.setMediaItem(seed.toMediaItem())
            player.clearMediaItems()
        }
        gate.complete(snapshot())
        Thread.sleep(150)
        main { assertEquals(0, player.mediaItemCount); assertFalse(player.playWhenReady) }
    }
    @Test fun sameSizeReplacementCheckpointReadsTheCurrentEngineIdentity() = runBlocking {
        val store = store()
        main {
            val player = engine()
            val persistence = QueuePersistence(player, store, { null }, {}, loadQueue = { null }).also { attachments += it }
            player.setMediaItems((0..2).map { seed.toMediaItem("old", entryId = "old$it") })
            persistence.checkpoint()
            player.setMediaItems((0..2).map { seed.toMediaItem("new", entryId = "new$it") })
            // Radio publishes extras synchronously, before batched Player.onEvents.
            persistence.checkpoint()
        }
        assertEquals(listOf("new0", "new1", "new2"), store.load()!!.entries.map { it.entryId })
    }
    @Test fun spotifySourceAndClosingDuringLoadDoNotResurrectTheLocalQueue() {
        val gate = CompletableDeferred<SavedQueue?>()
        lateinit var closed: ExoPlayer
        lateinit var spotify: ExoPlayer
        main {
            closed = engine(); spotify = engine()
            QueuePersistence(closed, store(), { null }, {}, loadQueue = { gate.await() }).close()
            attachments += QueuePersistence(spotify, store(), { null }, {}, canRestore = { false }, loadQueue = { snapshot() })
        }
        gate.complete(snapshot()); Thread.sleep(150)
        main { assertEquals(0, closed.mediaItemCount); assertEquals(0, spotify.mediaItemCount) }
    }
    @Test fun restoredRadioUsesOriginalSeedAfterAdvancingToAnotherArtist() {
        val settingsName = "queue-radio-${UUID.randomUUID()}"
        lateinit var player: ExoPlayer
        lateinit var radio: RadioAutoplay
        val requests = mutableListOf<app.nada.player.recommendations.RadioRequest>()
        val saved = snapshot().copy(repeat = Player.REPEAT_MODE_OFF, shuffle = false, current = 2, order = listOf(0, 1, 2, 3))
        val disk = store(); runBlocking { disk.save(saved); disk.awaitWrites() }
        main {
            player = engine()
            val settings = RecommendationSettings(context, settingsName).apply { setAutoplay(true) }
            radio = RadioAutoplay(player, settings, { requests += it; emptyList() }, {})
            attachments += QueuePersistence(player, store(), radio::station, radio::restoreStation)
        }
        try {
            await("radio restore") { player.mediaItemCount == 4 }
            main { player.prepare() }
            await("radio request") { requests.isNotEmpty() }
            main {
                assertEquals(seed, requests.first().anchor)
                assertEquals(saved.entries[3].track, requests.first().frontier)
            }
        } finally { main { radio.close() }; context.deleteSharedPreferences(settingsName) }
    }
    @Test fun everyFutureMovePreservesCurrentOccurrencePositionAndOtherRelativeOrder() {
        main {
            val player = engine()
            for (shuffle in listOf(false, true)) for (repeat in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL))
                for (current in 0..4) for (from in 1..4) for (to in 1..4) {
                    val items = (0..4).map { seed.toMediaItem(entryId = "entry$it") }
                    player.setMediaItems(items, current, 12_345)
                    player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(3, 1, 4, 0, 2), 0))
                    player.shuffleModeEnabled = shuffle; player.repeatMode = repeat
                    val before = player.upcomingQueue().map { it.entryId }
                    if (from !in before.indices || to !in before.indices || from == to) continue
                    val expected = before.toMutableList().apply { add(to, removeAt(from)) }
                    assertTrue(player.moveUpcoming(before[from], before[to]))
                    assertEquals("shuffle=$shuffle repeat=$repeat current=$current from=$from to=$to", expected, player.upcomingQueue().map { it.entryId })
                    assertEquals("entry$current", player.currentMediaItem!!.mediaMetadata.extras!!.getString(QueueCommands.ENTRY_ID))
                    assertEquals(12_345L, player.currentPosition)
                    assertFalse(player.moveUpcoming(before[0], before[1]))
                }
        }
    }
}
