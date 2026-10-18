@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nada.player.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.nada.player.MusicState
import app.nada.player.MusicViewModel
import app.nada.player.spotify.SpotifyConfig
import app.nada.player.R

@Composable
fun SpotifyLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.spotify_logo_white), contentDescription = "Spotify", modifier = modifier.width(84.dp).height(26.dp))
}

@Composable
fun SpotifyConnectionSheet(state: MusicState, model: MusicViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    var clientId by rememberSaveable(state.spotifyClientId) { mutableStateOf(state.spotifyClientId) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = NadaSurface, contentColor = MaterialTheme.colorScheme.onSurface,
        properties = NadaSheetProperties, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SpotifyLogo()
            Text("Hubungkan Spotify", style = MaterialTheme.typography.headlineMedium)
            Text("Dengarkan dengan akun Premium kamu. Musik diputar oleh aplikasi Spotify di perangkat ini.", color = NadaMuted)
            if (state.spotifyAuthorized) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = NadaGreen)
                    Text(if (state.spotifyConnected) "Akun dan pemutar terhubung" else "Akun terhubung · pemutar belum aktif", color = NadaGreen)
                }
            }
            OutlinedTextField(clientId, { clientId = it.trim().take(32) }, label = { Text("Client ID") }, singleLine = true,
                enabled = !state.spotifyBusy, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Dari aplikasi NadaKuy di Spotify Developer Dashboard. Client Secret tidak diperlukan.") })
            state.spotifyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                model.configureSpotify(clientId)
                if (SpotifyConfig.validClientId(clientId)) model.connectSpotify(context as Activity)
            }, enabled = !state.spotifyBusy && SpotifyConfig.validClientId(clientId), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (state.spotifyBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (state.spotifyAuthorized) "Hubungkan pemutar Spotify" else "Hubungkan akun Spotify")
            }
            if (state.spotifyBusy) TextButton(onClick = model::cancelSpotifyConnection, modifier = Modifier.fillMaxWidth()) { Text("Batalkan koneksi") }
            OutlinedButton(onClick = { openSpotify(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Buka Spotify"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
            }
            Text("Login dan izin akses diberikan langsung di Spotify. Lagu disukai Spotify mengikuti akunmu. Playlist NadaKuy tetap tersimpan di perangkat.", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
            if (state.spotifyAuthorized) TextButton(onClick = model::disconnectSpotify, modifier = Modifier.fillMaxWidth()) { Text("Putuskan koneksi akun") }
            HorizontalDivider()
            TextButton(onClick = { model.selectSource("audius"); dismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Gunakan Audius tanpa akun") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

fun openSpotify(context: android.content.Context, url: String? = null) {
    val launch = context.packageManager.getLaunchIntentForPackage(SpotifyConfig.PACKAGE)
    val intent = if (url != null && launch != null) Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(SpotifyConfig.PACKAGE) else launch
    runCatching { context.startActivity(intent ?: Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.spotify.music".toUri())) }
}
