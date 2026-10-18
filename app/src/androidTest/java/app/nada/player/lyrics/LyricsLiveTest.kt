package app.nada.player.lyrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.data.Track
import app.nada.player.youtube.YouTubeCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in provider integration: fetch real lyrics, convert kanji, then reopen the persistent cache. */
@RunWith(AndroidJUnit4::class)
class LyricsLiveTest {
    @Test fun realEnglishAndJapaneseLyricsRemainAvailableFromCache() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLyrics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LyricsRepository(context)
        val english = Track("youtube:lBOnj7sFjRc", "The Lazy Song", "Bruno Mars", "", 190, "YouTube Music", "https://www.youtube.com/watch?v=lBOnj7sFjRc", "youtube")
        val englishLyrics = repository.load(english).first
        assertNotNull("English lyrics were unavailable", englishLyrics)
        assertTrue("Expected time-synced English lyrics", englishLyrics!!.synced)
        val results = YouTubeCatalog.search("YOASOBI Yoru ni kakeru")
        val japanese = results.firstOrNull { it.artist.equals("YOASOBI", true) && it.duration in 255..265 &&
            (JapaneseRomaji.romanize(it.title) ?: it.title).replace(" ", "").equals("yorunikakeru", true) }
        assertNotNull("Matching Japanese studio track was not found", japanese)
        val lyrics = repository.load(japanese!!).first
        assertNotNull("Japanese lyrics were unavailable", lyrics)
        assertTrue("Expected time-synced Japanese lyrics", lyrics!!.synced)
        assertTrue("Japanese text was not romanized", lyrics.romanized)
        assertTrue(lyrics.lines.any { it.romaji != null })
        val cached = LyricsRepository(context).load(japanese)
        assertTrue("Reopened repository did not read cached lyrics", cached.second)
        assertEquals(lyrics, cached.first)
        context.openFileOutput("lyrics-live-selected.json", 0).use { it.write(japanese.toJson().toString().toByteArray()) }
        android.util.Log.i("NadaLyricsTest", "Track=${japanese.id}; title=${japanese.title}; duration=${japanese.duration}; lines=${lyrics.lines.size}; romaji=${lyrics.lines.count { it.romaji != null }}; cache=true")
    }
}
