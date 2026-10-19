package app.nada.player.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import android.provider.Settings
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

private enum class SettingsSection { TITLE, BACKUP, DOWNLOADS, UPDATES }

@Composable
fun SettingsScreen(state: MusicState, model: MusicViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val transfer = model.transferState
    val update = model.updateState
    val list = rememberLazyListState()
    LaunchedEffect(model.updatesNavigationRequest) {
        if (model.updatesNavigationRequest > 0) list.scrollToItem(SettingsSection.entries.indexOf(SettingsSection.UPDATES))
    }
    var actionError by remember { mutableStateOf<String?>(null) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) model.updateInstallError("Pemasangan belum selesai. Paket tetap tersimpan; ketuk Pasang pembaruan untuk mencoba lagi.")
    }
    fun launchInstaller() {
        model.prepareAppUpdateInstall { intent ->
            try { installer.launch(intent) }
            catch (error: ActivityNotFoundException) { model.updateInstallError("Pemasang aplikasi Android belum tersedia di perangkat ini.") }
            catch (error: SecurityException) { model.updateInstallError("Android belum mengizinkan pemasangan. Ketuk Pasang pembaruan untuk mencoba lagi.") }
        }
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) launchInstaller()
        else model.updateInstallError("Izin pemasangan belum diberikan. Paket tetap tersimpan; ketuk Pasang pembaruan untuk mencoba lagi.")
    }
    fun requestInstall() {
        if (context.packageManager.canRequestPackageInstalls()) launchInstaller() else {
            try { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())) }
            catch (error: ActivityNotFoundException) { model.updateInstallError("Pengaturan izin pemasangan belum tersedia di perangkat ini.") }
        }
    }
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
    LazyColumn(Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
      items(SettingsSection.entries, key = { it.name }) { section ->
        if (section == SettingsSection.DOWNLOADS || section == SettingsSection.UPDATES) {
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        when (section) {
        SettingsSection.TITLE -> {
            Text("Pengaturan", Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp), style = MaterialTheme.typography.headlineMedium)
            actionError?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        }
        SettingsSection.BACKUP -> {
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
        SettingsSection.DOWNLOADS -> {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Unduhan", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Hanya jaringan tanpa kuota", style = MaterialTheme.typography.titleMedium)
                        Text("Mengikuti penanda kuota Android, biasanya Wi-Fi. Berlaku untuk musik, playlist, dan pembaruan aplikasi.", Modifier.padding(top = 4.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = state.offline.unmeteredOnly, onCheckedChange = model::setUnmeteredDownloads, enabled = state.offline.loaded,
                        modifier = Modifier.semantics { contentDescription = "Unduh hanya di jaringan tanpa kuota" })
                }
            }
        }
        SettingsSection.UPDATES -> {
            AppUpdateSection(model.installedVersion, update, model.updateDownload, model.preparingUpdateInstall,
                model.updateActionError, model::checkForUpdates, model::downloadAppUpdate, model::cancelAppUpdate,
                ::requestInstall, { openLink(AppUpdates.RELEASES_URL) })
        }
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
