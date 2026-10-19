package app.nada.player.lyrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.data.Track
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses retained recording identities; it never substitutes a new search result. */
@RunWith(AndroidJUnit4::class)
class LyricsTimingLiveTest {
    @Test fun calibratedSourcesSurviveFreshFetchAndRawCacheReload() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLyricsTiming") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val records = File(context.noBackupFilesDir, "lyrics-audit-catalog.json").bufferedReader()
            .use { JsonParser.parseReader(it).asJsonArray }
        val expected = mapOf("yoru" to (36263563L to 1700L), "lemon" to (34873437L to 1220L),
            "refrain" to (3691064L to 6580L), "kangen" to (21914365L to 15140L),
            "dan" to (4662600L to 16540L), "yangterdalam" to (3000148L to 28020L))
        val repository = LyricsRepository(context)
        val results = mutableListOf<Map<String, Any>>()
        try {
            for (record in records) {
                val json = record.asJsonObject
                val key = json["key"].asString
                val track = Gson().fromJson(json["track"], Track::class.java)
                val fresh = requireNotNull(repository.load(track, refresh = true).first)
                val cached = repository.load(track)
                assertTrue("$key uses timed lyrics", fresh.synced)
                assertEquals("$key source", expected.getValue(key).first, fresh.id)
                // The Lemon source includes a title/artist credit before its first sung row.
                val vocal = fresh.lines.filter { it.text.isNotBlank() }[if (key == "lemon") 1 else 0]
                assertEquals("$key opening vocal", expected.getValue(key).second, vocal.timeMs)
                assertTrue("$key cached", cached.second)
                assertEquals("$key cannot accumulate an offset", fresh, cached.first)
                results += mapOf("key" to key, "track" to track, "lyrics" to fresh, "cached" to cached.second)
                android.util.Log.i("NadaLyricsTiming", "PASS $key source=${fresh.id} firstVocalMs=${vocal.timeMs}; fresh/cache identical")
            }
            assertEquals(expected.size, results.size)
            File(context.noBackupFilesDir, "lyrics-acoustic-calibrated.json").writeText(Gson().toJson(results))
        } finally { repository.close() }
    }
}
