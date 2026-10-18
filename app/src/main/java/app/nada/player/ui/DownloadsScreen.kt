package app.nada.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nada.player.MusicState
import app.nada.player.MusicViewModel
import app.nada.player.downloads.OfflineStatus
import app.nada.player.downloads.OfflineTrack
import java.util.Locale

internal fun downloadSize(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes.coerceAtLeast(0) / 1_000_000.0)

@Composable
fun DownloadsScreen(state: MusicState, model: MusicViewModel, modifier: Modifier, back: () -> Unit, search: () -> Unit) {
    val entries = state.offline.tracks
    val ready = entries.filter { it.ready }.mapNotNull { it.track }
    var removing by remember { mutableStateOf<OfflineTrack?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("Unduhan", style = MaterialTheme.typography.headlineMedium)
                    Text("${ready.size} lagu siap offline · ${downloadSize(entries.sumOf { it.bytes })}", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                }
                FilledIconButton(onClick = { model.play(ready) }, enabled = ready.isNotEmpty(), modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = NadaGreen, contentColor = NadaBackground)) {
                    Icon(Icons.Rounded.PlayArrow, "Putar semua unduhan")
                }
            }
            Text("Audio disimpan di NadaKuy pada perangkat ini.", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        }
        state.offline.error?.let { message -> item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::refreshDownloads) { Text("Muat ulang") }
            }
        } }
        if (!state.offline.loaded) item {
            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = NadaGreen) }
        } else if (entries.isEmpty() && state.offline.error == null) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.DownloadForOffline, null, Modifier.size(48.dp), tint = NadaGreen)
                Text("Musikmu, tetap dekat", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
                Text("Buka menu lagu, lalu pilih Unduh untuk offline. Lagu yang selesai diunduh bisa diputar tanpa internet.",
                    Modifier.padding(top = 12.dp), color = NadaMuted, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = search, modifier = Modifier.padding(top = 24.dp)) { Text("Cari musik") }
            }
        }
        items(entries, key = { it.id }) { entry ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable(enabled = entry.ready) {
                entry.track?.let { model.play(ready, ready.indexOf(it).coerceAtLeast(0)) }
            }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                entry.track?.let { Cover(it, Modifier.size(52.dp)) }
                    ?: Icon(Icons.Rounded.MusicNote, null, Modifier.size(52.dp), tint = NadaMuted)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.track?.title ?: "Unduhan tidak dikenali", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (state.current?.id == entry.id) NadaGreen else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    entry.track?.let { Text(it.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = NadaMuted, style = MaterialTheme.typography.bodyMedium) }
                    val label = when {
                        entry.track == null -> "Data lagu tidak tersedia · Hapus unduhan"
                        entry.missingMedia -> "File tidak tersedia · Unduh ulang"
                        entry.status == OfflineStatus.COMPLETE -> "Siap offline · ${downloadSize(entry.bytes)}"
                        entry.status == OfflineStatus.PAUSED -> "Dijeda · ${downloadSize(entry.bytes)}"
                        entry.status == OfflineStatus.FAILED -> "Gagal · Periksa internet atau ruang kosong, lalu coba lagi"
                        entry.status == OfflineStatus.REMOVING -> "Menghapus unduhan…"
                        entry.status == OfflineStatus.QUEUED -> when {
                            entry.waitingForUnmetered -> "Menunggu jaringan tanpa kuota"
                            entry.waitingForNetwork -> "Menunggu internet"
                            else -> "Menunggu giliran"
                        }
                        else -> entry.percent?.let { "${it.toInt()}% · ${downloadSize(entry.bytes)}" } ?: downloadSize(entry.bytes)
                    }
                    Text(label, Modifier.padding(top = 3.dp), color = if (entry.ready) NadaGreen else NadaMuted, style = MaterialTheme.typography.labelMedium)
                    if (entry.status == OfflineStatus.DOWNLOADING) {
                        val progress = entry.percent
                        if (progress != null) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = NadaGreen, drawStopIndicator = {})
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = NadaGreen)
                    }
                }
                when (entry.status) {
                    OfflineStatus.QUEUED, OfflineStatus.DOWNLOADING -> IconButton(onClick = { model.pauseDownload(entry.id) }) { Icon(Icons.Rounded.Pause, "Jeda unduhan ${entry.track?.title.orEmpty()}") }
                    OfflineStatus.PAUSED -> IconButton(onClick = { model.resumeDownload(entry.id) }) { Icon(Icons.Rounded.PlayArrow, "Lanjutkan unduhan ${entry.track?.title.orEmpty()}") }
                    OfflineStatus.FAILED -> if (entry.track != null) IconButton(onClick = { model.download(entry.track) }) { Icon(Icons.Rounded.Refresh, "Coba unduh lagi ${entry.track.title}") }
                    else -> Unit
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = entry.status != OfflineStatus.REMOVING) { Icon(Icons.Rounded.MoreVert, "Opsi unduhan ${entry.track?.title.orEmpty()}", tint = NadaMuted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Hapus unduhan") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; removing = entry })
                    }
                }
            }
        }
    }
    removing?.let { entry -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Hapus unduhan?") },
        text = { Text("Salinan offline ${entry.track?.title ?: "lagu ini"} akan dihapus dari perangkat. Playlist dan lagu disukai tidak ikut dihapus.") },
        confirmButton = { TextButton(onClick = { model.removeDownload(entry.id); removing = null }) { Text("Hapus") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Batal") } }) }
}
