package app.nada.player.recommendations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.nada.player.MusicState
import app.nada.player.data.Track
import app.nada.player.ui.NadaTheme
import app.nada.player.ui.PersonalizedHome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PersonalizedHomeTest {
    @get:Rule val rule = createComposeRule()
    private val track = Track("test", "New Song", "Singer", "", 180, "", source = "youtube")
    private fun show(state: MusicState, play: (List<Track>, Int) -> Unit = { _, _ -> }, retry: () -> Unit = {}) {
        rule.setContent { NadaTheme { Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) { PersonalizedHome(state, play, retry) }
        } } }
    }
    @Test fun recommendationStartsTheDisplayedQueueAndFrequentListRemainsDistinct() {
        var selected: List<Track>? = null
        show(MusicState(recommendations = listOf(track), frequent = listOf(track.copy(id = "old", title = "Old Song")), recommendationArtists = listOf("Singer")),
            play = { tracks, index -> assertEquals(0, index); selected = tracks })
        rule.onNodeWithText("New Song").performClick()
        assertEquals(listOf(track), selected)
        rule.onNodeWithText("Sering kamu putar").performScrollTo().assertIsDisplayed()
    }
    @Test fun coldStartExplainsHowToGetRecommendations() {
        show(MusicState())
        rule.onNodeWithText("Dengarkan lagu favoritmu", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Sering kamu putar").assertDoesNotExist()
    }
    @Test fun failedRecommendationsHaveRetryWithoutHidingFrequentMusic() {
        var retried = false
        show(MusicState(recommendationArtists = listOf("Singer"), recommendationsError = "Periksa internet", frequent = listOf(track)), retry = { retried = true })
        rule.onNodeWithText("Coba lagi rekomendasi").performClick()
        assertTrue(retried)
        rule.onNodeWithText("New Song").assertIsDisplayed()
    }
}
