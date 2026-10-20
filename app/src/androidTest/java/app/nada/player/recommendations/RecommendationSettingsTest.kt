package app.nada.player.recommendations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecommendationSettingsTest {
    @Test fun choicesSurviveReopeningAndCanBeRestoredIndividually() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-recommendations-${UUID.randomUUID()}"
        val song = Track("one", "Song", "Singer - Topic", "", 180, "Pop")
        try {
            val settings = RecommendationSettings(context, name)
            assertFalse(settings.state.value.autoplay)
            settings.toggleDislike(song); settings.toggleArtist("Other - Topic"); settings.setAutoplay(true)
            val reopened = RecommendationSettings(context, name)
            assertTrue(reopened.state.value.dislikes(song.copy(id = "another")))
            assertTrue(reopened.state.value.blocks("OtherVEVO"))
            assertTrue(reopened.state.value.autoplay)
            reopened.toggleDislike(song); reopened.toggleArtist("Other")
            assertTrue(reopened.state.value.allows(song))
            assertFalse(RecommendationSettings(context, name).state.value.blocks("Other"))
        } finally { context.deleteSharedPreferences(name) }
    }
}
