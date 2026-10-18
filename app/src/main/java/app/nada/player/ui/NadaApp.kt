@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nada.player.ui

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import app.nada.player.MusicState
import app.nada.player.MusicViewModel
import app.nada.player.data.Track
import app.nada.player.downloads.OfflineStatus
import coil3.compose.AsyncImage

private data class Genre(val title: String, val query: String, val color: Color)
private val genres = listOf(
    Genre("Pop", "Pop", Color(0xFFB82B78)), Genre("Elektronik", "Electronic", Color(0xFF286F57)),
    Genre("Hip-hop", "Hip-Hop/Rap", Color(0xFFAD5824)), Genre("Jazz", "Jazz", Color(0xFF6050A4)),
    Genre("Ambient", "Ambient", Color(0xFF245A87)), Genre("Rock", "Rock", Color(0xFFBB3535)),
    Genre("House", "House", Color(0xFF1B7180)), Genre("Akustik", "Acoustic", Color(0xFF90642D)),
)
private val coverColors = listOf(Color(0xFF376152), Color(0xFF534A74), Color(0xFF77533D), Color(0xFF36546D))

@Composable
fun NadaApp(model: MusicViewModel = viewModel()) {
    val s = model.state
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var collection by rememberSaveable { mutableStateOf<String?>(null) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<Track?>(null) }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var creating by remember { mutableStateOf(false) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showSpotify by rememberSaveable { mutableStateOf(false) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    val artworkColor by rememberArtworkColor(s.current)
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingDownload by remember { mutableStateOf<Track?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDownload?.let(model::download)
        pendingDownload = null
    }
    fun startDownload(track: Track) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = track
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else model.download(track)
    }
    fun showDownloads() { fullPlayer = false; collection = "downloads" }
    LaunchedEffect(s.source) { if (collection != "downloads") collection = null; fullPlayer = false; showQueue = false; showSleepTimer = false }
    LaunchedEffect(showQueue) { if (showQueue) model.refreshSpotifyQueue() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); model.dismissMessage() } }
    LaunchedEffect(collection) {
        if (collection?.startsWith("genre:") == true && s.genreTracks.isEmpty() && !s.genreLoading) model.loadGenre(collection!!.removePrefix("genre:"))
    }
    BackHandler(fullPlayer || collection != null || tab != 0) {
        when { fullPlayer -> fullPlayer = false; collection != null -> collection = null; else -> tab = 0 }
    }
    fun openGenre(genre: Genre) { collection = "genre:${genre.query}"; model.loadGenre(genre.query) }
    val navigation = listOf("Beranda" to Icons.Rounded.Home, "Cari" to Icons.Rounded.Search, "Koleksi" to Icons.Rounded.LibraryMusic)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(containerColor = Color(0xFF090909), header = { BrandMark(Modifier.padding(vertical = 20.dp)) }) {
                navigation.forEachIndexed { index, (label, icon) -> NavigationRailItem(selected = tab == index,
                    onClick = { tab = index; collection = null }, icon = { Icon(icon, label) }, label = { Text(label) }) }
            }
            Scaffold(Modifier.weight(1f), containerColor = NadaBackground, contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sumber musik", Modifier.weight(1f), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { showSources = true }) {
                            if (s.source == "spotify") SpotifyLogo() else Text(sourceName(s))
                            Icon(Icons.Rounded.KeyboardArrowDown, "Pilih sumber musik")
                        }
                    }
                },
                snackbarHost = { if (!fullPlayer) SnackbarHost(snackbar) }, bottomBar = {
                    Column {
                        s.current?.let { MiniPlayer(it, s, model, artworkColor) { fullPlayer = true } }
                        if (!wide) NavigationBar(containerColor = Color(0xFF0A0A0A), tonalElevation = 0.dp) {
                            navigation.forEachIndexed { index, (label, icon) -> NavigationBarItem(
                                selected = tab == index, onClick = { tab = index; collection = null },
                                icon = { Icon(icon, null) }, label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                                    selectedIconColor = Color.White, selectedTextColor = Color.White,
                                    unselectedIconColor = NadaMuted, unselectedTextColor = NadaMuted)) }
                        } else Spacer(Modifier.navigationBarsPadding())
                    }
                }) { padding ->
                val content = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                when {
                    collection == "downloads" -> DownloadsScreen(s, model, content, { collection = null }, { collection = null; tab = 1 })
                    collection != null -> {
                        val key = collection!!
                        val playlist = s.playlists.find { key == "playlist:${it.id}" }
                        val genre = genres.find { key == "genre:${it.query}" }
                        val tracks = when { key == "liked" -> s.liked; key == "history" -> s.history; genre != null -> s.genreTracks; else -> playlist?.tracks.orEmpty() }
                        CollectionScreen(when (key) { "liked" -> "Lagu yang disukai"; "history" -> "Baru diputar"; else -> genre?.title ?: playlist?.name ?: "Playlist" },
                            tracks, s, model, content, genre != null && s.genreLoading, if (genre != null) s.genreError else null,
                            { genre?.let { model.loadGenre(it.query) } }, { collection = null }, { options = it },
                            if (playlist != null) ({ deleting = playlist.id }) else null,
                            key, if (playlist != null) ({ playlistName = playlist.name; renaming = playlist.id }) else null)
                    }
                    tab == 0 -> HomeScreen(s, model, content, { options = it }, ::openGenre, { collection = "history" }, { collection = "liked" }, { showInfo = true }, { showSpotify = true })
                    tab == 1 -> SearchScreen(s, model, content, { options = it }, ::openGenre)
                    else -> LibraryScreen(s, content, { collection = it }, { playlistName = ""; creating = true })
                }
            }
        }
        if (fullPlayer && s.current != null) FullPlayer(s, model, artworkColor, { fullPlayer = false }, { showQueue = true }, { options = s.current }, { showSleepTimer = true }) {
            val track = s.current
            val saved = s.offline.tracks.find { it.id == track.id }
            if (saved != null && saved.status != OfflineStatus.FAILED) showDownloads() else startDownload(track)
        }
        if (fullPlayer) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
    }
    options?.let { track ->
        ModalBottomSheet(onDismissRequest = { options = null }, containerColor = NadaSurface, properties = NadaSheetProperties) {
            TrackRow(track, s, { model.play(listOf(track)); options = null }, {}, false)
            HorizontalDivider(Modifier.padding(16.dp), color = Color(0xFF393939))
            SheetAction(if (s.liked.any { it.id == track.id }) "Hapus dari lagu disukai" else "Simpan ke lagu disukai", Icons.Rounded.FavoriteBorder) { model.toggleLike(track); options = null }
            SheetAction(if (track.source == "spotify") "Tambahkan ke antrean Spotify" else "Putar berikutnya", Icons.AutoMirrored.Rounded.QueueMusic) { model.playNext(track); options = null }
            SheetAction("Tambahkan ke playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { options = null; addTrack = track }
            if (track.playableInNada) {
                val saved = s.offline.tracks.find { it.id == track.id }
                if (saved?.status != OfflineStatus.REMOVING) SheetAction(when {
                    saved?.ready == true -> "Lihat di Unduhan"
                    saved?.status == OfflineStatus.PAUSED -> "Lanjutkan unduhan"
                    saved?.status == OfflineStatus.DOWNLOADING || saved?.status == OfflineStatus.QUEUED -> "Lihat progres unduhan"
                    saved?.status == OfflineStatus.FAILED -> "Unduh ulang"
                    else -> "Unduh untuk offline"
                }, if (saved?.ready == true) Icons.Rounded.DownloadDone else Icons.Rounded.Download) {
                    options = null
                    when {
                        saved?.status == OfflineStatus.PAUSED -> model.resumeDownload(track.id)
                        saved == null || saved.status == OfflineStatus.FAILED -> startDownload(track)
                        else -> showDownloads()
                    }
                }
            }
            if (s.current != null && s.source != "spotify") SheetAction("Timer tidur", Icons.Rounded.Timer) { options = null; showSleepTimer = true }
            if (collection?.startsWith("playlist:") == true) SheetAction("Hapus dari playlist ini", Icons.Rounded.RemoveCircleOutline) {
                model.removeFromPlaylist(collection!!.removePrefix("playlist:"), track); options = null
            }
            SheetAction(when (track.source) { "spotify" -> "Buka di Spotify"; "youtube" -> "Buka di YouTube"; else -> "Lihat di Audius" }, Icons.AutoMirrored.Rounded.OpenInNew) {
                options = null
                if (track.source == "spotify") openSpotify(context, track.permalink) else if (track.source == "youtube") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, track.permalink.toUri())) }
                } else {
                    val url = if (track.permalink.startsWith("/")) "https://audius.co${track.permalink}" else "https://audius.co"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showQueue) QueueSheet(s, model) { showQueue = false }
    if (showSleepTimer) SleepTimerSheet(s, model) { showSleepTimer = false }
    if (showSpotify) SpotifyConnectionSheet(s, model) { showSpotify = false }
    if (showSources) MusicSourceSheet(s, model, { showSpotify = true }) { showSources = false }
    if (addTrack != null && !creating) AlertDialog(onDismissRequest = { addTrack = null }, title = { Text("Tambahkan ke playlist") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            s.playlists.forEach { playlist -> TextButton(onClick = { model.addToPlaylist(playlist.id, addTrack!!); addTrack = null }, modifier = Modifier.fillMaxWidth()) { Text(playlist.name, Modifier.weight(1f)) } }
            TextButton(onClick = { playlistName = ""; creating = true }) { Icon(Icons.Rounded.Add, null); Text("Buat playlist baru") }
        } }, confirmButton = { TextButton(onClick = { addTrack = null }) { Text("Batal") } })
    if (creating) AlertDialog(onDismissRequest = { creating = false; addTrack = null }, title = { Text("Beri nama playlistmu") },
        text = { OutlinedTextField(playlistName, { playlistName = it.take(60) }, singleLine = true, label = { Text("Nama playlist") }, placeholder = { Text("Lagu untuk perjalanan") }) },
        confirmButton = { TextButton(enabled = playlistName.isNotBlank(), onClick = {
            val id = model.createPlaylist(playlistName); addTrack?.let { model.addToPlaylist(id, it) }; addTrack = null; creating = false
        }) { Text("Buat") } }, dismissButton = { TextButton(onClick = { creating = false; addTrack = null }) { Text("Batal") } })
    if (deleting != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Hapus playlist?") },
        text = { Text("Playlist ini akan dihapus dari perangkat. Lagu yang kamu sukai tetap tersimpan.") },
        confirmButton = { TextButton(onClick = { model.deletePlaylist(deleting!!); deleting = null; collection = null }) { Text("Hapus") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } })
    if (renaming != null) AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Ubah nama playlist") },
        text = { OutlinedTextField(playlistName, { playlistName = it.take(60) }, singleLine = true, label = { Text("Nama playlist") }) },
        confirmButton = { TextButton(enabled = playlistName.isNotBlank(), onClick = { model.renamePlaylist(renaming!!, playlistName); renaming = null }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text("Batal") } })
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("NadaKuy. Musik untukmu.") },
        text = { Text("Cari musik dari YouTube Music, YouTube, atau Audius tanpa akun NadaKuy. Spotify tetap tersedia dengan akun Premium dan aplikasi resminya.\n\nKoleksi dan riwayat NadaKuy tersimpan di perangkat ini. Katalog dan ketersediaan tiap sumber berbeda; koneksi internet diperlukan.") },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Mengerti") } })
}

