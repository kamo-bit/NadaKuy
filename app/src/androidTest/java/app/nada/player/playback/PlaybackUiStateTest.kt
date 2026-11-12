package app.nada.player.playback

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.nada.player.MusicState
import app.nada.player.PlaybackProgress
import app.nada.player.data.Track
import app.nada.player.downloads.OfflineState
import app.nada.player.downloads.OfflineStatus
import app.nada.player.downloads.OfflineTrack
import app.nada.player.ui.HomeFeed
import app.nada.player.ui.HomeFeedState
import app.nada.player.ui.NadaTheme
import app.nada.player.ui.PlaybackTimeline
import app.nada.player.ui.rememberHomeFeedState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackUiStateTest {
    @get:Rule val rule = createComposeRule()
    private val track = Track("track", "New Song", "Singer", "", 180, "", source = "youtube")

    @Test fun queueAndDownloadProgressKeepHomeProjectionWhileVisibleStatusChangesInvalidateIt() {
        val download = OfflineTrack(track.id, track, OfflineStatus.DOWNLOADING, 10, 100, 10f)
        val state = mutableStateOf(MusicState(source = "youtube", loading = false, recommendations = listOf(track),
            offline = OfflineState(tracks = listOf(download))))
        var rendered: HomeFeedState? = null
        rule.setContent { NadaTheme { Surface {
            val home = rememberHomeFeedState(state.value)
            SideEffect { rendered = home }
            HomeFeed(home, Modifier.fillMaxSize().safeDrawingPadding(), {}, { _, _ -> }, {}, {})
        } } }
        rule.waitForIdle()
        val initial = rendered
        rule.runOnIdle {
            state.value = state.value.copy(queue = listOf(QueuedTrack(0, track)), playing = true, message = "Saved",
                offline = state.value.offline.copy(tracks = listOf(download.copy(bytes = 40, percent = 40f))))
        }
        rule.runOnIdle { assertSame(initial, rendered) }
        rule.runOnIdle {
            state.value = state.value.copy(offline = state.value.offline.copy(tracks = listOf(download.copy(status = OfflineStatus.COMPLETE))))
        }
        rule.runOnIdle {
            assertNotSame(initial, rendered)
            assertTrue(track.id in rendered!!.indicators.offlineIds)
        }
        rule.runOnIdle { state.value = state.value.copy(liked = listOf(track), current = track) }
        rule.runOnIdle {
            assertEquals(track.id, rendered!!.indicators.currentId)
            assertTrue(track.id in rendered!!.indicators.likedIds)
        }
    }

    @Test fun progressAdvancesAndSeeksWithoutRecomposingTheHomeOwner() {
        val progress = mutableStateOf(PlaybackProgress(position = 1000, duration = 180_000))
        val state = MusicState(source = "youtube", loading = false, recommendations = listOf(track))
        var ownerCompositions = 0
        var seek = -1L
        rule.setContent { NadaTheme { Surface {
            val home = rememberHomeFeedState(state)
            SideEffect { ownerCompositions++ }
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                HomeFeed(home, Modifier.weight(1f), {}, { _, _ -> }, {}, {})
                PlaybackTimeline(track.id, { progress.value }, true, { seek = it })
            }
        } } }
        rule.onNodeWithText("0:01").assertIsDisplayed()
        val initial = rule.runOnIdle { ownerCompositions }
        rule.runOnIdle { progress.value = progress.value.copy(position = 10_000) }
        rule.onNodeWithText("0:10").assertIsDisplayed()
        rule.onNodeWithText("3:00").assertIsDisplayed()
        rule.onNodeWithText("New Song").assertIsDisplayed()
        rule.runOnIdle { assertEquals(initial, ownerCompositions) }
        rule.onNodeWithContentDescription("Posisi pemutaran").performSemanticsAction(SemanticsActions.SetProgress) { it(90_000f) }
        rule.runOnIdle { assertEquals(90_000L, seek) }
    }
}
