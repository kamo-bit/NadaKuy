package app.nada.player.recommendations

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MusicViewModel
import coil3.decode.DataSource
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in: real catalog and image pipeline, using this emulator's existing listening profile. */
class RecommendationCacheLiveTest {
    @Test fun refreshReopenAndAlbumArtworkUseProductionCaches() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRecommendationCache") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var store = ViewModelStore()
        fun create(): MusicViewModel {
            lateinit var model: MusicViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MusicViewModel::class.java] }
            return model
        }
        fun waitFor(model: MusicViewModel, timeout: Long = 90_000, predicate: () -> Boolean) {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                var ready = false
                instrumentation.runOnMainSync { ready = predicate() }
                if (ready) return
                check(SystemClock.elapsedRealtime() - start < timeout) { "Timed out: ${model.state.homeError}; ${model.state.recommendationsError}" }
                SystemClock.sleep(100)
            }
        }
        try {
            var model = create()
            waitFor(model) { !model.state.loading && !model.state.recommendationsLoading }
            assertEquals("youtube", model.state.source)
            assertTrue("Listen to a song before running this live test", model.state.recommendationArtists.isNotEmpty())
            val refreshedAt = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { model.refresh() }
            waitFor(model) { !model.state.homeRefreshing && !model.state.loading && !model.state.recommendationsLoading }
            val networkMs = SystemClock.elapsedRealtime() - refreshedAt
            assertNull(model.state.homeError)
            assertNull(model.state.recommendationsError)
            assertTrue(model.state.recommendations.isNotEmpty())
            val songs = model.state.recommendations
            val size = songs.size
            val more = model.state.recommendationsHaveMore
            if (more) {
                instrumentation.runOnMainSync { model.loadMoreRecommendations() }
                assertTrue(model.state.recommendations.size > size)
                assertEquals(songs, model.state.recommendations.take(size))
            }
            instrumentation.runOnMainSync { store.clear() }
            store = ViewModelStore()
            val reopenedAt = SystemClock.elapsedRealtime()
            model = create()
            waitFor(model, 10_000) { !model.state.loading && !model.state.recommendationsLoading }
            val cachedMs = SystemClock.elapsedRealtime() - reopenedAt
            assertEquals(songs, model.state.recommendations)
            assertTrue(model.state.home.isNotEmpty())

            val loader = app.imageLoader
            val url = songs.first().artwork
            assertTrue(url.startsWith("https://"))
            val small = loader.execute(ImageRequest.Builder(app).data(url).size(96).allowHardware(false).build())
            assertTrue("Album image failed: $small", small is SuccessResult)
            loader.memoryCache?.clear()
            val large = loader.execute(ImageRequest.Builder(app).data(url).size(256).allowHardware(false).build())
            assertTrue("Album image failed: $large", large is SuccessResult)
            large as SuccessResult
            assertEquals(DataSource.DISK, large.dataSource)
            assertTrue(large.image.width <= 544 && large.image.height <= 544)
            val memory = loader.execute(ImageRequest.Builder(app).data(url).size(256).allowHardware(false).build())
            assertTrue(memory is SuccessResult)
            assertEquals(DataSource.MEMORY_CACHE, (memory as SuccessResult).dataSource)
            assertTrue((loader.memoryCache?.maxSize ?: 0) <= 32L * 1024 * 1024)
            File(app.noBackupFilesDir, "recommendation-cache-live.txt").writeText(
                "Actual refresh=${networkMs}ms; reopened fresh cache=${cachedMs}ms; initial=$size; more=$more; artwork=${large.dataSource}; repeat=${memory.dataSource}")
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }
}
