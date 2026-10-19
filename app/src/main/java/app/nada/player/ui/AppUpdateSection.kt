package app.nada.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nada.player.updates.*
import java.util.Locale

@Composable
internal fun AppUpdateSection(installed: String, update: UpdateState, download: UpdateDownloadState,
    installing: Boolean, actionError: String?, check: () -> Unit, start: (UpdatePackage) -> Unit,
    cancel: () -> Unit, install: () -> Unit, notes: () -> Unit) {
    val newer = update.release?.takeIf { update.newer }
    val pkg = if (download.busy) download.pkg else if (newer != null) newer.apk else download.pkg
    val transfer = download.takeIf { it.pkg == pkg } ?: UpdateDownloadState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pembaruan aplikasi", style = MaterialTheme.typography.titleLarge)
        Text("Versi terpasang $installed", color = NadaMuted)
        Text("Unduh dan pasang pembaruan langsung dari NadaKuy. Koleksi dan lagu offline tetap tersimpan.", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        if (update.checking) {
            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Memeriksa pembaruan" }, color = NadaGreen)
            Text("Memeriksa versi terbaru…", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        }
        update.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (pkg != null) {
            Text("NadaKuy ${pkg.version}", style = MaterialTheme.typography.titleMedium, color = NadaGreen)
            Text("Paket pembaruan · ${updateBytes(pkg.bytes)}", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
            when {
                transfer.busy || installing -> {
                    if (transfer.phase == UpdatePhase.DOWNLOADING) {
                        val fraction = (transfer.bytes.toFloat() / pkg.bytes).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Progres unduhan pembaruan" }, color = NadaGreen, drawStopIndicator = {})
                        Text("${(fraction * 100).toInt()}% · ${updateBytes(transfer.bytes)} dari ${updateBytes(pkg.bytes)}", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = NadaGreen)
                        Text(when {
                            installing -> "Menyiapkan pemasangan…"
                            transfer.phase == UpdatePhase.VERIFYING -> "Memeriksa paket pembaruan…"
                            else -> "Menunggu koneksi sesuai pengaturan unduhan…"
                        }, color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (transfer.busy) {
                        Text("Kamu bisa tetap memakai NadaKuy selama proses ini berjalan.", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Batalkan unduhan") }
                    }
                }
                transfer.phase == UpdatePhase.READY -> {
                    Text("Pembaruan siap dipasang", color = NadaGreen, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = install, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Pasang pembaruan")
                    }
                    Text("Android akan meminta persetujuan pemasangan. Jika diminta, izinkan NadaKuy memasang pembaruan.", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    transfer.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (transfer.phase == UpdatePhase.CANCELLED) Text("Unduhan dibatalkan.", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { start(pkg) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (transfer.phase in setOf(UpdatePhase.FAILED, UpdatePhase.CANCELLED)) "Coba unduh lagi" else "Unduh pembaruan")
                    }
                }
            }
        } else if (newer != null) {
            Text("Versi ${newer.version} tersedia. Paket pembaruan belum siap; coba periksa lagi nanti.", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        } else if (update.release != null) Text("NadaKuy sudah terbaru", color = NadaGreen, style = MaterialTheme.typography.titleMedium)
        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onClick = check, enabled = !update.checking && !download.busy && !installing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Cek pembaruan")
        }
        if (update.release?.notes?.isNotBlank() == true) {
            var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Tutup catatan versi" else "Lihat catatan versi") }
            if (expanded) Text(update.release.notes, color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = notes, modifier = Modifier.heightIn(min = 48.dp)) { Text("Riwayat rilis di GitHub") }
    }
}

internal fun updateBytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
