package app.nada.player.recommendations

import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.youtube.YouTubeCatalog
import app.nada.player.youtube.YouTubeSearchRanking
import app.nada.player.NadaApplication
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RadioDiversityLiveTest {
    @Test fun anonymousMusicMixesContainRelatedTracksFromMultipleArtists() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRadioDiversity") == "true")
        val evidence = mutableListOf<String>()
        val app = ApplicationProvider.getApplicationContext<NadaApplication>()
        val catalog = RadioCatalog(app)
        for (query in listOf("Dewa 19 Kangen", "Sheila On 7 Dan", "Bruno Mars The Lazy Song")) {
            val seed = YouTubeCatalog.search(query).first()
            val pool = YouTubeCatalog.radio(seed)
            val artists = pool.map { ListeningRecommendations.artistKey(it.artist) }.distinct()
            evidence += "Seed=${seed.id} | ${seed.title} | ${seed.artist}; candidates=${pool.size}; artists=${artists.size}"
            evidence += pool.map { "Candidate=${it.id} | ${it.title} | ${it.artist}" }
            File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "radio-diversity-live.txt").writeText(evidence.joinToString("\n"))
            assertTrue("No music mix for $query", pool.size >= 8)
            assertTrue("No cross-artist candidates for $query", artists.size >= 3)
            assertTrue("An alternative recording leads $query", YouTubeSearchRanking.originalPreferred(seed.title))
            val coldAt = android.os.SystemClock.elapsedRealtime()
            val songs = catalog.pool(seed, refresh = true)
            val coldMs = android.os.SystemClock.elapsedRealtime() - coldAt
            val warmAt = android.os.SystemClock.elapsedRealtime()
            assertEquals(songs, catalog.pool(seed))
            val warmMs = android.os.SystemClock.elapsedRealtime() - warmAt
            val chosen = RadioSelection.select(songs, seed, listOf(seed), RecommendationPreferences())
            evidence += "Resolved=${songs.size}; selected=${chosen.size}; artists=${chosen.map { ListeningRecommendations.artistKey(it.artist) }.distinct().size}; cold=${coldMs}ms; warm=${warmMs}ms"
            evidence += chosen.map { "Selected=${it.id} | ${it.title} | ${it.artist}" }
            File(app.noBackupFilesDir, "radio-diversity-live.txt").writeText(evidence.joinToString("\n"))
            assertTrue("Too few resolved related songs for $query", chosen.size >= 4)
            assertTrue("Resolved pool lacks artist diversity for $query", chosen.map { ListeningRecommendations.artistKey(it.artist) }.distinct().size >= 3)
            assertTrue("Artist repeats despite alternatives for $query", chosen.zipWithNext().all {
                ListeningRecommendations.artistKey(it.first.artist) != ListeningRecommendations.artistKey(it.second.artist)
            })
        }
    }
}
