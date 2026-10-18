@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.nada.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.nada.player.MusicState
import app.nada.player.MusicViewModel

internal fun sourceName(state: MusicState) = when (state.source) {
    "youtube" -> if (state.youtubeVideos) "YouTube" else "YouTube Music"
    "spotify" -> "Spotify"
    else -> "Audius"
}

@Composable
fun MusicSourceSheet(state: MusicState, model: MusicViewModel, spotify: () -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = NadaSurface, contentColor = MaterialTheme.colorScheme.onSurface,
        properties = NadaSheetProperties, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Pilih sumber musik", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineMedium)
            Text("Koleksi NadaKuy tetap tersimpan di perangkat ini.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = NadaMuted)
            SourceChoice("YouTube Music", "Cari lagu berdasarkan judul atau artis · Tanpa akun", Icons.Rounded.MusicNote,
                state.source == "youtube" && !state.youtubeVideos) { model.selectYouTube(false); dismiss() }
            SourceChoice("YouTube", "Cari video musik dan penampilan lainnya", Icons.Rounded.PlayCircleOutline,
                state.source == "youtube" && state.youtubeVideos) { model.selectYouTube(true); dismiss() }
            SourceChoice("Audius", "Musik dari komunitas Audius · Tanpa akun", Icons.Rounded.MusicNote,
                state.source == "audius") { model.selectSource("audius"); dismiss() }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SourceChoice("Spotify", "Hubungkan akun Premium dan aplikasi Spotify", Icons.Rounded.MusicNote,
                state.source == "spotify") { dismiss(); spotify() }
        }
    }
}

@Composable
private fun SourceChoice(title: String, detail: String, icon: ImageVector, selected: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = choose).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = if (selected) NadaGreen else NadaMuted, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) NadaGreen else MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = NadaMuted)
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, "Sumber dipilih", tint = NadaGreen)
    }
}
