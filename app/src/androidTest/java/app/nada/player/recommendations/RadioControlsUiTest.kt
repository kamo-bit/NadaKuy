package app.nada.player.recommendations

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.nada.player.data.Track
import app.nada.player.ui.NadaTheme
import app.nada.player.ui.RadioTrackActions
import app.nada.player.ui.AutoplayControl
import app.nada.player.ui.RecommendationChoicesSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RadioControlsUiTest {
    @get:Rule val rule = createComposeRule()
    private val track = Track("song", "A Song", "Singer", "", 180, "Pop")
    @Test fun trackMenuOffersRadioAndReversibleFeedback() {
        val choices = mutableStateOf(RecommendationPreferences())
        var radio = false
        rule.setContent { NadaTheme { Surface { Column {
            RadioTrackActions(track, choices.value, { radio = true }, { choices.value = choices.value.copy(disliked = listOf(track)) },
                { choices.value = choices.value.copy(blockedArtists = mapOf("singer" to "Singer")) })
        } } } }
        rule.onNodeWithText("Mulai Radio Lagu").performClick(); assertTrue(radio)
        rule.onNodeWithText("Kurang suka").performClick()
        rule.onNodeWithText("Batalkan kurang suka").assertIsDisplayed()
        rule.onNodeWithText("Jangan rekomendasikan artis ini").performClick()
        rule.onNodeWithText("Rekomendasikan artis ini lagi").assertIsDisplayed()
    }
    @Test fun autoplayHasAnAccessibleSwitchAndChoicesCanBeRestored() {
        val enabled = mutableStateOf(false)
        rule.setContent { NadaTheme { Surface { AutoplayControl(enabled.value, { enabled.value = it }) } } }
        rule.onNodeWithContentDescription("Autoplay lagu serupa").assertIsOff().performClick().assertIsOn()
    }
    @Test fun managementSheetRestoresASongOrArtistIndividually() {
        val choices = mutableStateOf(RecommendationPreferences(listOf(track), mapOf("singer" to "Singer")))
        rule.setContent { NadaTheme { RecommendationChoicesSheet(choices.value,
            { choices.value = choices.value.copy(disliked = emptyList()) }, { choices.value = choices.value.copy(blockedArtists = emptyMap()) }, {}) } }
        rule.onNodeWithContentDescription("Pulihkan Singer").performClick()
        rule.onNodeWithText("Artis disembunyikan · 0").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pulihkan A Song").performClick()
        rule.onNodeWithText("Belum ada lagu atau artis yang disembunyikan.").assertIsDisplayed()
    }
}
