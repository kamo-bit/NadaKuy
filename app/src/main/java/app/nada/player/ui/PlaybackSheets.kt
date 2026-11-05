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
