package app.nada.player

import android.app.Application
import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import app.nada.player.data.LocalPlaylist
import app.nada.player.data.MusicRepository
import app.nada.player.data.Track
import app.nada.player.downloads.OfflineLibrary
import app.nada.player.downloads.OfflineState
import app.nada.player.playback.PlaybackService
import app.nada.player.playback.QueuedTrack
import app.nada.player.playback.QueueCommands
import app.nada.player.playback.SleepCommands
import app.nada.player.playback.toMediaItem
import app.nada.player.playback.toTrack
import app.nada.player.playback.togglePlayback
import app.nada.player.playback.playQueueItem
import app.nada.player.playback.upcomingQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.nada.player.spotify.SpotifyAuth
import app.nada.player.spotify.SpotifyApi
import app.nada.player.spotify.SpotifyRemote
import app.nada.player.youtube.YouTubeCatalog

data class MusicState(
    val home: List<Track> = emptyList(), val loading: Boolean = true, val homeError: String? = null,
    val query: String = "", val results: List<Track> = emptyList(), val searching: Boolean = false, val searchError: String? = null,
    val liked: List<Track> = emptyList(), val history: List<Track> = emptyList(), val playlists: List<LocalPlaylist> = emptyList(),
    val current: Track? = null, val queue: List<QueuedTrack> = emptyList(), val playing: Boolean = false,
    val buffering: Boolean = false, val position: Long = 0, val duration: Long = 0,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF,
    val sleepDeadline: Long = 0, val sleepRemainingSeconds: Long = 0, val sleepEndOfTrack: Boolean = false, val recentSearches: List<String> = emptyList(),
    val message: String? = null, val genreTracks: List<Track> = emptyList(), val genreLoading: Boolean = false, val genreError: String? = null,
    val source: String = "audius", val spotifyClientId: String = "", val spotifyAuthorized: Boolean = false,
    val spotifyConnected: Boolean = false, val spotifyBusy: Boolean = false, val spotifyError: String? = null,
    val queueError: String? = null,
    val queueHasCurrent: Boolean = true,
    val youtubeVideos: Boolean = false,
    val offline: OfflineState = OfflineState(),
    val canSeek: Boolean = true, val canNext: Boolean = true, val canPrevious: Boolean = true, val canShuffle: Boolean = true, val canRepeat: Boolean = true,
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicRepository(application)
    private val offlineLibrary = OfflineLibrary.get(application)
    private val spotifyAuth = SpotifyAuth(application)
    private val spotifyApi = SpotifyApi(spotifyAuth)
    private val connectionPrefs = application.getSharedPreferences("spotify_connection", android.content.Context.MODE_PRIVATE)
    private val initialSource = if (!connectionPrefs.getBoolean("youtube_v13_default", false)) {
        connectionPrefs.edit().putString("source", "youtube").putBoolean("youtube_v13_default", true).apply()
        "youtube"
    } else connectionPrefs.getString("source", "youtube").orEmpty().takeIf { it in listOf("audius", "spotify", "youtube") } ?: "youtube"
    var state by mutableStateOf(MusicState(home = repository.cachedHome, liked = repository.liked, history = repository.history, playlists = repository.playlists, recentSearches = repository.searchHistory,
        source = initialSource, youtubeVideos = connectionPrefs.getBoolean("youtube_videos", false), spotifyClientId = spotifyAuth.clientId, spotifyAuthorized = spotifyAuth.authorized))
        private set
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private var genreJob: Job? = null
    private var authJob: Job? = null
    private var queueJob: Job? = null
    private var spotifyLikedTracks = emptyList<Track>()
    private var foregroundActivity: java.lang.ref.WeakReference<Activity>? = null
    private var pendingSpotifyPrompt = false
    private val spotifyRemote = SpotifyRemote(changed = { playback ->
        if (state.source == "spotify") {
            if (playback.track != null && playback.track.id != state.current?.id) {
                repository.rememberPlayed(playback.track); syncLibrary()
                loadSpotifyLike(playback.track)
            }
            state = state.copy(current = playback.track, playing = playback.playing, position = playback.position, duration = playback.duration,
                shuffle = playback.shuffle, repeat = playback.repeat, buffering = false, canSeek = playback.canSeek, canNext = playback.canNext,
                canPrevious = playback.canPrevious, canShuffle = playback.canShuffle, canRepeat = playback.canRepeat)
        }
    }, status = { connected, error ->
        state = state.copy(spotifyConnected = connected, spotifyError = error, message = error ?: state.message)
        if (connected) { pendingSpotifyPrompt = false; state.current?.takeIf { it.source == "spotify" }?.let(::loadSpotifyLike) }
    })
    private val future = MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { updatePlayer() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let { repository.rememberPlayed(it.toTrack()); syncLibrary() }
        }
        override fun onPlayerError(error: PlaybackException) {
            if (state.source == "spotify") return
            android.util.Log.w("NadaPlayback", "Playback failed: ${error.errorCodeName}")
            state = state.copy(buffering = false, message = if (state.current?.source == "youtube") "Audio YouTube belum bisa diputar. Coba putar ulang atau pilih lagu lain." else "Lagu belum bisa diputar. Coba putar ulang atau pilih lagu lain.")
        }
    }

    init {
        viewModelScope.launch { offlineLibrary.state.collect { state = state.copy(offline = it) } }
        future.addListener({
            runCatching { future.get() }.onSuccess { player ->
                controller = player
                player.addListener(listener)
                if (player.currentMediaItem?.toTrack()?.source?.let { it != state.source } == true) { player.pause(); player.clearMediaItems() }
                updatePlayer()
            }.onFailure { state = state.copy(message = "Pemutar belum siap. Tutup lalu buka kembali aplikasi.") }
        }, ContextCompat.getMainExecutor(application))
        if (state.source != "audius") state = state.copy(home = emptyList())
        refresh()
        viewModelScope.launch { while (isActive) { updatePlayer(); delay(750) } }
    }

    fun refresh() {
        if (homeJob?.isActive == true) return
        homeJob = viewModelScope.launch {
            state = state.copy(loading = true, homeError = null)
            try {
                val year = java.time.Year.now().value
                val tracks = when (state.source) {
                    "spotify" -> if (state.spotifyAuthorized) spotifyApi.search("year:${year - 1}-$year") else emptyList()
                    "youtube" -> YouTubeCatalog.search("musik Indonesia", state.youtubeVideos)
                    else -> repository.trending()
                }
                state = state.copy(home = tracks, loading = false)
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state = state.copy(loading = false, homeError = catalogMessage(error)) }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        state = state.copy(query = query, searching = query.isNotBlank(), searchError = null, results = if (query.isBlank()) emptyList() else state.results)
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(400)
            try { state = state.copy(results = when (state.source) {
                "spotify" -> spotifyApi.search(query.trim())
                "youtube" -> YouTubeCatalog.search(query.trim(), state.youtubeVideos)
                else -> repository.search(query.trim())
            }, searching = false) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state = state.copy(searching = false, searchError = catalogMessage(error)) }
        }
    }

    fun loadGenre(genre: String) {
        genreJob?.cancel()
        state = state.copy(genreTracks = emptyList(), genreLoading = true, genreError = null)
        genreJob = viewModelScope.launch {
            try { state = state.copy(genreTracks = when (state.source) {
                "spotify" -> spotifyApi.search("genre:\"${genre.lowercase()}\"")
                "youtube" -> YouTubeCatalog.search("$genre music", state.youtubeVideos)
                else -> repository.trending(genre)
            }, genreLoading = false) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state = state.copy(genreLoading = false, genreError = catalogMessage(error)) }
        }
    }

    fun play(tracks: List<Track>, index: Int = 0, shuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        val selected = tracks[index.coerceIn(tracks.indices)]
        if (selected.source == "spotify") {
            if (state.source != "spotify") selectSource("spotify")
            controller?.pause()
            spotifyRemote.play(selected)
            return
        }
        if (state.source != selected.source) selectSource(selected.source)
        val player = controller ?: run { state = state.copy(message = "Pemutar sedang disiapkan. Coba sebentar lagi."); return }
        val local = tracks.filter { it.playableInNada }
        player.setMediaItems(local.map { it.toMediaItem() }, local.indexOf(selected).coerceAtLeast(0), 0)
        player.shuffleModeEnabled = shuffled
        player.prepare()
        player.play()
        updatePlayer()
    }

    fun togglePlayback() { if (state.source == "spotify") spotifyRemote.toggle() else controller?.togglePlayback() }
    fun download(track: Track) {
        try {
            offlineLibrary.add(track)
            state = state.copy(message = "Lagu ditambahkan ke Unduhan")
        } catch (error: Exception) { state = state.copy(message = "Unduhan belum bisa dimulai. Coba lagi saat aplikasi terbuka.") }
    }
    fun pauseDownload(id: String) = changeDownload { offlineLibrary.pause(id) }
    fun resumeDownload(id: String) = changeDownload { offlineLibrary.resume(id) }
    fun removeDownload(id: String) = changeDownload { offlineLibrary.remove(id) }
    fun refreshDownloads() = offlineLibrary.refresh()
    private fun changeDownload(action: () -> Unit) {
        try { action() } catch (error: Exception) { state = state.copy(message = "Unduhan belum bisa diubah. Coba lagi.") }
    }
    fun playQueueItem(mediaIndex: Int) { controller?.playQueueItem(mediaIndex); updatePlayer() }
    fun removeQueueItem(mediaIndex: Int) {
        controller?.let { if (mediaIndex in 0 until it.mediaItemCount && mediaIndex != it.currentMediaItemIndex) it.removeMediaItem(mediaIndex) }
        updatePlayer()
    }
    fun setSleepTimer(minutes: Int) {
        if (state.source == "spotify") { state = state.copy(message = "Atur timer Spotify melalui aplikasi Spotify."); return }
        val player = controller ?: run { state = state.copy(message = "Putar lagu lebih dahulu untuk memakai timer."); return }
        val mode = when { minutes == -1 -> "end"; minutes == 0 -> "off"; else -> "duration" }
        val result = player.sendCustomCommand(SleepCommands.set, Bundle().apply { putString(SleepCommands.MODE, mode); putLong(SleepCommands.DURATION, minutes * 60_000L) })
        result.addListener({
            val success = runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
            state = state.copy(message = if (!success) "Timer belum dapat diatur. Coba lagi." else when (minutes) { -1 -> "Musik berhenti setelah lagu ini"; 0 -> "Timer tidur dinonaktifkan"; else -> "Musik berhenti dalam $minutes menit" })
            updatePlayer()
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    fun rememberSearch(query: String = state.query) { repository.rememberSearch(query); state = state.copy(recentSearches = repository.searchHistory) }
    fun clearSearchHistory() { repository.clearSearchHistory(); state = state.copy(recentSearches = emptyList()) }
    fun next() { if (state.source == "spotify") spotifyRemote.next() else controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.seekTo(0) } }
    fun previous() { if (state.source == "spotify") spotifyRemote.previous() else controller?.let { if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seek(position: Long) { if (state.source == "spotify") spotifyRemote.seek(position) else controller?.seekTo(position.coerceAtLeast(0)); updatePlayer() }
    fun toggleShuffle() { if (state.source == "spotify") spotifyRemote.shuffle() else controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun toggleRepeat() { if (state.source == "spotify") spotifyRemote.repeat() else controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }
    fun playNext(track: Track) {
        if (track.source == "spotify") { spotifyRemote.queue(track); return }
        if (state.source == "spotify") selectSource(track.source)
        val player = controller
        if (player == null || player.mediaItemCount == 0) { play(listOf(track)); return }
        val result = player.sendCustomCommand(QueueCommands.playNext, Bundle().apply { putString(QueueCommands.TRACK, track.toJson().toString()) })
        result.addListener({
            val success = runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
            state = state.copy(message = if (success) "Ditambahkan ke antrean berikutnya" else "Antrean belum dapat diubah. Coba lagi.")
            updatePlayer()
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    fun toggleLike(track: Track) {
        if (track.source == "spotify") spotifyRemote.toggleLike(track) { added -> rememberSpotifyLike(track, added) }
        else { repository.toggleLike(track); syncLibrary() }
    }
    fun createPlaylist(name: String): String { val result = repository.createPlaylist(name); syncLibrary(); return result.id }
    fun addToPlaylist(id: String, track: Track) { repository.addToPlaylist(id, track); syncLibrary(); state = state.copy(message = "Lagu ditambahkan ke playlist") }
    fun removeFromPlaylist(id: String, track: Track) { repository.removeFromPlaylist(id, track.id); syncLibrary() }
    fun deletePlaylist(id: String) { repository.deletePlaylist(id); syncLibrary() }
    fun renamePlaylist(id: String, name: String) { repository.renamePlaylist(id, name); syncLibrary() }
    fun dismissMessage() { state = state.copy(message = null) }

    private fun syncLibrary() { state = state.copy(liked = repository.liked + spotifyLikedTracks, history = repository.history, playlists = repository.playlists) }
    private fun loadSpotifyLike(track: Track) { spotifyRemote.libraryState(track) { added -> rememberSpotifyLike(track, added) } }
    private fun rememberSpotifyLike(track: Track, added: Boolean) {
        spotifyLikedTracks = spotifyLikedTracks.filterNot { it.id == track.id }.let { if (added) listOf(track) + it else it }
        syncLibrary()
    }
    private fun updatePlayer() {
        if (state.source == "spotify") { spotifyRemote.tick(); return }
        controller?.let { player ->
            state = state.copy(current = player.currentMediaItem?.toTrack(), playing = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING, position = player.currentPosition.coerceAtLeast(0),
                duration = player.duration.takeIf { it > 0 } ?: ((player.currentMediaItem?.mediaMetadata?.extras?.getInt("duration") ?: 0) * 1000L),
                shuffle = player.shuffleModeEnabled, repeat = player.repeatMode,
                canSeek = true, canNext = true, canPrevious = true, canShuffle = true, canRepeat = true,
                sleepDeadline = player.sessionExtras.getLong(SleepCommands.DEADLINE), sleepEndOfTrack = player.sessionExtras.getBoolean(SleepCommands.END_OF_TRACK),
                sleepRemainingSeconds = ((player.sessionExtras.getLong(SleepCommands.DEADLINE) - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000,
                queue = player.upcomingQueue(), queueHasCurrent = true)
        }
    }

    fun selectSource(source: String) {
        if (source == state.source) return
        require(source in listOf("audius", "spotify", "youtube"))
        homeJob?.cancel(); searchJob?.cancel(); genreJob?.cancel(); queueJob?.cancel()
        controller?.pause()
        controller?.clearMediaItems()
        controller?.sendCustomCommand(SleepCommands.set, Bundle().apply { putString(SleepCommands.MODE, "off") })
        if (source != "spotify") {
            authJob?.cancel(); spotifyAuth.cancel(); state = state.copy(spotifyBusy = false)
            if (spotifyRemote.connected) spotifyRemote.pause()
            spotifyRemote.disconnect()
        }
        connectionPrefs.edit().putString("source", source).apply()
        state = state.copy(source = source, home = emptyList(), results = emptyList(), query = "", current = null, queue = emptyList(), playing = false, spotifyConnected = spotifyRemote.connected,
            loading = false, searching = false, genreLoading = false, homeError = null, searchError = null, genreTracks = emptyList(), genreError = null,
            sleepDeadline = 0, sleepEndOfTrack = false, sleepRemainingSeconds = 0, position = 0, duration = 0, queueError = null)
        refresh()
        if (source == "spotify" && state.spotifyAuthorized) foregroundActivity?.get()?.let { spotifyRemote.connect(it, spotifyAuth.clientId, prompt = pendingSpotifyPrompt) }
    }
    fun selectYouTube(videos: Boolean) {
        val changed = state.youtubeVideos != videos
        connectionPrefs.edit().putBoolean("youtube_videos", videos).apply()
        state = state.copy(youtubeVideos = videos)
        if (state.source != "youtube") selectSource("youtube")
        else if (changed) {
            homeJob?.cancel(); searchJob?.cancel(); genreJob?.cancel()
            state = state.copy(home = emptyList(), results = emptyList(), genreTracks = emptyList())
            refresh(); if (state.query.isNotBlank()) search(state.query)
        }
    }
    private fun catalogMessage(error: Exception): String = when (state.source) {
        "spotify" -> spotifyMessage(error)
        "youtube" -> YouTubeCatalog.message(error)
        else -> "Musik belum dapat dimuat. Periksa internet lalu coba lagi."
    }
    fun configureSpotify(clientId: String) {
        try {
            if (app.nada.player.spotify.SpotifyConfig.validClientId(clientId.trim()) && spotifyAuth.clientId != clientId.trim()) {
                authJob?.cancel(); spotifyRemote.disconnect()
                state = state.copy(spotifyConnected = false)
            }
            spotifyAuth.configure(clientId); state = state.copy(spotifyClientId = spotifyAuth.clientId, spotifyAuthorized = spotifyAuth.authorized, spotifyError = null)
        }
        catch (error: IllegalArgumentException) { state = state.copy(spotifyError = error.message) }
    }
    fun connectSpotify(activity: Activity) {
        if (state.spotifyBusy) return
        pendingSpotifyPrompt = true
        selectSource("spotify")
        authJob = viewModelScope.launch {
            state = state.copy(spotifyBusy = true, spotifyError = null)
            try {
                if (!spotifyAuth.authorized) spotifyAuth.authorize(activity)
                state = state.copy(spotifyAuthorized = true)
                homeJob?.cancel(); refresh()
                pendingSpotifyPrompt = true
                foregroundActivity?.get()?.let { spotifyRemote.connect(it, spotifyAuth.clientId, prompt = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { state = state.copy(spotifyError = spotifyMessage(error)) }
            finally { state = state.copy(spotifyBusy = false) }
        }
    }
    fun resumeSpotify(activity: Activity) {
        foregroundActivity = java.lang.ref.WeakReference(activity)
        if (state.source == "spotify" && state.spotifyAuthorized && !state.spotifyBusy) spotifyRemote.connect(activity, spotifyAuth.clientId, prompt = pendingSpotifyPrompt)
    }
    fun suspendSpotify() { foregroundActivity = null; spotifyRemote.disconnect(); state = state.copy(spotifyConnected = false) }
    fun disconnectSpotify() {
        authJob?.cancel(); spotifyAuth.disconnect(); spotifyRemote.disconnect()
        spotifyLikedTracks = emptyList(); syncLibrary()
        homeJob?.cancel(); searchJob?.cancel(); queueJob?.cancel()
        state = state.copy(spotifyAuthorized = false, spotifyConnected = false, spotifyBusy = false, spotifyError = null,
            home = emptyList(), results = emptyList(), current = null, playing = false, queue = emptyList(), loading = false)
    }
    fun cancelSpotifyConnection() { authJob?.cancel(); spotifyAuth.cancel(); state = state.copy(spotifyBusy = false) }
    fun refreshSpotifyQueue() {
        if (state.source != "spotify" || !state.spotifyAuthorized || queueJob?.isActive == true) return
        queueJob = viewModelScope.launch {
            try {
                val queue = spotifyApi.queue()
                state = state.copy(queue = (listOfNotNull(queue.current) + queue.upcoming).mapIndexed { index, track -> QueuedTrack(index, track) }, queueError = null, queueHasCurrent = queue.current != null)
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state = state.copy(queue = emptyList(), queueError = spotifyMessage(error)) }
        }
    }
    private fun spotifyMessage(error: Exception): String = if (error is IllegalStateException || error is IllegalArgumentException) error.message ?: "Hubungkan kembali Spotify." else "Koneksi Spotify belum berhasil. Periksa internet lalu coba lagi."
    override fun onCleared() { spotifyAuth.cancel(); spotifyRemote.disconnect(); controller?.removeListener(listener); MediaController.releaseFuture(future); super.onCleared() }
}
