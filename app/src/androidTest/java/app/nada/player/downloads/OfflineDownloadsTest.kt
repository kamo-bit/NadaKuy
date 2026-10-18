package app.nada.player.downloads

import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import app.nada.player.data.Track
import app.nada.player.playback.createMusicPlayback
import app.nada.player.playback.toMediaItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class OfflineDownloadsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(60)
        }
        fail(message)
    }
    private fun fixture(): File {
        val bytes = 44_100 * 2 * 2 * 3
        val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(2).putInt(44_100).putInt(176_400).putShort(4).putShort(16)
        wav.put("data".toByteArray()).putInt(bytes)
        return File.createTempFile("offline-test-", ".wav", context.cacheDir).apply { writeBytes(wav.array()) }
    }
    private fun track(id: String) = Track("youtube:$id", "Offline test", "NadaKuy", "", 3, "YouTube Music", "https://www.youtube.com/watch?v=$id", "youtube")
    private fun submit(library: OfflineLibrary, track: Track, file: File, metadata: ByteArray = track.toJson().toString().toByteArray()) {
        val request = DownloadRequest.Builder(track.id, file.toUri()).setCustomCacheKey(track.id)
            .setMimeType(MimeTypes.AUDIO_WAV).setData(metadata).build()
        main { DownloadService.sendAddDownload(context, OfflineDownloadService::class.java, request, true) }
        await("Fixture download did not complete") { library.state.value.tracks.any { it.id == track.id && it.status == OfflineStatus.COMPLETE } }
    }
    private fun remove(library: OfflineLibrary, id: String) {
        main { library.remove(id) }
        await("Download index entry was not removed") { library.state.value.tracks.none { it.id == id } }
        assertEquals(0L, library.cache.getCachedBytes(id, 0, Long.MAX_VALUE))
    }

    @Test fun serviceStoresExactBytesAndRealPlayerReadsTheOfflineCopy() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val file = fixture()
        lateinit var library: OfflineLibrary
        main { library = OfflineLibrary.get(context) }
        val track = track("NadaKuy001A")
        val preferences = context.getSharedPreferences("nada_library", 0).all.toMap()
        try {
            await("Offline index did not initialize") { library.state.value.loaded }
            submit(library, track, file)
            assertTrue(library.state.value.tracks.first { it.id == track.id }.ready)
            val blockedNetwork = DataSource.Factory {
                object : BaseDataSource(false) {
                    override fun open(dataSpec: DataSpec): Long = throw IOException("Offline playback attempted to use upstream")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("Unexpected upstream read")
                    override fun getUri(): android.net.Uri? = null
                    override fun close() = Unit
                }
            }
            val cached = CacheDataSource.Factory().setCache(library.cache).setUpstreamDataSourceFactory(blockedNetwork)
                .setCacheWriteDataSinkFactory(null).createDataSource()
            val expected = file.readBytes()
            val output = java.io.ByteArrayOutputStream()
            try {
                cached.open(DataSpec.Builder().setUri(track.toMediaItem().localConfiguration!!.uri).setKey(track.id).build())
                val buffer = ByteArray(8192)
                while (true) { val count = cached.read(buffer, 0, buffer.size); if (count == -1) break; output.write(buffer, 0, count) }
            } finally { cached.close() }
            assertArrayEquals(expected, output.toByteArray())
            assertTrue(file.delete())

            val errors = CopyOnWriteArrayList<String>()
            lateinit var playback: app.nada.player.playback.MusicPlayback
            main {
                playback = createMusicPlayback(context)
                playback.engine.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { errors += error.errorCodeName }
                })
                playback.player.setMediaItem(track.toMediaItem()); playback.player.prepare(); playback.player.play()
            }
            try {
                await("Real player did not advance through the downloaded media: $errors") {
                    var position = 0L
                    main { position = playback.player.currentPosition }
                    errors.isEmpty() && position > 1000
                }
                assertTrue(errors.toString(), errors.isEmpty())
            } finally { main { playback.player.release() } }
            remove(library, track.id)
            assertEquals(preferences, context.getSharedPreferences("nada_library", 0).all)
        } finally {
            if (library.state.value.tracks.any { it.id == track.id }) remove(library, track.id)
            file.delete(); scenario.close()
        }
    }

    @Test fun missingMediaCanBeDownloadedAgainAndMalformedMetadataRemainsRemovable() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val file = fixture()
        lateinit var library: OfflineLibrary
        main { library = OfflineLibrary.get(context) }
        val track = track("NadaKuy002A")
        try {
            await("Offline index did not initialize") { library.state.value.loaded }
            submit(library, track, file)
            library.cache.removeResource(track.id)
            library.refresh()
            await("Missing media was incorrectly advertised as ready") { library.state.value.tracks.any { it.id == track.id && it.missingMedia && !it.ready } }
            submit(library, track, file)
            assertTrue(library.state.value.tracks.first { it.id == track.id }.ready)
            remove(library, track.id)
            submit(library, track, file, "invalid metadata".toByteArray())
            val malformed = library.state.value.tracks.first { it.id == track.id }
            assertNull(malformed.track)
            assertFalse(malformed.ready)
            remove(library, track.id)
        } finally {
            if (library.state.value.tracks.any { it.id == track.id }) remove(library, track.id)
            file.delete(); scenario.close()
        }
    }
}
