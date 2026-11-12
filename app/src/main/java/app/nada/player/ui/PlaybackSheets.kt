@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nada.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.nada.player.MusicState
import app.nada.player.MusicViewModel
import app.nada.player.PlaybackProgress

private fun sleepTimerLabel(state: MusicState, seconds: Long): String? = when {
    state.sleepEndOfTrack -> "Akhir lagu"
    state.sleepDeadline > 0 -> {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
    else -> null
}

@Composable
internal fun SleepTimerStatus(state: MusicState, progress: () -> PlaybackProgress, modifier: Modifier,
    prefix: String, emptyText: String? = null, color: Color = NadaMuted, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val seconds = if (state.sleepDeadline > 0) progress().sleepRemainingSeconds else 0
    val text = sleepTimerLabel(state, seconds)?.let { "$prefix$it" } ?: emptyText
    text?.let { Text(it, modifier, color = color, style = style) }
}

@Composable
fun SleepTimerSheet(state: MusicState, model: MusicViewModel, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = NadaSurface, properties = NadaSheetProperties, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Timer tidur", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        SleepTimerStatus(state, { model.progress }, Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            "Aktif · ", "Hentikan musik secara otomatis.", style = LocalTextStyle.current)
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
