package app.nada.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nada.player.MusicState
import app.nada.player.data.Track

@Composable
internal fun PersonalizedHome(state: MusicState, play: (List<Track>, Int) -> Unit, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Pilihan untukmu")
        Text(if (state.recommendationArtists.isEmpty()) "Dengarkan lagu favoritmu untuk mulai mendapatkan rekomendasi."
            else "Dari riwayat dengarmu · ${state.recommendationArtists.joinToString(" · ")}",
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        if (state.recommendationsLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = NadaGreen)
            Text("Menyiapkan rekomendasi…", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.recommendations.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.recommendations, key = Track::id) { track -> TrackCard(track) { play(state.recommendations, state.recommendations.indexOf(track)) } }
        }
        state.recommendationsError?.let { error ->
            Text(error, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = retry, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) { Text("Coba lagi rekomendasi") }
        }
        if (!state.recommendationsLoading && state.recommendationsError == null && state.recommendationArtists.isNotEmpty() && state.recommendations.isEmpty()) {
            Text("Belum menemukan lagu baru yang cocok. Coba lagi atau dengarkan artis lain untuk memperluas rekomendasi.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = retry, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) { Text("Coba lagi rekomendasi") }
        }
        if (state.frequent.isNotEmpty()) {
            SectionTitle("Sering kamu putar")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.frequent, key = Track::id) { track -> TrackCard(track) { play(state.frequent, state.frequent.indexOf(track)) } }
            }
        }
    }
}