@Composable
private fun HomeScreen(s: MusicState, model: MusicViewModel, modifier: Modifier, options: (Track) -> Unit, genre: (Genre) -> Unit, history: () -> Unit, liked: () -> Unit, info: () -> Unit, spotify: () -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = info, modifier = Modifier.semantics { contentDescription = "Tentang NadaKuy" }) { BrandMark(Modifier.size(34.dp)) }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(true, {}, label = { Text("Semua") }, shape = CircleShape, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NadaGreen, selectedLabelColor = Color.Black))
                    FilterChip(false, liked, label = { Text("Disukai") }, shape = CircleShape)
                    FilterChip(false, history, label = { Text("Baru diputar") }, shape = CircleShape)
                }
            }
        }
        if (s.source == "spotify" && (!s.spotifyAuthorized || !s.spotifyConnected)) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (s.spotifyAuthorized) "Aktifkan pemutar Spotify" else "Musikmu di Spotify", style = MaterialTheme.typography.headlineMedium)
                Text(if (s.spotifyAuthorized) "Hubungkan pemutar untuk mendengarkan lagu dari NadaKuy." else "Hubungkan akun Premium untuk mencari dan mendengarkan musik dari katalog Spotify.", color = NadaMuted)
                Button(onClick = spotify) { Text("Hubungkan Spotify") }
            }
        }
        s.homeError?.let { item { ErrorBlock(it, model::refresh, s.home.isNotEmpty()) } }
        if (s.home.isEmpty() && s.loading) item { LoadingBlock("Menemukan musik untukmu…") }
        if (s.home.isNotEmpty()) {
            item {
                val quick = (s.history.filter { it.source == s.source } + s.home).distinctBy(Track::id).take(8)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { track -> Row(Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A2A)).clickable { model.play(quick, quick.indexOf(track)) }, verticalAlignment = Alignment.CenterVertically) {
                            Cover(track, Modifier.size(58.dp)); Text(track.title, Modifier.weight(1f).padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    } }
                }
            }
            item { SectionTitle(when (s.source) { "spotify" -> "Jelajahi Spotify"; "youtube" -> "Musik Indonesia"; else -> "Sedang ramai" }) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(s.home.take(16), key = Track::id) { track -> TrackCard(track) { model.play(s.home, s.home.indexOf(track)) } }
            } }
            if (s.history.any { it.source == s.source }) {
                item { SectionTitle("Baru kamu putar", history) }
                item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(s.history.filter { it.source == s.source }.take(10), key = Track::id) { track -> TrackCard(track) { model.play(s.history, s.history.indexOf(track)) } } } }
            }
            if (s.home.size > 16) item { SectionTitle("Temukan favorit berikutnya") }
            items(s.home.drop(16).take(8), key = Track::id) { track -> TrackRow(track, s, { model.play(s.home, s.home.indexOf(track)) }, { options(track) }) }
        }
        item { SectionTitle("Sesuai suasana hatimu") }; item { GenreGrid(genres.take(4), genre) }
        item { Text("Musik dari ${sourceName(s)}", Modifier.fillMaxWidth().padding(top = 32.dp), color = NadaMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun SearchScreen(s: MusicState, model: MusicViewModel, modifier: Modifier, options: (Track) -> Unit, genre: (Genre) -> Unit) {
    val focus = LocalFocusManager.current
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("Cari", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 20.dp)) }
        if (s.source == "youtube") item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!s.youtubeVideos, { focus.clearFocus(); model.selectYouTube(false) }, label = { Text("Lagu · YouTube Music") }, shape = CircleShape)
                FilterChip(s.youtubeVideos, { focus.clearFocus(); model.selectYouTube(true) }, label = { Text("Video · YouTube") }, shape = CircleShape)
            }
        }
        item { TextField(s.query, model::search, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = "Cari lagu atau artis" },
            placeholder = { Text("Lagu atau artis yang ingin kamu dengar") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (s.query.isNotEmpty()) IconButton(onClick = { model.search("") }) { Icon(Icons.Rounded.Close, "Hapus pencarian") } },
            shape = RoundedCornerShape(8.dp), colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                focusedTextColor = Color.Black, unfocusedTextColor = Color.Black, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedPlaceholderColor = Color(0xFF555555), unfocusedPlaceholderColor = Color(0xFF555555), focusedLeadingIconColor = Color.Black,
                unfocusedLeadingIconColor = Color.Black, focusedTrailingIconColor = Color.Black, unfocusedTrailingIconColor = Color.Black),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); model.rememberSearch(); model.search(s.query) })) }
        when {
            s.query.isBlank() -> {
                if (s.recentSearches.isNotEmpty()) {
                    item { Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Pencarian terbaru", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = model::clearSearchHistory) { Text("Hapus", color = NadaMuted) }
                    } }
                    items(s.recentSearches) { query -> SheetAction(query, Icons.Rounded.History) { model.search(query); model.rememberSearch(query) } }
                }
                item { SectionTitle("Jelajahi semua") }; item { GenreGrid(genres, genre) }
            }
            s.searching -> item { LoadingBlock("Mencari musik…") }
            s.searchError != null -> item { ErrorBlock(s.searchError, { model.search(s.query) }) }
            s.results.isEmpty() -> item { EmptyBlock(Icons.Rounded.Search, "Belum ada hasil", "Coba nama lagu atau artis lain di katalog ${sourceName(s)}.") }
            else -> { item { SectionTitle("Lagu") }; items(s.results, key = Track::id) { track -> TrackRow(track, s, { focus.clearFocus(); model.rememberSearch(); model.play(s.results, s.results.indexOf(track)) }, { options(track) }) } }
        }
    }
}

