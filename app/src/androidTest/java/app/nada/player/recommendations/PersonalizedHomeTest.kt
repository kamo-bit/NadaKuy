package app.nada.player.recommendations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.nada.player.MusicState
import app.nada.player.data.Track
import app.nada.player.ui.NadaTheme
import app.nada.player.ui.personalizedHome
import app.nada.player.ui.HomeFeed
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PersonalizedHomeTest {
    @get:Rule val rule = createComposeRule()
    private val track = Track("test", "New Song", "Singer", "", 180, "", source = "youtube")
    private fun show(state: MusicState, play: (List<Track>, Int) -> Unit = { _, _ -> }, retry: () -> Unit = {}) {
        rule.setContent { NadaTheme { Surface(color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding()) { personalizedHome(state, play, retry, {}) }
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

    @Test fun recommendationsLoadTheNextBatchOnlyWhenApproachingTheEnd() {
        val all = (0 until 40).map { track.copy(id = "song-$it", title = "Song $it") }
        val state = mutableStateOf(MusicState(recommendations = all.take(6), recommendationsHaveMore = true))
        var pages = 0
        rule.setContent { NadaTheme { LazyColumn(Modifier.fillMaxSize().safeDrawingPadding()) {
            personalizedHome(state.value, { _, _ -> }, {}, {
                pages++
                val next = state.value.recommendations.size + 6
                state.value = state.value.copy(recommendations = all.take(next), recommendationsHaveMore = next < all.size)
            })
        } } }
        rule.runOnIdle { assertEquals(0, pages) }
        rule.onNodeWithText("Song 39").assertDoesNotExist()
        rule.onNodeWithTag("recommendation-tracks").performScrollToIndex(5)
        rule.waitUntil { state.value.recommendations.size == 12 }
        rule.runOnIdle { assertEquals(1, pages) }
        rule.onNodeWithText("Song 39").assertDoesNotExist()
    }

    @Test fun pullingToRefreshKeepsCurrentRecommendationsVisible() {
        val state = mutableStateOf(MusicState(source = "youtube", loading = false, recommendations = listOf(track)))
        var refreshes = 0
        rule.setContent { NadaTheme { Surface { HomeFeed(state.value, Modifier.fillMaxSize().safeDrawingPadding(),
            refresh = { refreshes++; state.value = state.value.copy(homeRefreshing = true) },
            play = { _, _ -> }, retryRecommendations = {}, loadMoreRecommendations = {}) } } }
        rule.onNodeWithTag("home-feed").performTouchInput { swipeDown(durationMillis = 600) }
        rule.waitUntil { refreshes == 1 }
        rule.onNodeWithText("New Song").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(homeRefreshing = false, recommendationsError = "Koneksi terputus") }
        rule.onNodeWithText("New Song").assertIsDisplayed()
        rule.onNodeWithText("Koneksi terputus").assertIsDisplayed()
    }
}
