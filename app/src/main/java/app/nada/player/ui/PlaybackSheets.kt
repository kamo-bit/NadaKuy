@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nada.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nada.player.MusicState
import app.nada.player.MusicViewModel

fun sleepTimerLabel(state: MusicState): String? = when {
    state.sleepEndOfTrack -> "Akhir lagu"
    state.sleepDeadline > 0 -> {
        val seconds = state.sleepRemainingSeconds
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
    else -> null
}

@Composable
fun SleepTimerSheet(state: MusicState, model: MusicViewModel, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = NadaSurface, properties = NadaSheetProperties, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Timer tidur", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        Text(sleepTimerLabel(state)?.let { "Aktif · $it" } ?: "Hentikan musik secara otomatis.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = NadaMuted)
        LazyColumn {
            items(listOf(5, 10, 15, 30, 45, 60)) { minutes ->
                SheetAction("$minutes menit", Icons.Rounded.Timer) { model.setSleepTimer(minutes); dismiss() }
            }
            item { SheetAction("Setelah lagu ini selesai", Icons.Rounded.MusicNote) { model.setSleepTimer(-1); dismiss() } }
            if (state.sleepEndOfTrack || state.sleepDeadline > 0) item {
                SheetAction("Matikan timer", Icons.Rounded.TimerOff) { model.setSleepTimer(0); dismiss() }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun QueueSheet(state: MusicState, model: MusicViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val upcoming = if (state.queueHasCurrent) state.queue.drop(1) else state.queue
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = NadaSurface, properties = NadaSheetProperties) {
        Text("Antrean", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        if (state.source == "spotify") {
            Text(state.queueError ?: "Antrean ini mengikuti pemutar Spotify. Kelola urutannya di aplikasi Spotify.", Modifier.padding(horizontal = 24.dp), color = NadaMuted)
            TextButton(onClick = { openSpotify(context) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Buka Spotify") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("Sedang diputar", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
            state.queue.firstOrNull()?.takeIf { state.queueHasCurrent }?.let { current -> item {
                TrackRow(current.track, state, { dismiss() }, {}, false)
            } }
            item { Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Berikutnya", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (state.shuffle) Icon(Icons.Rounded.Shuffle, "Mode acak aktif", tint = NadaGreen, modifier = Modifier.size(20.dp))
                Text("  ${upcoming.size} lagu", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
            } }
            if (upcoming.isEmpty()) item {
                Text(if (state.source == "spotify") "Tambahkan lagu ke antrean Spotify melalui menu lagu." else "Pilih lagu melalui menu Putar berikutnya untuk menambah antrean.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = NadaMuted)
            }
            items(upcoming, key = { it.mediaIndex }) { entry ->
                Row(Modifier.fillMaxWidth().clickable(enabled = state.source != "spotify") { model.playQueueItem(entry.mediaIndex); dismiss() }.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(entry.track, Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(entry.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(entry.track.artist, color = NadaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.source != "spotify") IconButton(onClick = { model.removeQueueItem(entry.mediaIndex) }) { Icon(Icons.Rounded.RemoveCircleOutline, "Hapus ${entry.track.title} dari antrean", tint = NadaMuted) }
                }
            }
        }
    }
}
