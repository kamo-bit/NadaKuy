package app.nada.player.updates

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.nada.player.ui.NadaTheme
import app.nada.player.ui.UpdateNoticeDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateNoticeUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun noticeOpensDetailsWithoutStartingADownload() {
        var opened = 0
        var deferred = 0
        rule.setContent { NadaTheme { UpdateNoticeDialog("1.9.0", { opened++ }, { deferred++ }) } }
        rule.onNodeWithText("Pembaruan tersedia").assertIsDisplayed()
        rule.onNodeWithText("NadaKuy 1.9.0", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Unduh pembaruan").assertDoesNotExist()
        rule.onNodeWithText("Lihat pembaruan").performClick()
        assertEquals(1, opened)
        assertEquals(0, deferred)
    }

    @Test fun laterDefersWithoutNavigating() {
        var opened = 0
        var deferred = 0
        rule.setContent { NadaTheme { UpdateNoticeDialog("1.9.0", { opened++ }, { deferred++ }) } }
        rule.onNodeWithText("Nanti").performClick()
        assertEquals(0, opened)
        assertEquals(1, deferred)
    }
}
