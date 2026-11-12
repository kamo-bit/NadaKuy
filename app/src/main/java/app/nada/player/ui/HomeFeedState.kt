package app.nada.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.nada.player.MusicState
import app.nada.player.data.Track

internal data class TrackIndicators(val currentId: String?, val likedIds: Set<String>, val offlineIds: Set<String>)

@Composable
internal fun rememberTrackIndicators(state: MusicState): TrackIndicators {
    val liked = remember(state.liked) { state.liked.mapTo(hashSetOf(), Track::id) }
    val offline = remember(state.offline.tracks) { state.offline.tracks.filter { it.ready }.mapTo(hashSetOf()) { it.id } }
    return remember(state.current?.id, liked, offline) { TrackIndicators(state.current?.id, liked, offline) }
}

internal data class HomeFeedState(
    val home: List<Track>, val history: List<Track>, val loading: Boolean, val homeError: String?,
    val recommendations: List<Track>, val frequent: List<Track>, val recommendationArtists: List<String>,
    val recommendationsLoading: Boolean, val recommendationsError: String?, val recommendationsHaveMore: Boolean,
    val homeRefreshing: Boolean, val source: String, val sourceLabel: String, val youtubeVideos: Boolean,
    val spotifyAuthorized: Boolean, val spotifyConnected: Boolean, val indicators: TrackIndicators,
)

@Composable
internal fun rememberHomeFeedState(state: MusicState): HomeFeedState {
    val indicators = rememberTrackIndicators(state)
    return remember(state.home, state.history, state.loading, state.homeError, state.recommendations, state.frequent,
        state.recommendationArtists, state.recommendationsLoading, state.recommendationsError, state.recommendationsHaveMore,
        state.homeRefreshing, state.source, state.youtubeVideos, state.spotifyAuthorized, state.spotifyConnected, indicators) {
        HomeFeedState(state.home, state.history, state.loading, state.homeError, state.recommendations, state.frequent,
            state.recommendationArtists, state.recommendationsLoading, state.recommendationsError, state.recommendationsHaveMore,
            state.homeRefreshing, state.source, sourceName(state), state.youtubeVideos, state.spotifyAuthorized,
            state.spotifyConnected, indicators)
    }
}