@Composable
private fun CollectionScreen(title: String, tracks: List<Track>, s: MusicState, model: MusicViewModel, modifier: Modifier,
    loading: Boolean, error: String?, retry: () -> Unit, back: () -> Unit, options: (Track) -> Unit, delete: (() -> Unit)?, kind: String, rename: (() -> Unit)?) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    var byName by rememberSaveable(title) { mutableStateOf(false) }
    val matching = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    val visible = if (byName) matching.sortedBy { it.title.lowercase() } else matching
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }; Spacer(Modifier.weight(1f))
            rename?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Edit, "Ubah nama playlist") } }
            delete?.let { IconButton(onClick = it) { Icon(Icons.Rounded.DeleteOutline, "Hapus playlist") } }
        } }
        item { Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF274438), NadaBackground))).padding(horizontal = 24.dp, vertical = 22.dp)) {
            CollectionArtwork(tracks, kind == "liked", Modifier.size(192.dp).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp)); Text(title, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp)); Text("${tracks.size} lagu · ${tracks.sumOf { it.duration } / 60} menit", color = NadaMuted)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when { kind.startsWith("genre:") -> "Musik dari ${sourceName(s)}"; kind == "liked" && s.source == "spotify" -> "Status Spotify dari lagu yang dibuka di NadaKuy"; else -> "Dibuat untukmu · Di perangkat ini" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(enabled = tracks.isNotEmpty(), onClick = { model.play(tracks, shuffled = true) }) { Icon(Icons.Rounded.Shuffle, "Acak semua lagu", tint = NadaMuted) }
                FilledIconButton(enabled = tracks.isNotEmpty(), onClick = { model.play(tracks) }, modifier = Modifier.size(58.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = NadaGreen, contentColor = Color.Black)) { Icon(Icons.Rounded.PlayArrow, "Putar semua lagu", Modifier.size(34.dp)) }
            }
        } }
        if (tracks.isNotEmpty()) item { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Cari di playlist ini") }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
            IconButton(onClick = { byName = !byName }) { Icon(Icons.Rounded.SortByAlpha, if (byName) "Urutkan semula" else "Urutkan menurut nama", tint = if (byName) NadaGreen else NadaMuted) }
        } }
        when {
            loading -> item { LoadingBlock("Memuat musik…") }
            error != null -> item { ErrorBlock(error, retry) }
            tracks.isEmpty() -> item { EmptyBlock(Icons.Rounded.MusicNote, "Belum ada lagu", "Temukan lagu di Beranda atau Cari, lalu simpan lewat menu tiga titik.") }
            visible.isEmpty() -> item { EmptyBlock(Icons.Rounded.Search, "Tidak ada lagu yang cocok", "Coba judul atau artis lain di koleksi ini.") }
            else -> items(visible, key = Track::id) { track -> TrackRow(track, s, { model.play(visible, visible.indexOf(track)) }, { options(track) }) }
        }
    }
}

