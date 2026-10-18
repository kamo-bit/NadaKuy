package app.nada.player.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.nada.player.MusicState
import app.nada.player.MusicViewModel
import app.nada.player.updates.AppUpdates
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SettingsScreen(state: MusicState, model: MusicViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val transfer = model.transferState
    val update = model.updateState
    var actionError by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.exportCollection(uri)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.readCollectionBackup(uri)
    }
    fun openLink(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())); actionError = null }
        catch (error: ActivityNotFoundException) { actionError = "Browser belum tersedia. Pasang browser untuk membuka halaman rilis." }
    }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
    LazyColumn(Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("Pengaturan", Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp), style = MaterialTheme.typography.headlineMedium)
            actionError?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Backup koleksi", style = MaterialTheme.typography.titleLarge)
                Text("Simpan playlist, lagu disukai, riwayat putar, dan pencarian ke file untuk dipindahkan ke perangkat lain.", color = NadaMuted)
                Text("Audio offline dan koneksi Spotify tidak disertakan. Unduh kembali lagunya setelah memulihkan koleksi.", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    try { export.launch("NadaKuy-backup-${LocalDate.now()}.json"); actionError = null }
                    catch (error: ActivityNotFoundException) { actionError = "Pemilih file belum tersedia di perangkat ini." }
                }, enabled = transfer.busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.SaveAlt, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Simpan backup")
                }
                OutlinedButton(onClick = {
                    try { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")); actionError = null }
                    catch (error: ActivityNotFoundException) { actionError = "Pemilih file belum tersedia di perangkat ini." }
                }, enabled = transfer.busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Restore, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Pulihkan dari file")
                }
                transfer.busy?.let { label -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = NadaGreen)
                    Text(label, Modifier.padding(start = 12.dp), color = NadaMuted)
                } }
                transfer.message?.let { Text(it, color = NadaGreen, style = MaterialTheme.typography.bodyMedium) }
                transfer.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), color = MaterialTheme.colorScheme.outlineVariant) }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Unduhan", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Hanya jaringan tanpa kuota", style = MaterialTheme.typography.titleMedium)
                        Text("Mengikuti penanda kuota Android, biasanya Wi-Fi. Berlaku juga untuk unduhan playlist.", Modifier.padding(top = 4.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = state.offline.unmeteredOnly, onCheckedChange = model::setUnmeteredDownloads, enabled = state.offline.loaded,
                        modifier = Modifier.semantics { contentDescription = "Unduh hanya di jaringan tanpa kuota" })
                }
            }
        }
        item { HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), color = MaterialTheme.colorScheme.outlineVariant) }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Pembaruan aplikasi", style = MaterialTheme.typography.titleLarge)
                Text("Versi terpasang ${model.installedVersion}", color = NadaMuted)
                Text("Periksa rilis resmi NadaKuy di GitHub. Pemeriksaan dilakukan saat kamu menekan tombol di bawah.", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                when {
                    update.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = NadaGreen, strokeWidth = 2.dp)
                        Text("Memeriksa versi terbaru…", Modifier.padding(start = 12.dp), color = NadaMuted)
                    }
                    update.error != null -> Text(update.error, color = MaterialTheme.colorScheme.error)
                    update.release != null -> {
                        val release = update.release
                        Text(if (update.newer) "Versi ${release.version} tersedia" else "NadaKuy sudah terbaru",
                            color = NadaGreen, style = MaterialTheme.typography.titleMedium)
                        if (!update.newer && release.version != model.installedVersion) Text("Rilis publik terbaru: ${release.version}", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                        if (update.newer) {
                            if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodyMedium, color = NadaMuted)
                            Button(onClick = { openLink(release.apkUrl ?: release.pageUrl) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                Text(if (release.apkUrl != null) "Unduh APK ${release.version}" else "Buka rilis ${release.version}")
                            }
                            Text("Buka APK yang selesai diunduh dan pilih Perbarui. Gunakan APK resmi agar koleksimu tetap tersimpan.", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                OutlinedButton(onClick = model::checkForUpdates, enabled = !update.checking, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Cek pembaruan")
                }
                TextButton(onClick = { openLink(AppUpdates.RELEASES_URL) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Buka halaman rilis") }
            }
        }
    }
    }
    transfer.pending?.let { backup ->
        val collection = backup.collection
        val date = runCatching { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault()).format(Instant.parse(backup.exportedAt)) }.getOrDefault(backup.exportedAt)
        AlertDialog(onDismissRequest = model::cancelCollectionRestore,
            title = { Text("Gabungkan koleksi dari backup?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Backup $date")
                Text("${collection.playlists.size} playlist · ${collection.liked.size} lagu disukai · ${collection.history.size} riwayat putar")
                Text("Koleksi yang ada tetap tersimpan. Lagu dan playlist dengan identitas yang sama digabungkan tanpa duplikat. Audio offline perlu diunduh kembali.", color = NadaMuted)
            } },
            confirmButton = { TextButton(onClick = model::restoreCollection) { Text("Gabungkan") } },
            dismissButton = { TextButton(onClick = model::cancelCollectionRestore) { Text("Batal") } })
    }
}
