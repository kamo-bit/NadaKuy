package app.nada.player.recommendations

import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.youtube.YouTubeCatalog
import app.nada.player.NadaApplication
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RecommendationsLiveTest {
    @Test fun currentCatalogFindsOriginalCandidatesAndCrossArtistSongsRelatedToHistory() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRecommendations") == "true")
        val evidence = mutableListOf<String>()
        val seeds = listOf("Dewa 19 Kangen", "Sheila On 7 Dan", "Bruno Mars The Lazy Song").map { query ->
            val tracks = YouTubeCatalog.search(query)
            assertTrue("No results for $query", tracks.isNotEmpty())
            val first = tracks.first()
            assertFalse(first.title, Regex("(?i)\\b(cover|karaoke|remix|slowed)\\b").containsMatchIn(first.title))
            evidence += "Query=$query; first=${first.id} | ${first.title} | ${first.artist}"
            first
        }
        val plan = ListeningRecommendations.seeds(seeds.mapIndexed { index, track -> ListeningStat(track, 10 - index, 1) }, emptyList(), "youtube")
        val catalog = RadioCatalog(ApplicationProvider.getApplicationContext<NadaApplication>())
        val pools = plan.map { catalog.pool(it.track) }
        val suggestions = ListeningRecommendations.select(pools, plan, seeds, "youtube")
        assertTrue("Related pools returned no new tracks", suggestions.isNotEmpty())
        assertTrue(suggestions.none { song -> seeds.any { it.id == song.id } })
        assertTrue("Home suggestions are not diverse", suggestions.map { ListeningRecommendations.artistKey(it.artist) }.distinct().size >= 3)
        evidence += suggestions.map { "Suggestion=${it.id} | ${it.title} | ${it.artist}" }
        File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "recommendations-live.txt").writeText(evidence.joinToString("\n"))
    }
}
