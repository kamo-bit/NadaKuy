package app.nada.player.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.nada.player.MusicState
import app.nada.player.MusicViewModel
import app.nada.player.lyrics.LyricsDocument
import app.nada.player.lyrics.LyricsState
import app.nada.player.lyrics.activeLyricIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun LyricsScreen(state: MusicState, model: MusicViewModel, modifier: Modifier, back: () -> Unit) {
    val track = state.current ?: return
    LaunchedEffect(track) { model.loadLyrics(track) }
    DisposableEffect(model) { onDispose { model.closeLyrics() } }
    val lyrics = model.lyricsState.takeIf { it.trackId == track.id } ?: LyricsState(trackId = track.id, loading = true)
    val position by produceState(model.lyricsPosition(), track.id) {
        while (isActive) { value = model.lyricsPosition(); delay(200) }
    }
    val context = LocalContext.current
    // Keyed by track so a pick can never land on the next song after a skip.
    var browsing by rememberSaveable(track.id) { mutableStateOf(false) }
    if (browsing) {
        LyricsBrowser(track, lyrics.document, model, modifier) { browsing = false }
        return
    }
    Column(modifier.background(NadaBackground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Tutup lirik") }
            Text("Lirik", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { browsing = true }, enabled = !lyrics.loading) { Icon(Icons.Rounded.Search, "Cari lirik lain") }
            IconButton(onClick = { model.loadLyrics(track, refresh = true) }, enabled = !lyrics.loading) { Icon(Icons.Rounded.Refresh, "Muat ulang lirik") }
        }
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, Modifier.padding(top = 4.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        }
        val document = lyrics.document
        if (document != null && lyrics.error != null) Text(lyrics.error, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        when {
            lyrics.loading && document == null -> LyricsMessage("Mencari lirik…", "Mencocokkan judul, artis, dan durasi lagu.", Modifier.weight(1f), loading = true)
            lyrics.error != null && document == null -> LyricsMessage("Lirik belum bisa dimuat", lyrics.error, Modifier.weight(1f), retry = { model.loadLyrics(track, refresh = true) })
            document == null -> LyricsMessage("Lirik belum ditemukan", "Pencarian otomatis belum menemukan lirik yang cocok. Cari sendiri di LRCLIB, lalu pilih lirik yang sesuai.",
                Modifier.weight(1f), browse = { browsing = true })
            document.instrumental -> LyricsMessage("Lagu instrumental", "Penyedia lirik menandai rekaman ini tanpa vokal.", Modifier.weight(1f))
            else -> LyricsBody(document, position, state.canSeek, model::seek, Modifier.weight(1f).fillMaxWidth())
        }
        if (document != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://lrclib.net".toUri())) } }) { Text("Lirik: LRCLIB") }
            Spacer(Modifier.weight(1f))
            if (document.chosen) Text("Dipilih manual", color = NadaMuted, style = MaterialTheme.typography.labelSmall)
            else if (lyrics.cached) Text("Tersimpan di perangkat", color = NadaMuted, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            LinearProgressIndicator(progress = { if (state.duration > 0) (position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(), color = NadaGreen, drawStopIndicator = {})
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(timeText(position / 1000), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = model::previous, enabled = state.canPrevious) { Icon(Icons.Rounded.SkipPrevious, "Lagu sebelumnya") }
                FilledIconButton(onClick = model::togglePlayback, modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = NadaGreen, contentColor = NadaBackground)) {
                    if (state.buffering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = NadaBackground)
                    else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Jeda" else "Lanjutkan")
                }
                IconButton(onClick = model::next, enabled = state.canNext) { Icon(Icons.Rounded.SkipNext, "Lagu berikutnya") }
                Spacer(Modifier.weight(1f))
                Text(timeText(state.duration / 1000), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun LyricsBody(document: LyricsDocument, position: Long, canSeek: Boolean, seek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var showRomaji by rememberSaveable { mutableStateOf(true) }
    var follow by rememberSaveable(document.id) { mutableStateOf(true) }
    val list = rememberSaveable(document.id, saver = LazyListState.Saver) { LazyListState() }
    val current = if (document.synced) activeLyricIndex(document.lines, position) else -1
    val anchor = with(LocalDensity.current) { 80.dp.roundToPx() }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(document.id, current, follow, showRomaji) {
        if (follow && current >= 0) list.animateScrollToItem(current, scrollOffset = -anchor)
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when { !document.synced -> "Lirik teks · waktu belum tersedia untuk versi ini"
                !follow -> "Gulir untuk membaca · ketuk Ikuti lagu untuk kembali"
                else -> "Mengikuti pemutaran · ketuk baris untuk lompat" },
                Modifier.weight(1f).padding(end = 8.dp), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
            if (document.romanized) FilterChip(selected = showRomaji, onClick = { showRomaji = !showRomaji }, label = { Text("Romaji") })
        }
        if (document.romanized && showRomaji) Text("Romaji otomatis · bacaan nama dapat berbeda", Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = NadaMuted, style = MaterialTheme.typography.labelSmall)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(document.lines) { index, line ->
                    val active = index == current
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = document.synced && canSeek) { seek(line.timeMs) }
                        .semantics { selected = active }.padding(vertical = 8.dp)) {
                        Text(line.text.ifBlank { "Musik" }, color = if (active) NadaGreen else NadaMuted, style = MaterialTheme.typography.headlineMedium)
                        if (showRomaji && line.romaji != null) Text(line.romaji, Modifier.padding(top = 8.dp),
                            color = if (active) MaterialTheme.colorScheme.onSurface else NadaMuted, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (!follow && document.synced) FilledTonalButton(onClick = { follow = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Ikuti lagu")
            }
        }
    }
}

@Composable
private fun LyricsMessage(title: String, message: String, modifier: Modifier, loading: Boolean = false,
    retry: (() -> Unit)? = null, browse: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = NadaGreen)
            else Icon(Icons.Rounded.Lyrics, null, Modifier.size(36.dp), tint = NadaMuted)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, color = NadaMuted, style = MaterialTheme.typography.bodyLarge)
            browse?.let { Button(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Cari lirik")
            } }
            retry?.let { OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Coba lagi") } }
        }
    }
}
