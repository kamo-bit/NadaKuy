@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nada.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nada.player.MusicState
import app.nada.player.MusicViewModel
import app.nada.player.data.Track
import app.nada.player.playback.QueuedTrack
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun QueueSheet(state: MusicState, model: MusicViewModel, dismiss: () -> Unit) {
    var snapshot by remember { mutableStateOf<List<Track>?>(null) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = NadaSurface, properties = NadaSheetProperties,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        QueueSheetContent(state, onSave = { snapshot = state.queue.map { it.track }.distinctBy(Track::id) },
            onMove = model::moveQueueItem, onPlay = { model.playQueueItem(it); dismiss() }, onRemove = model::removeQueueItem,
            onSpotify = { openSpotify(context) }, radio = { RadioQueueStatus(state, model::setAutoplay, model::retryRadio) })
    }
    snapshot?.let { tracks ->
        QueuePlaylistDialog(tracks.size, { snapshot = null }) { name ->
            model.saveQueuePlaylist(name, tracks); snapshot = null; dismiss()
        }
    }
}

@Composable
internal fun QueuePlaylistDialog(count: Int, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Simpan antrean") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("$count lagu dari lagu saat ini dan antrean berikutnya akan disimpan ke playlist baru. Lagu yang sama disimpan sekali.")
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nama playlist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { save(name.trim()) }, enabled = name.isNotBlank()) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Batal") } })
}

@Composable
internal fun ColumnScope.QueueSheetContent(state: MusicState, onSave: () -> Unit, onMove: (String, String) -> Unit,
    onPlay: (Int) -> Unit, onRemove: (Int) -> Unit, onSpotify: () -> Unit = {}, radio: @Composable () -> Unit = {}) {
    val local = state.source != "spotify"
    val indicators = rememberTrackIndicators(state)
    val upcoming = if (state.queueHasCurrent) state.queue.drop(1) else state.queue
    val byId = remember(upcoming) { upcoming.associateBy(QueuedTrack::entryId) }
    var displayed by remember { mutableStateOf(upcoming) }
    var dragging by remember { mutableStateOf(false) }
    val currentId = state.queue.firstOrNull()?.entryId.takeIf { state.queueHasCurrent }
    LaunchedEffect(upcoming, dragging, currentId) { if (!dragging) displayed = upcoming }
    // A transition during a gesture makes the new current item ineligible immediately.
    val rows = displayed.filter { it.entryId in byId }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = displayed.indexOfFirst { it.entryId == from.key }
        val b = displayed.indexOfFirst { it.entryId == to.key }
        if (a >= 0 && b >= 0 && a != b && local) {
            displayed = displayed.toMutableList().apply { add(b, removeAt(a)) }
            onMove(from.key as String, to.key as String)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Antrean", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (local) TextButton(onClick = onSave, enabled = state.queue.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, Modifier.size(20.dp))
            Text("Simpan", Modifier.padding(start = 8.dp))
        }
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "status") {
            if (local) {
                Text(state.queueSaveError ?: "Antrean dan posisi lagu disimpan otomatis di perangkat.",
                    Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    color = if (state.queueSaveError != null) MaterialTheme.colorScheme.error else NadaMuted,
                    style = MaterialTheme.typography.bodySmall)
                radio()
            } else {
                Text(state.queueError ?: "Antrean ini mengikuti pemutar Spotify. Kelola urutannya di aplikasi Spotify.", Modifier.padding(horizontal = 24.dp), color = NadaMuted)
                TextButton(onClick = onSpotify, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Buka Spotify") }
            }
        }
        item(key = "current") {
            Text("Sedang diputar", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            state.queue.firstOrNull()?.takeIf { state.queueHasCurrent }?.let {
                TrackRow(it.track, indicators.currentId == it.track.id, it.track.id in indicators.likedIds,
                    it.track.id in indicators.offlineIds, {}, {}, false) }
                ?: Text("Belum ada lagu diputar.", Modifier.padding(horizontal = 24.dp), color = NadaMuted)
        }
        item(key = "next") {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Berikutnya", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (state.shuffle) Icon(Icons.Rounded.Shuffle, "Mode acak aktif", tint = NadaGreen, modifier = Modifier.size(20.dp))
                Text("  ${upcoming.size} lagu", color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
            }
            if (local && upcoming.size > 1) Text("Geser pegangan untuk mengatur urutan.", Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp), color = NadaMuted, style = MaterialTheme.typography.bodySmall)
            if (upcoming.isEmpty()) Text(if (local) "Pilih Putar berikutnya dari menu lagu untuk menambah antrean." else "Tambahkan lagu melalui menu lagu di Spotify.",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = NadaMuted)
        }
        itemsIndexed(rows, key = { _, entry -> entry.entryId }) { index, entry ->
            val actual = byId[entry.entryId] ?: return@itemsIndexed
            ReorderableItem(reorder, key = entry.entryId, enabled = local) { isDragging ->
                var menu by remember { mutableStateOf(false) }
                fun move(offset: Int) { rows.getOrNull(index + offset)?.let { onMove(entry.entryId, it.entryId) }; menu = false }
                Surface(color = NadaSurface, tonalElevation = if (isDragging) 4.dp else 0.dp, shadowElevation = if (isDragging) 4.dp else 0.dp) {
                    Row(Modifier.fillMaxWidth().semantics {
                        if (local) customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Pindahkan ke atas") { move(-1); true })
                            if (index < rows.lastIndex) add(CustomAccessibilityAction("Pindahkan ke bawah") { move(1); true })
                        }
                    }.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable(enabled = local) { onPlay(actual.mediaIndex) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(entry.track, Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                                Text(entry.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(entry.track.artist, color = NadaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                if (entry.automatic) Text("Ditambahkan radio", color = NadaGreen, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (local) {
                            IconButton(onClick = { onRemove(actual.mediaIndex) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.RemoveCircleOutline, "Hapus ${entry.track.title} dari antrean", tint = NadaMuted) }
                            Spacer(Modifier.width(8.dp))
                            Box {
                                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).draggableHandle(
                                    enabled = rows.size > 1, onDragStarted = { dragging = true }, onDragStopped = { dragging = false })) {
                                    Icon(Icons.Rounded.DragHandle, "Atur urutan ${entry.track.title}", tint = NadaMuted)
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Pindahkan ke atas") }, enabled = index > 0, onClick = { move(-1) })
                                    DropdownMenuItem(text = { Text("Pindahkan ke bawah") }, enabled = index < rows.lastIndex, onClick = { move(1) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
