package app.nada.player

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nada.player.data.Track
import app.nada.player.playback.PlaybackService
import app.nada.player.playback.toMediaItem
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun settingsIsAnIndependentTabAfterLibraryAndBackReturnsHome() {
        val library = rule.onNodeWithContentDescription("Tab Koleksi").fetchSemanticsNode().boundsInRoot
        val settings = rule.onNodeWithContentDescription("Tab Pengaturan").fetchSemanticsNode().boundsInRoot
        assertTrue(settings.left > library.left)
        rule.onNodeWithContentDescription("Tab Pengaturan").performClick().assertIsSelected()
        rule.onNodeWithText("Backup koleksi").assertIsDisplayed()
        rule.onNodeWithContentDescription("Tab Koleksi").performClick().assertIsSelected()
        rule.onNodeWithText("Koleksi Kamu").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Pengaturan").assertCountEquals(0)
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithContentDescription("Tab Beranda").assertIsSelected()
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @Test fun lyricsHidesUnderlyingActionsAndBackRestoresPlayer() {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        rule.runOnUiThread {
            future = MediaController.Builder(rule.activity,
                SessionToken(rule.activity, ComponentName(rule.activity, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            // Metadata only: no prepare/play or network audio is needed for navigation.
            rule.runOnUiThread {
                controller.stop()
                controller.setMediaItem(Track("youtube:lBOnj7sFjRc", "The Lazy Song", "Bruno Mars", "", 190,
                    "YouTube Music", "https://www.youtube.com/watch?v=lBOnj7sFjRc", "youtube").toMediaItem())
            }
            rule.waitUntil(10_000) { rule.onAllNodes(hasText("The Lazy Song") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodes(hasText("The Lazy Song") and hasClickAction()).onLast().performClick()
            rule.onNodeWithContentDescription("Tutup pemutar").assertIsDisplayed()
            rule.onNodeWithText("Lirik").performClick()
            rule.onNodeWithContentDescription("Tutup lirik").assertIsDisplayed()
            rule.onAllNodesWithContentDescription("Tutup pemutar", useUnmergedTree = true).assertCountEquals(0)
            rule.onAllNodesWithContentDescription("Opsi lagu", useUnmergedTree = true).assertCountEquals(0)
            // Cleared background descendants remain in Compose's diagnostic
            // unmerged tree, but must not reach its exported accessibility tree.
            rule.onAllNodesWithContentDescription("Tab Beranda").assertCountEquals(0)
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithContentDescription("Tutup pemutar").assertIsDisplayed()
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithContentDescription("Tab Beranda").assertIsDisplayed()
        } finally {
            rule.runOnUiThread { controller.stop(); controller.clearMediaItems(); controller.release() }
        }
    }
}
