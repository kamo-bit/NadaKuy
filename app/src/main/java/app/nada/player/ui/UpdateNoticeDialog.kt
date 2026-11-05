package app.nada.player.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun UpdateNoticeDialog(version: String, open: () -> Unit, later: () -> Unit) {
    AlertDialog(
        onDismissRequest = later,
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text("Pembaruan tersedia") },
        text = { Text("NadaKuy $version sudah tersedia. Lihat detail dan unduh pembaruan langsung di aplikasi. Jika memilih Nanti, pengingat ditunda selama 24 jam.") },
        confirmButton = { TextButton(onClick = open) { Text("Lihat pembaruan") } },
        dismissButton = { TextButton(onClick = later) { Text("Nanti") } },
    )
}
