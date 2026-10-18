package app.nada.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nada.player.MusicState
import app.nada.player.data.Track

@Composable
fun LibraryScreen(state: MusicState, modifier: Modifier, open: (String) -> Unit, create: () -> Unit, settings: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    val filtered = state.playlists.filter { it.name.contains(query, ignoreCase = true) }
    val playlists = if (alphabetical) filtered.sortedBy { it.name.lowercase() } else filtered.asReversed()
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = settings) { Icon(Icons.Rounded.Settings, "Pengaturan", tint = NadaGreen) }
            Text("Koleksi Kamu", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Rounded.Search, "Cari koleksi") }
            IconButton(onClick = create) { Icon(Icons.Rounded.Add, "Buat playlist") }
        } }
        item { Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Semua", "Playlist", "Disukai", "Unduhan").forEachIndexed { index, label -> FilterChip(filter == index, { filter = index }, label = { Text(label) }, shape = RoundedCornerShape(50)) }
        } }
        if (searching) item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true, placeholder = { Text("Cari di koleksimu") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }) }
        item { Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { alphabetical = !alphabetical }) { Icon(Icons.Rounded.SwapVert, null, Modifier.size(20.dp), tint = Color.White); Text(if (alphabetical) "Nama" else "Baru dibuat", Modifier.padding(start = 6.dp), color = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "Tampilkan daftar" else "Tampilkan grid") }
        } }
        if ((filter == 0 || filter == 3) && "Unduhan".contains(query, true)) item {
            val ready = state.offline.tracks.count { it.ready }
            val pending = state.offline.tracks.count { !it.ready }
            CollectionRow("Unduhan", "$ready lagu siap offline" + if (pending > 0) " · $pending lainnya" else "", Icons.Rounded.DownloadForOffline, Color(0xFF286151)) { open("downloads") }
        }
        if ((filter == 0 || filter == 2) && "Lagu yang disukai".contains(query, true)) item {
            CollectionRow("Lagu yang disukai", "Playlist · ${state.liked.size} lagu", Icons.Rounded.Favorite, Color(0xFF6454B6)) { open("liked") }
        }
        if (filter == 0 && state.history.isNotEmpty() && "Baru diputar".contains(query, true)) item {
            CollectionRow("Baru diputar", "${state.history.size} lagu terakhir", Icons.Rounded.History, Color(0xFF286151)) { open("history") }
        }
        if (filter == 2 && !"Lagu yang disukai".contains(query, true)) item {
            Text("Tidak ada koleksi yang cocok. Coba nama lain.", Modifier.padding(24.dp), color = NadaMuted)
        }
        if (filter == 0 || filter == 1) {
            if (grid) items(playlists.chunked(2)) { pair -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { playlist -> Column(Modifier.weight(1f).clickable { open("playlist:${playlist.id}") }) {
                    CollectionArtwork(playlist.tracks, false, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(playlist.name, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Playlist · ${playlist.tracks.size} lagu", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            } } else items(playlists, key = { it.id }) { playlist ->
                Row(Modifier.fillMaxWidth().clickable { open("playlist:${playlist.id}") }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CollectionArtwork(playlist.tracks, false, Modifier.size(72.dp))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Playlist · ${playlist.tracks.size} lagu", Modifier.padding(top = 4.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (playlists.isEmpty()) item {
                Column(Modifier.padding(24.dp)) {
                    Text(if (query.isNotBlank()) "Tidak ada playlist yang cocok" else "Musikmu, sesukamu.", style = MaterialTheme.typography.titleMedium)
                    Text(if (query.isNotBlank()) "Coba nama lain." else "Buat playlist untuk menyimpan lagu favoritmu.", Modifier.padding(top = 8.dp), color = NadaMuted)
                    if (query.isBlank()) Button(onClick = create, modifier = Modifier.padding(top = 16.dp)) { Text("Buat playlist") }
                }
            }
        }
        item { Text("Disimpan di perangkat ini", Modifier.padding(24.dp), color = NadaMuted, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun CollectionArtwork(tracks: List<Track>, liked: Boolean, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(Color(0xFF6454B6), Color(0xFF223F37)))), contentAlignment = Alignment.Center) {
        when {
            liked -> Icon(Icons.Rounded.Favorite, null, Modifier.fillMaxSize(.36f), tint = Color.White)
            tracks.size >= 4 -> Column { tracks.take(4).chunked(2).forEach { pair -> Row(Modifier.weight(1f)) { pair.forEach { Cover(it, Modifier.weight(1f).fillMaxHeight()) } } } }
            tracks.isNotEmpty() -> Cover(tracks.first(), Modifier.fillMaxSize())
            else -> Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(.4f), tint = Color.White)
        }
    }
}
