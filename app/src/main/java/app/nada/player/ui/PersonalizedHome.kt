package app.nada.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.nada.player.MusicState
import app.nada.player.data.Track
import kotlinx.coroutines.flow.distinctUntilChanged

/** Separate lazy items let off-screen sections leave composition, including their image requests. */
internal fun LazyListScope.personalizedHome(state: MusicState, play: (List<Track>, Int) -> Unit,
    retry: () -> Unit, loadMore: () -> Unit, options: (Track) -> Unit = {}) {
    item(key = "recommendation-heading", contentType = "heading") {
        SectionTitle("Pilihan untukmu")
        Text(if (state.recommendationArtists.isEmpty()) "Dengarkan lagu favoritmu untuk mulai mendapatkan rekomendasi."
            else if (state.source == "spotify") "Dari riwayat dengarmu · ${state.recommendationArtists.joinToString(" · ")}"
            else "Lagu terkait, lintas artis · Dari ${state.recommendationArtists.joinToString(" · ")}",
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
    }
    if (state.recommendationsLoading && state.recommendations.isEmpty()) item(key = "recommendation-loading", contentType = "status") {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = NadaGreen)
        Text("Menyiapkan rekomendasi…", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
    }
    if (state.recommendations.isNotEmpty()) item(key = "recommendation-tracks", contentType = "track-carousel") {
        key(state.source, state.youtubeVideos, state.recommendationArtists) {
            RecommendationRow(state.recommendations, state.recommendationsHaveMore, play, loadMore, options)
        }
    }
    state.recommendationsError?.let { error -> item(key = "recommendation-error", contentType = "status") {
        Text(error, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = retry, enabled = !state.recommendationsLoading, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) { Text("Coba lagi rekomendasi") }
    } }
    if (!state.recommendationsLoading && state.recommendationsError == null && state.recommendationArtists.isNotEmpty() && state.recommendations.isEmpty()) {
        item(key = "recommendation-empty", contentType = "status") {
            Text("Belum menemukan lagu terkait yang cocok. Coba lagi atau dengarkan lagu lain untuk memperluas rekomendasi.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = retry, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) { Text("Coba lagi rekomendasi") }
        }
    }
    if (state.frequent.isNotEmpty()) {
        item(key = "frequent-heading", contentType = "heading") { SectionTitle("Sering kamu putar") }
        item(key = "frequent-tracks", contentType = "track-carousel") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(state.frequent, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track -> TrackCard(track) { play(state.frequent, index) } }
            }
        }
    }
}

@Composable
private fun RecommendationRow(tracks: List<Track>, hasMore: Boolean, play: (List<Track>, Int) -> Unit, loadMore: () -> Unit, options: (Track) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(list, tracks.size, hasMore) {
        if (hasMore) snapshotFlow { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= tracks.size - 2 }
            .distinctUntilChanged().collect { nearEnd -> if (nearEnd) loadMore() }
    }
    LazyRow(Modifier.testTag("recommendation-tracks"), state = list, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track -> TrackCard(track, options = { options(track) }) { play(tracks, index) } }
        if (hasMore) item(key = "more", contentType = "action") {
            TextButton(onClick = loadMore, modifier = Modifier.heightIn(min = 158.dp)) { Text("Muat lagi") }
        }
    }
}
