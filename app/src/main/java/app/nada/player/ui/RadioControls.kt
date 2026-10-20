@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nada.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nada.player.MusicState
import app.nada.player.data.Track
import app.nada.player.recommendations.RecommendationPreferences

@Composable
internal fun RadioTrackActions(track: Track, preferences: RecommendationPreferences, radio: () -> Unit, dislike: () -> Unit, block: () -> Unit) {
    if (track.playableInNada) SheetAction("Mulai Radio Lagu", Icons.Rounded.Radio, radio)
    SheetAction(if (preferences.dislikes(track)) "Batalkan kurang suka" else "Kurang suka", Icons.Rounded.ThumbDownOffAlt, dislike)
    SheetAction(if (preferences.blocks(track.artist)) "Rekomendasikan artis ini lagi" else "Jangan rekomendasikan artis ini", Icons.Rounded.PersonOff, block)
}

@Composable
internal fun AutoplayControl(enabled: Boolean, toggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Autoplay", style = MaterialTheme.typography.titleMedium)
            Text("Tambahkan lagu serupa setelah antrean pilihanmu. Berlaku untuk YouTube dan Audius.",
                Modifier.padding(top = 4.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(enabled, toggle, modifier = Modifier.semantics { contentDescription = "Autoplay lagu serupa" })
    }
}

@Composable
internal fun RadioQueueStatus(state: MusicState, toggle: (Boolean) -> Unit, retry: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.radioTitle?.let { Text("Radio · $it", style = MaterialTheme.typography.titleMedium, color = NadaGreen) }
        AutoplayControl(state.recommendationPreferences.autoplay, toggle)
        if (state.radioLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = NadaGreen)
            Text("Menyiapkan lagu berikutnya…", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        }
        state.radioError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = retry) { Text("Coba lagi radio") }
        }
    }
}

@Composable
internal fun RecommendationChoicesSheet(preferences: RecommendationPreferences, restoreSong: (Track) -> Unit,
    restoreArtist: (String) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = NadaSurface, properties = NadaSheetProperties,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Kontrol rekomendasi", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        Text("Pulihkan pilihan agar lagu atau artis boleh muncul lagi. Koleksi dan pencarian tetap tersedia.",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("Artis disembunyikan · ${preferences.blockedArtists.size}", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium) }
            items(preferences.blockedArtists.toList(), key = { "artist:${it.first}" }) { (_, artist) ->
                ChoiceRow(artist, "Artis", { restoreArtist(artist) })
            }
            item { Text("Lagu kurang disukai · ${preferences.disliked.size}", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium) }
            items(preferences.disliked, key = { "song:${it.id}" }) { track -> ChoiceRow(track.title, track.artist, { restoreSong(track) }) }
            if (preferences.blockedArtists.isEmpty() && preferences.disliked.isEmpty()) item {
                Text("Belum ada lagu atau artis yang disembunyikan.", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = NadaMuted)
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, restore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = restore, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Pulihkan $title" }) { Text("Pulihkan") }
    }
}