@Composable
private fun MiniPlayer(track: Track, s: MusicState, model: MusicViewModel, artworkColor: Color, expand: () -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(artworkColor).clickable(onClick = expand)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 8.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(track, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(track.artist, color = Color(0xFFDAE6DF), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { model.toggleLike(track) }) { Icon(if (s.liked.any { it.id == track.id }) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (s.liked.any { it.id == track.id }) "Batal sukai lagu" else "Sukai lagu", tint = if (s.liked.any { it.id == track.id }) NadaGreen else Color.White) }
            IconButton(onClick = model::togglePlayback, enabled = s.source != "spotify" || s.spotifyConnected) { if (s.buffering) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) else Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) "Jeda" else "Lanjutkan", Modifier.size(30.dp)) }
        }
        LinearProgressIndicator(progress = { if (s.duration > 0) (s.position.toFloat() / s.duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 8.dp), color = Color.White, trackColor = Color(0xFF60706A), drawStopIndicator = {})
    }
}

@Composable
private fun FullPlayer(s: MusicState, model: MusicViewModel, background: Color, close: () -> Unit, queue: () -> Unit, options: () -> Unit, timer: () -> Unit, download: () -> Unit) {
    val track = s.current ?: return
    var seekPreview by remember(track.id) { mutableStateOf<Float?>(null) }
    val liked = s.liked.any { it.id == track.id }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(background, Color(0xFF131D19), Color(0xFF101411))))
        .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.Rounded.KeyboardArrowDown, "Tutup pemutar", Modifier.size(30.dp)) }
            Text("Sedang diputar", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = options) { Icon(Icons.Rounded.MoreHoriz, "Opsi lagu") }
        }
        Box(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 30.dp), contentAlignment = Alignment.Center) { Cover(track, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Color(0xFFCCD7D1), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp)) }
            IconButton(onClick = { model.toggleLike(track) }) { Icon(if (liked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                if (liked) "Batal sukai lagu" else "Sukai lagu", tint = if (liked) NadaGreen else Color.White, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        Slider(value = seekPreview ?: s.position.toFloat().coerceAtMost(s.duration.toFloat().coerceAtLeast(1f)), onValueChange = { seekPreview = it }, enabled = s.duration > 0 && s.canSeek && (s.source != "spotify" || s.spotifyConnected),
            onValueChangeFinished = { seekPreview?.let { model.seek(it.toLong()) }; seekPreview = null }, valueRange = 0f..s.duration.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Posisi pemutaran" },
            thumb = { Box(Modifier.size(10.dp).background(Color.White, CircleShape)) },
            track = { sliderState -> SliderDefaults.Track(sliderState, Modifier.height(3.dp), thumbTrackGapSize = 0.dp, trackInsideCornerSize = 1.5.dp, drawStopIndicator = {}, colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f))) })
        Row(Modifier.fillMaxWidth()) { Text(timeText((seekPreview?.toLong() ?: s.position) / 1000), color = NadaMuted, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.weight(1f)); Text(timeText(s.duration / 1000), color = NadaMuted, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::toggleShuffle, enabled = s.canShuffle && (s.source != "spotify" || s.spotifyConnected)) { Icon(Icons.Rounded.Shuffle, if (s.shuffle) "Matikan acak" else "Aktifkan acak", tint = if (!s.canShuffle) NadaMuted else if (s.shuffle) NadaGreen else Color.White) }
            IconButton(onClick = model::previous, enabled = s.canPrevious && (s.source != "spotify" || s.spotifyConnected), modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Lagu sebelumnya", Modifier.size(38.dp), tint = if (s.canPrevious) Color.White else NadaMuted) }
            FilledIconButton(onClick = model::togglePlayback, enabled = s.source != "spotify" || s.spotifyConnected, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                if (s.buffering) CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp) else Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) "Jeda" else "Lanjutkan", Modifier.size(42.dp))
            }
            IconButton(onClick = model::next, enabled = s.canNext && (s.source != "spotify" || s.spotifyConnected), modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Lagu berikutnya", Modifier.size(38.dp), tint = if (s.canNext) Color.White else NadaMuted) }
            IconButton(onClick = model::toggleRepeat, enabled = s.canRepeat && (s.source != "spotify" || s.spotifyConnected)) { Icon(if (s.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (s.repeat) { Player.REPEAT_MODE_ALL -> "Ulangi satu lagu"; Player.REPEAT_MODE_ONE -> "Matikan pengulangan"; else -> "Ulangi semua lagu" }, tint = if (s.repeat != Player.REPEAT_MODE_OFF) NadaGreen else Color.White) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Headphones, null, tint = NadaGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (s.source == "spotify") "Pemutar Spotify" else "Perangkat ini", color = NadaGreen, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (track.playableInNada) {
                val saved = s.offline.tracks.find { it.id == track.id }
                IconButton(onClick = download) {
                    Icon(if (saved?.ready == true) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                        if (saved?.ready == true) "Lagu tersedia offline" else "Unduh lagu", tint = if (saved?.ready == true) NadaGreen else Color.White)
                }
            }
            if (s.source != "spotify") IconButton(onClick = timer) { Icon(Icons.Rounded.Timer, "Timer tidur", tint = if (sleepTimerLabel(s) != null) NadaGreen else Color.White) }
            IconButton(onClick = queue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Buka antrean") }
        }
        sleepTimerLabel(s)?.let { Text("Timer tidur · $it", color = NadaGreen, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp)) }
        Text("Diputar dari ${when (track.source) { "spotify" -> "Spotify"; "youtube" -> track.genre; else -> "Audius" }}", color = NadaMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 24.dp))
        if (track.source == "youtube") {
            val context = LocalContext.current
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, track.permalink.toUri())) } }, modifier = Modifier.padding(bottom = 24.dp)) {
                Icon(Icons.Rounded.PlayCircleOutline, null); Spacer(Modifier.width(8.dp)); Text("Buka di YouTube")
            }
        }
        if (track.source == "spotify") {
            val context = LocalContext.current
            TextButton(onClick = { openSpotify(context, track.permalink) }, modifier = Modifier.padding(bottom = 24.dp)) { SpotifyLogo(); Spacer(Modifier.width(12.dp)); Text("Buka Spotify") }
        }
    }
}

