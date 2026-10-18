package app.nada.player.lyrics

import org.junit.Assert.*
import org.junit.Test

class JapaneseRomajiTest {
    @Test fun kanaHandlesDigraphsGeminationLongVowelsAndMoraN() {
        assertEquals("matcha", JapaneseRomaji.kanaToRomaji("まっちゃ"))
        assertEquals("issho", JapaneseRomaji.kanaToRomaji("イッショ"))
        assertEquals("koohii", JapaneseRomaji.kanaToRomaji("コーヒー"))
        assertEquals("shin'ya", JapaneseRomaji.kanaToRomaji("シンヤ"))
        assertEquals("tifa", JapaneseRomaji.kanaToRomaji("ティファ"))
    }

    @Test fun dictionaryReadsKanjiAsJapaneseAndHandlesParticles() {
        val result = JapaneseRomaji.romanize("私は日本の歌を歌う")!!
        assertTrue(result, result.startsWith("watashi wa"))
        assertTrue(result, result.contains("uta o"))
        assertFalse(result, result.any { it in '\u3400'..'\u9fff' })
        assertFalse(result, result.contains("ri ben"))
        assertEquals("konnichiwa", JapaneseRomaji.romanize("こんにちは"))
        assertEquals("datta", JapaneseRomaji.romanize("だった"))
        assertTrue(JapaneseRomaji.romanize("今日は歌った")!!.contains("utatta"))
        assertNull(JapaneseRomaji.romanize("Hello World"))
    }

    @Test fun conversionPreservesOriginalTextAndTimestamps() {
        val original = LyricsDocument(1, "Sample", "Artist", 10.0, false,
            listOf(LyricLine(1000, "君の名前"), LyricLine(5000, "Hello")), true)
        val result = JapaneseRomaji.convert(original)
        assertTrue(result.romanized)
        assertEquals(original.lines.map { it.text }, result.lines.map { it.text })
        assertEquals(original.lines.map { it.timeMs }, result.lines.map { it.timeMs })
        assertNotNull(result.lines.first().romaji)
        assertNull(result.lines.last().romaji)
    }

    @Test fun hanOnlyLyricsAreNotAssumedToBeJapanese() {
        val document = LyricsDocument(2, "Song", "Artist", 10.0, false, listOf(LyricLine(1000, "你好世界")), true)
        assertEquals(document, JapaneseRomaji.convert(document))
    }
}
