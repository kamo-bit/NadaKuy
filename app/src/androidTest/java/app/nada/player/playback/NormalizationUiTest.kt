package app.nada.player.playback

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.nada.player.ui.NadaTheme
import app.nada.player.ui.VolumeNormalizationControl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NormalizationUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun labeledSwitchTogglesAndRemainsDisabledForSpotify() {
        val enabled = mutableStateOf(true)
        val spotify = mutableStateOf(false)
        rule.setContent { NadaTheme { Surface { VolumeNormalizationControl(enabled.value, spotify.value) { enabled.value = it } } } }
        rule.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
        assertFalse(enabled.value)
        rule.onNode(isToggleable()).performClick().assertIsOn()
        rule.runOnIdle { spotify.value = true }
        rule.onNode(isToggleable()).assertIsNotEnabled().assertIsOn()
        rule.onNodeWithText("Atur normalisasi di aplikasi Spotify. Pilihan ini tetap disimpan untuk YouTube dan Audius.").assertIsDisplayed()
    }
}
