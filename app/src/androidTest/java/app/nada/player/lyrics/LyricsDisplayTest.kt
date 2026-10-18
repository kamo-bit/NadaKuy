package app.nada.player.lyrics

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nada.player.ui.LyricsBody
import app.nada.player.ui.NadaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsDisplayTest {
    @get:Rule val rule = createComposeRule()

    @Test fun activeLineFollowsPositionSeekAndRomajiToggle() {
        val position = mutableLongStateOf(1500)
        var seek = -1L
        val document = LyricsDocument(1, "Sample", "Artist", 10.0, false, listOf(
            LyricLine(1000, "君の名前", "kimi no namae"),
            LyricLine(5000, "新しい朝", "atarashii asa")), true, romanized = true)
        rule.setContent { NadaTheme { LyricsBody(document, position.longValue, true, { seek = it }, Modifier.fillMaxSize()) } }
        rule.onNodeWithText("君の名前").assertIsSelected()
        rule.onNodeWithText("kimi no namae").assertIsDisplayed()
        rule.onNodeWithText("新しい朝").performClick()
        rule.runOnIdle { assertEquals(5000L, seek); position.longValue = 5500 }
        rule.onNodeWithText("新しい朝").assertIsSelected()
        rule.runOnIdle { position.longValue = 1500 }
        rule.onNodeWithText("君の名前").assertIsSelected()
        rule.onNodeWithText("Romaji").performClick()
        rule.onNodeWithText("kimi no namae").assertDoesNotExist()
        rule.onNodeWithText("君の名前").assertIsDisplayed()
    }

    @Test fun packagedJapaneseDictionaryConvertsKanjiOnAndroid() {
        val result = JapaneseRomaji.romanize("私は日本の歌を歌う")!!
        assertTrue(result, result.startsWith("watashi wa"))
        assertFalse(result, result.any { it in '\u3400'..'\u9fff' })
        assertEquals("datta", JapaneseRomaji.romanize("だった"))
    }
}
