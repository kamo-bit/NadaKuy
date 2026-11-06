package app.nada.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nada.player.PlaylistImportState

@Composable
internal fun PlaylistImportScreen(
    modifier: Modifier,
    state: PlaylistImportState,
    back: () -> Unit,
    previewPlaylist: (String) -> Unit,
    reset: () -> Unit,
    save: () -> Unit,
) {
    var link by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val preview = state.preview
    val canSubmit = link.isNotBlank() && !state.loading
    fun submit() {
        if (!canSubmit) return
        focus.clearFocus()
        previewPlaylist(link)
    }

    Column(modifier.imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text(if (preview == null) "Impor playlist" else "Pratinjau playlist",
                    Modifier.padding(start = 8.dp), style = MaterialTheme.typography.headlineMedium)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                if (preview == null) item {
                    Text("Tempel tautan playlist publik dari YouTube atau YouTube Music.",
                        style = MaterialTheme.typography.bodyLarge, color = NadaMuted)
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it.take(4096); reset() },
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                        enabled = !state.loading,
                        singleLine = true,
                        isError = state.error != null,
                        label = { Text("Tautan playlist") },
                        leadingIcon = { Icon(Icons.Rounded.Link, null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                    )
                    state.error?.let { error ->
                        Text(error, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = ::submit, enabled = canSubmit,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = NadaMuted)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (state.loading) "Membaca playlist…" else "Lihat pratinjau",
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CollectionArtwork(preview.tracks, false, Modifier.size(68.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(preview.name, style = MaterialTheme.typography.titleLarge,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text("${preview.tracks.size} lagu akan disimpan", Modifier.padding(top = 4.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = NadaMuted)
                            }
                        }
                        if (preview.skipped > 0) Text("${preview.skipped} entri dilewati karena duplikat atau tidak tersedia.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = NadaMuted)
                        if (preview.incomplete) Text("Sebagian playlist berhasil dibaca. Hanya lagu di bawah ini yang akan disimpan.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = NadaMuted)
                        TextButton(onClick = reset) { Text("Ganti tautan") }
                        HorizontalDivider(Modifier.padding(bottom = 4.dp))
                    }
                    items(preview.tracks, key = { it.id }) { track ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(track, Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = NadaMuted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (preview != null) {
                HorizontalDivider()
                Button(onClick = save, modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp).heightIn(min = 48.dp)) {
                    Text("Simpan ke Koleksi")
                }
            }
        }
    }
}
