package app.nada.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nada.player.MusicViewModel
import app.nada.player.data.Track
import app.nada.player.lyrics.LyricsCandidate
import app.nada.player.lyrics.LyricsDocument
import app.nada.player.lyrics.LyricsMatching
import app.nada.player.lyrics.LyricsSearchState
import kotlin.math.roundToLong

/** Lets the listener search LRCLIB themselves when automatic matching finds nothing or picks the wrong record. */
@Composable
fun LyricsBrowser(track: Track, current: LyricsDocument?, model: MusicViewModel, modifier: Modifier, close: () -> Unit) {
    var query by rememberSaveable(track.id) { mutableStateOf(LyricsMatching.query(track)) }
    LaunchedEffect(track.id) { model.openLyricsSearch(track, query) }
    val search = model.lyricsSearch.takeIf { it.trackId == track.id } ?: LyricsSearchState(trackId = track.id, loading = true)
    var previewId by rememberSaveable(track.id) { mutableStateOf<Long?>(null) }
    val preview = search.results.firstOrNull { it.document.id == previewId }
    BackHandler { if (preview != null) previewId = null else close() }
    if (preview != null) {
        LyricsPreview(preview, preview.document.id == current?.id, modifier, back = { previewId = null }) {
            model.useLyrics(track, preview)
            close()
        }
        return
    }
    val focus = LocalFocusManager.current
    Column(modifier.background(NadaBackground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali ke lirik") }
            Text("Cari lirik", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
        Text("Pilih lirik untuk ${track.title} · ${track.artist}", Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            color = NadaMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true, placeholder = { Text("Judul, artis, atau album") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Hapus kata kunci") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); model.searchLyrics(track, query) }))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                search.loading -> BrowserMessage("Mencari di LRCLIB…", null, loading = true)
                search.error != null -> BrowserMessage(search.error, "Pastikan internet tersambung.") {
                    OutlinedButton(onClick = { model.searchLyrics(track, query) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Coba lagi") }
                }
                !search.searched -> BrowserMessage("Ketik judul atau artis", "Lalu tekan cari pada keyboard.")
                search.results.isEmpty() -> BrowserMessage("Tidak ada hasil", "Coba kata kunci lebih singkat, misalnya judul lagu saja.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("${search.results.size} hasil · lirik tersinkron diutamakan", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = NadaMuted, style = MaterialTheme.typography.labelMedium)
                    }
                    items(search.results, key = { it.document.id }) { candidate ->
                        LyricsResult(candidate, candidate.document.id == current?.id) { focus.clearFocus(); previewId = candidate.document.id }
                    }
                }
            }
        }
        if (current?.chosen == true) TextButton(onClick = { model.loadLyrics(track, refresh = true, automatic = true); close() },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Kembali ke pencocokan otomatis")
        }
        Text("Hasil dari LRCLIB", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = NadaMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LyricsResult(candidate: LyricsCandidate, inUse: Boolean, open: () -> Unit) {
    val document = candidate.document
    Surface(onClick = open, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = NadaSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(document.title, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(timeText(document.duration.roundToLong()), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Text(credits(candidate), color = NadaMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LyricsBadges(candidate, inUse)
            val excerpt = document.lines.map { it.text }.filter(String::isNotBlank).take(2)
            if (excerpt.isNotEmpty()) Text(excerpt.joinToString("\n"), color = NadaMuted, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LyricsPreview(candidate: LyricsCandidate, inUse: Boolean, modifier: Modifier, back: () -> Unit, use: () -> Unit) {
    val document = candidate.document
    Column(modifier.background(NadaBackground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali ke hasil pencarian") }
            Text("Pratinjau lirik", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(document.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${credits(candidate)} · ${timeText(document.duration.roundToLong())}", color = NadaMuted,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LyricsBadges(candidate, inUse)
            Text(when {
                document.instrumental -> "Rekaman ini ditandai tanpa vokal."
                document.synced -> "Lirik akan mengikuti pemutaran lagu ini."
                candidate.timedAvailable -> "Durasi versi ini berbeda lebih dari 4 detik dari lagu yang diputar, jadi lirik tampil sebagai teks tanpa waktu."
                else -> "Versi ini hanya memiliki lirik teks tanpa waktu."
            }, color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(document.lines) { line ->
                Row {
                    if (document.synced) Text(timeText(line.timeMs / 1000), Modifier.width(48.dp).padding(top = 2.dp),
                        color = NadaMuted, style = MaterialTheme.typography.labelMedium)
                    Text(line.text.ifBlank { "♪" }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Button(onClick = use, enabled = !inUse, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
            Icon(if (inUse) Icons.Rounded.Check else Icons.Rounded.Lyrics, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(if (inUse) "Sedang dipakai" else "Gunakan lirik ini")
        }
    }
}

@Composable
private fun LyricsBadges(candidate: LyricsCandidate, inUse: Boolean) {
    val document = candidate.document
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            document.instrumental -> LyricsBadge("Instrumental")
            document.synced -> LyricsBadge("Tersinkron", highlighted = true)
            candidate.timedAvailable -> LyricsBadge("Teks · durasi beda")
            else -> LyricsBadge("Teks saja")
        }
        candidate.durationDifference?.roundToLong()?.let { LyricsBadge(if (it <= 1) "Durasi cocok" else "Selisih ${if (it < 60) "$it dtk" else timeText(it)}") }
        if (inUse) LyricsBadge("Dipakai", highlighted = true)
    }
}

@Composable
private fun LyricsBadge(text: String, highlighted: Boolean = false) {
    Text(text, Modifier.background(if (highlighted) NadaGreen.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
        .padding(horizontal = 10.dp, vertical = 4.dp), color = if (highlighted) NadaGreen else NadaMuted,
        style = MaterialTheme.typography.labelMedium, maxLines = 1)
}

@Composable
private fun BrowserMessage(title: String, message: String?, loading: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = NadaGreen)
            else Icon(Icons.Rounded.Search, null, Modifier.size(36.dp), tint = NadaMuted)
            Text(title, style = MaterialTheme.typography.titleMedium)
            message?.let { Text(it, color = NadaMuted, style = MaterialTheme.typography.bodyMedium) }
            action?.invoke()
        }
    }
}

private fun credits(candidate: LyricsCandidate) =
    listOf(candidate.document.artist, candidate.album).filter(String::isNotBlank).distinct().joinToString(" · ")
