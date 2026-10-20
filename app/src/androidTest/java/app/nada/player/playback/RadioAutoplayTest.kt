package app.nada.player.playback

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import app.nada.player.data.Track
import app.nada.player.recommendations.RecommendationSettings
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class RadioAutoplayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "test-radio-${UUID.randomUUID()}"
    private lateinit var settings: RecommendationSettings
    private lateinit var playback: MusicPlayback
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var audio: File
    private var radio: RadioAutoplay? = null
    private val pending = mutableListOf<CompletableDeferred<Unit>>()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun await(label: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < end) {
            var done = false; main { done = predicate() }
            if (done) return
            Thread.sleep(50)
        }
        fail("Timed out: $label")
    }
    private fun song(id: String, artist: String = "Singer") = Track(id, id, artist, "", 180, "Pop")
    private fun item(track: Track, token: String?, automatic: Boolean) = track.toMediaItem(token, automatic).buildUpon().setUri(audio.toUri()).build()
    private fun attach(loader: suspend (Track) -> List<Track>, publish: (Bundle) -> Unit = {}) = main {
        radio = RadioAutoplay(playback.player, settings, { loader(it.frontier) }, publish, ::item)
    }
    @Before fun setup() {
        val bytes = 16_000 * 2 * 10
        val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16_000).putInt(32_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
        audio = File(context.cacheDir, "radio-test.wav").also { it.writeBytes(buffer.array()) }
        settings = RecommendationSettings(context, name)
        activity = ActivityScenario.launch(MainActivity::class.java)
        main { playback = createMusicPlayback(context) }
    }
    @After fun cleanup() {
        main { radio?.close(); playback.player.release() }
        pending.forEach { it.complete(Unit) }
        activity.close(); context.deleteSharedPreferences(name); audio.delete()
    }
    @Test fun radioRefillsAndReallyAdvancesToTheNextDecodedTrack() {
        val next = song("radioNext")
        attach({ listOf(next, song("radioThird"), song("radioFourth")) })
        main { radio!!.start(song("radioSeed")) }
        await("ready queue") { playback.engine.isPlaying && playback.engine.mediaItemCount == 4 }
        main { assertTrue(playback.engine.getMediaItemAt(1).mediaMetadata.extras!!.getBoolean(RadioCommands.AUTOMATIC)); playback.engine.seekTo(9800) }
        await("automatic transition") { playback.engine.currentMediaItem?.mediaId == next.id && playback.engine.isPlaying }
    }
    @Test fun aLateRequestCannotAppendAfterTheUserReplacesTheQueue() {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        pending += release
        attach({ seed -> if (seed.id == "oldSeed") withContext(NonCancellable) { started.complete(Unit); release.await(); listOf(song("late")) } else emptyList() })
        main { radio!!.start(song("oldSeed")) }
        runBlocking { withTimeout(12_000) { started.await() } }
        main { playback.player.setMediaItem(item(song("newSeed"), "new-queue", false)); playback.player.prepare() }
        await("new queue") { playback.player.currentMediaItem?.mediaId == "newSeed" }
        release.complete(Unit)
        await("old work settled") { playback.player.playbackState == Player.STATE_READY }
        Thread.sleep(200)
        main { assertEquals(1, playback.player.mediaItemCount); assertEquals("newSeed", playback.player.currentMediaItem?.mediaId) }
    }
    @Test fun disablingAutoplayCancelsPendingRefillWithoutResumingOrReplacingTheSong() {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        pending += release
        attach({ withContext(NonCancellable) { started.complete(Unit); release.await(); listOf(song("late")) } })
        main { radio!!.start(song("seed")) }
        runBlocking { withTimeout(12_000) { started.await() } }
        main { playback.player.pause(); settings.setAutoplay(false) }
        release.complete(Unit)
        Thread.sleep(300)
        main { assertEquals(1, playback.player.mediaItemCount); assertFalse(playback.player.playWhenReady) }
    }
    @Test fun blockingAnArtistRemovesOnlyAutomaticFutureSongsAndPreservesExplicitChoices() {
        attach({ emptyList() })
        main {
            settings.setAutoplay(true)
            playback.player.setMediaItems(listOf(item(song("seed"), "queue", false), item(song("manual", "Other"), "queue", false), item(song("auto", "Other"), "queue", true)))
            playback.player.prepare()
        }
        await("playlist ready") { playback.player.playbackState == Player.STATE_READY }
        main { settings.toggleArtist("Other - Topic") }
        await("blocked automatic song removed") { playback.player.mediaItemCount == 2 }
        main { assertEquals("manual", playback.player.getMediaItemAt(1).mediaId); assertEquals("seed", playback.player.currentMediaItem?.mediaId) }
    }
    @Test fun repeatModeCancelsARefillAndTurningRepeatOffCanTryAgain() {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        pending += release
        var calls = 0
        attach({ calls++; if (calls == 1) withContext(NonCancellable) { started.complete(Unit); release.await() }; listOf(song("next"), song("third"), song("fourth")) })
        main { radio!!.start(song("seed")) }
        runBlocking { withTimeout(12_000) { started.await() } }
        main { playback.player.repeatMode = Player.REPEAT_MODE_ONE }
        release.complete(Unit)
        Thread.sleep(200)
        main { assertEquals(1, playback.player.mediaItemCount); playback.player.repeatMode = Player.REPEAT_MODE_OFF }
        await("repeat off refills") { playback.player.mediaItemCount > 1 }
    }
    @Test fun radioHistoryIsBoundedWithoutDeletingManualPlaylistItems() {
        attach({ listOf(song("next"), song("third"), song("fourth")) })
        main {
            settings.setAutoplay(true)
            val items = listOf(item(song("manual"), "queue", false)) +
                (0 until 25).map { item(song("auto$it"), "queue", true) } + item(song("current"), "queue", false)
            playback.player.setMediaItems(items, items.lastIndex, 0)
            playback.player.prepare()
        }
        await("new radio batch") { playback.player.mediaItemCount == 25 }
        main {
            assertEquals("manual", playback.player.getMediaItemAt(0).mediaId)
            assertEquals("current", playback.player.currentMediaItem?.mediaId)
            assertEquals(20, (0 until playback.player.currentMediaItemIndex).count { playback.player.getMediaItemAt(it).mediaMetadata.extras!!.getBoolean(RadioCommands.AUTOMATIC) })
        }
    }
    @Test fun refillRetainsTheOriginalStationWhenTheQueueReachesAnotherArtist() {
        val requests = mutableListOf<app.nada.player.recommendations.RadioRequest>()
        main {
            radio = RadioAutoplay(playback.player, settings, { request ->
                requests += request
                (1..3).map { song("next${requests.size}x$it", "Artist$it") }
            }, {}, ::item)
            radio!!.start(song("original"))
        }
        await("initial batch") { playback.player.mediaItemCount == 4 }
        main { playback.player.seekTo(2, 0) }
        await("second batch") { requests.size == 2 && playback.player.mediaItemCount == 7 }
        main {
            assertEquals(listOf("original", "original"), requests.map { it.anchor.id })
            assertEquals("next1x3", requests.last().frontier.id)
            assertEquals(listOf("original", "next1x1", "next1x2", "next1x3"), requests.last().recent.map { it.id })
        }
    }
}
