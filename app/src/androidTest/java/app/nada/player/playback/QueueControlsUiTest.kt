package app.nada.player.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.nada.player.MusicState
import app.nada.player.data.Track
import app.nada.player.ui.NadaTheme
import app.nada.player.ui.QueueSheetContent
import app.nada.player.ui.QueuePlaylistDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QueueControlsUiTest {
    @get:Rule val rule = createComposeRule()
    private val track = Track("track", "Judul", "Artis", "", 180, "Pop")
    private val queue = (0..3).map { QueuedTrack(it, track.copy(title = "Lagu $it"), entryId = "entry$it") }
    @Test fun accessibleReorderControlsTargetOccurrencesAndProtectTheCurrentSong() {
        val state = mutableStateOf(MusicState(source = "youtube", current = queue[0].track, queue = queue))
        var moved: Pair<String, String>? = null
        rule.setContent { NadaTheme { Surface { Column { QueueSheetContent(state.value, {}, { from, to -> moved = from to to }, {}, {}) } } } }
        rule.onNodeWithContentDescription("Atur urutan Lagu 0").assertDoesNotExist()
        rule.onNodeWithContentDescription("Atur urutan Lagu 1").performScrollTo().performClick()
        rule.onNodeWithText("Pindahkan ke atas").assertIsNotEnabled()
        rule.onNodeWithText("Pindahkan ke bawah").performClick()
        assertEquals("entry1" to "entry2", moved)
        rule.onNodeWithContentDescription("Hapus Lagu 0 dari antrean").assertDoesNotExist()
    }
    @Test fun draggingUsesOccurrenceIdentityForDuplicateSongs() {
        var moved: Pair<String, String>? = null
        rule.setContent { NadaTheme { Surface { Column {
            QueueSheetContent(MusicState(source = "youtube", queue = queue), {}, { a, b -> moved = a to b }, {}, {})
        } } } }
        rule.onNodeWithContentDescription("Atur urutan Lagu 1").performScrollTo()
        val first = rule.onNodeWithContentDescription("Atur urutan Lagu 1").fetchSemanticsNode().boundsInRoot.center
        val next = rule.onNodeWithContentDescription("Atur urutan Lagu 2").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { swipe(first, next, 900) }
        rule.waitUntil(5000) { moved != null }
        assertEquals("entry1" to "entry2", moved)
    }
    @Test fun emptyAndStorageFailureStatesExplainRecovery() {
        val state = mutableStateOf(MusicState(source = "youtube"))
        rule.setContent { NadaTheme { Surface { Column { QueueSheetContent(state.value, {}, { _, _ -> }, {}, {}) } } } }
        rule.onNodeWithText("Simpan").assertIsNotEnabled()
        rule.onNodeWithText("Belum ada lagu diputar.").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(queueSaveError = "Periksa ruang perangkat; coba lagi.") }
        rule.onNodeWithText("Periksa ruang perangkat; coba lagi.").assertIsDisplayed()
    }
    @Test fun spotifyQueueKeepsItsExternalOwnership() {
        rule.setContent { NadaTheme { Surface { Column { QueueSheetContent(MusicState(source = "spotify", queue = queue), {}, { _, _ -> }, {}, {}) } } } }
        rule.onNodeWithText("Simpan").assertDoesNotExist()
        rule.onNodeWithContentDescription("Atur urutan Lagu 1").assertDoesNotExist()
        rule.onNodeWithText("Buka Spotify").assertIsDisplayed()
    }
    @Test fun playlistDialogRequiresANameAndShowsItsFrozenSongCount() {
        var saved = ""
        rule.setContent { NadaTheme { QueuePlaylistDialog(3, {}) { saved = it } } }
        rule.onNodeWithText("Simpan").assertIsNotEnabled()
        rule.onNodeWithText("Nama playlist").performTextInput("  Perjalanan  ")
        rule.onNodeWithText("Simpan").performClick()
        assertEquals("Perjalanan", saved)
    }
}
