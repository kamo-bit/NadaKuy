package app.nada.player.recommendations

import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.youtube.YouTubeCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Exercises the actual NewPipe continuation boundary, separately from decoder tests. */
class RadioPagesLiveTest {
    @Test fun anonymousMixFollowsItsUpstreamPages() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRadioPages") == "true")
        val report = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "radio-pages-live.txt")
        val evidence = mutableListOf<String>()
        for (query in listOf("Dewa 19 Kangen", "Bruno Mars The Lazy Song")) {
            val seed = YouTubeCatalog.search(query).first()
            val pages = YouTubeCatalog.radioPages(seed)
            val seen = hashSetOf<String>()
            repeat(3) { index ->
                val page = pages.next()
                val fresh = page.orEmpty().count { seen.add(it.id) }
                evidence += "$query; page=${index + 1}; size=${page?.size}; new=$fresh; unique=${seen.size}"
                report.writeText(evidence.joinToString("\n"))
                assertNotNull("Upstream supplied no page ${index + 1} for $query; see report", page)
                assertTrue("Page ${index + 1} added no distinct tracks for $query", fresh > 0)
            }
        }
    }
}
