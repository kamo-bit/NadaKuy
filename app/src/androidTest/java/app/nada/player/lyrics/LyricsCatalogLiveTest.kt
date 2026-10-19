package app.nada.player.lyrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LyricsCatalogLiveTest {
    @Test fun japaneseAndIndonesianRecordingsHaveTimedLyrics() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLyricsCatalog") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LyricsRepository(context)
        val results = mutableListOf<Map<String, Any>>()
        val errors = mutableListOf<String>()
        try {
            for (sample in lyricsSamples) {
                try {
                    val track = sample.find()
                    val (document, cached) = repository.load(track)
                    checkNotNull(document) { "No matching lyrics" }
                    check(document.synced) { "No synced lyrics for recording" }
                    val entry = mapOf("key" to sample.key, "track" to track, "lyrics" to document, "cached" to cached)
                    results += entry
                    android.util.Log.i("NadaLyricsAudit", "${sample.key}: ${track.id}; ${track.title}; ${track.artist}; duration=${track.duration}; lyricDuration=${document.duration}; lines=${document.lines.size}; romaji=${document.lines.count { it.romaji != null }}")
                } catch (failure: Exception) {
                    errors += "${sample.key}: ${failure.message}"
                    android.util.Log.e("NadaLyricsAudit", errors.last())
                }
            }
            File(context.noBackupFilesDir, "lyrics-audit-catalog.json").writeText(Gson().toJson(results))
            assertTrue(errors.joinToString("\n"), errors.isEmpty())
            assertEquals(lyricsSamples.size, results.size)
        } finally { repository.close() }
    }
}
