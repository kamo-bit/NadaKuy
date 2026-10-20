package app.nada.player.recommendations

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import app.nada.player.data.CatalogCache
import app.nada.player.data.CatalogQuery
import app.nada.player.data.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.File

class CatalogCacheTest {
    @get:Rule val folder = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private val key = CatalogQuery("youtube", false, "artist:Singer")
    private val song = Track("youtube:abcdefghijk", "Song", "Singer", "https://example.org/album.jpg", 180, "YouTube Music", source = "youtube")

    @Test fun reopeningUsesDiskWithoutFetchingAndExplicitRefreshReplacesIt() = runBlocking {
        val directory = folder.newFolder()
        var calls = 0
        val cold = android.os.SystemClock.elapsedRealtime()
        CatalogCache(directory).load(key) { calls++; delay(150); listOf(song) }
        val coldMs = android.os.SystemClock.elapsedRealtime() - cold
        val warm = android.os.SystemClock.elapsedRealtime()
        val reopened = CatalogCache(directory).load(key) { error("Fresh disk data should not fetch") }
        val warmMs = android.os.SystemClock.elapsedRealtime() - warm
        assertEquals(listOf(song), reopened)
        val next = song.copy(id = "youtube:12345678901", title = "New Song")
        var visible = emptyList<Track>()
        assertEquals(listOf(next), CatalogCache(directory).load(key, refresh = true, cached = { visible = it }) { calls++; listOf(next) })
        assertEquals(listOf(song), visible)
        assertEquals(2, calls)
        assertEquals(listOf(next), CatalogCache(directory).load(key) { error("Unexpected fetch") })
        File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "catalog-cache-metrics.txt")
            .writeText("Controlled fetch delay=150ms; cold=${coldMs}ms; reopened=${warmMs}ms; reopenedFetches=0")
    }

    @Test fun expiredOrFailedRefreshPublishesSavedSongsAndPreservesThemForNextOpen() = runBlocking {
        val directory = folder.newFolder()
        var now = 1_000_000L
        CatalogCache(directory) { now }.load(key) { listOf(song) }
        now += CatalogCache.FRESH_MS
        var visible = emptyList<Track>()
        try {
            CatalogCache(directory) { now }.load(key, cached = { visible = it }) { throw IOException("Offline") }
            fail("Network failure should reach the caller")
        } catch (_: IOException) { }
        assertEquals(listOf(song), visible)
        var saved = emptyList<Track>()
        try {
            CatalogCache(directory) { now }.load(key, refresh = true, cached = { saved = it }) { emptyList() }
            fail("Empty refresh should retain the previous usable response")
        } catch (_: IOException) { }
        assertEquals(listOf(song), saved)
    }

    @Test fun sourceModeAndArtistHaveIndependentEntriesAndEmptyResultsAreRetried() = runBlocking {
        val cache = CatalogCache(folder.newFolder())
        cache.load(key) { listOf(song) }
        var calls = 0
        listOf(key.copy(videos = true), key.copy(query = "artist:Other"), key.copy(source = "audius")).forEach { query ->
            repeat(2) { assertTrue(cache.load(query) { calls++; emptyList() }.isEmpty()) }
        }
        assertEquals(6, calls)
        assertEquals(listOf(song), cache.load(key) { error("Unexpected fetch") })
    }

    @Test fun cancelledRequestCannotReplaceSavedMetadata() = runBlocking {
        val directory = folder.newFolder()
        val cache = CatalogCache(directory)
        cache.load(key) { listOf(song) }
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            cache.load(key, refresh = true) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await(); job.cancelAndJoin()
        assertEquals(listOf(song), CatalogCache(directory).load(key) { error("Cancelled request must preserve cache") })
    }

    @Test fun corruptOversizedFutureAndObsoleteFilesAreTreatedAsMisses() = runBlocking {
        val directory = folder.newFolder()
        var now = 1000L
        val cache = CatalogCache(directory) { now }
        cache.load(key) { listOf(song) }
        val file = directory.listFiles()!!.single()
        file.writeText("{")
        var calls = 0
        cache.load(key) { calls++; listOf(song) }
        file.writeBytes(ByteArray(300 * 1024))
        cache.load(key) { calls++; listOf(song) }
        now = 999
        cache.load(key) { calls++; listOf(song) }
        now += 8 * 24 * 60 * 60 * 1000L
        var usedObsolete = false
        cache.load(key, cached = { usedObsolete = true }) { calls++; listOf(song) }
        assertFalse(usedObsolete)
        assertEquals(4, calls)
    }

    @Test fun cacheEvictsOldEntriesAndCannotFillTheDiskWithArtistQueries() = runBlocking {
        val directory = folder.newFolder()
        val cache = CatalogCache(directory)
        repeat(20) { cache.load(key.copy(query = "artist:$it")) { listOf(song) } }
        assertTrue(directory.listFiles()!!.size <= 12)
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 12L * 256 * 1024)
    }
}