@Composable
internal fun TrackRow(track: Track, state: MusicState, play: () -> Unit, options: () -> Unit, showOptions: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(onClick = play).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(track, Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = if (state.current?.id == track.id) NadaGreen else Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = NadaMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.liked.any { it.id == track.id }) Icon(Icons.Rounded.Favorite, null, tint = NadaGreen, modifier = Modifier.size(16.dp))
        if (state.offline.tracks.any { it.id == track.id && it.ready }) Icon(Icons.Rounded.DownloadDone, "Tersedia offline", tint = NadaGreen, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        if (showOptions) IconButton(onClick = options) { Icon(Icons.Rounded.MoreVert, "Opsi ${track.title}", tint = NadaMuted) }
    }
}

@Composable
private fun TrackCard(track: Track, play: () -> Unit) {
    Column(Modifier.width(158.dp).clickable(onClick = play)) {
        Cover(track, Modifier.size(158.dp).clip(RoundedCornerShape(6.dp)))
        Text(track.title, Modifier.padding(top = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Text(track.artist, Modifier.padding(top = 4.dp), color = NadaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Cover(track: Track, modifier: Modifier) {
    Box(modifier.background(coverColors[Math.floorMod(track.id.hashCode(), coverColors.size)]), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = .4f), modifier = Modifier.fillMaxSize(.4f))
        AsyncImage(model = track.artwork, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Box(modifier.size(42.dp).clip(CircleShape).background(NadaGreen), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = Color.Black, modifier = Modifier.size(27.dp)) }
}

@Composable
private fun SectionTitle(text: String, more: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 26.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        more?.let { TextButton(onClick = it) { Text("Lihat semua", color = NadaMuted, style = MaterialTheme.typography.labelSmall) } }
    }
}

@Composable
private fun GenreGrid(data: List<Genre>, choose: (Genre) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        data.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { genre -> Box(Modifier.weight(1f).height(108.dp).clip(RoundedCornerShape(8.dp)).background(genre.color).clickable { choose(genre) }) {
                Text(genre.title, Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                Icon(Icons.Rounded.Album, null, Modifier.align(Alignment.BottomEnd).offset(8.dp, 12.dp).size(70.dp).rotate(22f), tint = Color.White.copy(alpha = .22f))
            } }
        } }
    }
}

@Composable
internal fun CollectionRow(title: String, subtitle: String, icon: ImageVector, color: Color, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = .45f)))), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(32.dp), tint = Color.White) }
        Column(Modifier.weight(1f).padding(start = 16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(4.dp)); Text(subtitle, color = NadaMuted, style = MaterialTheme.typography.bodyMedium) }
        Icon(Icons.Rounded.ChevronRight, null, tint = NadaMuted)
    }
}

@Composable
internal fun SheetAction(title: String, icon: ImageVector, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 20.dp, vertical = 16.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = NadaMuted); Text(title, Modifier.padding(start = 20.dp), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun EmptyBlock(icon: ImageVector, title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(52.dp), tint = NadaMuted); Spacer(Modifier.height(20.dp)); Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
        Text(detail, Modifier.padding(top = 12.dp), color = NadaMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadingBlock(text: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp); Text(text, Modifier.padding(top = 16.dp), color = NadaMuted) }
}

@Composable
private fun ErrorBlock(text: String, retry: () -> Unit, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compact) 12.dp else 36.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(text, textAlign = TextAlign.Center, color = NadaMuted); TextButton(onClick = retry) { Text("Coba lagi") } }
}

private fun timeText(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
