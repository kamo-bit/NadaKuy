package app.nada.player

import android.app.Application
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import app.nada.player.data.LocalPlaylist
import app.nada.player.data.MusicRepository
import app.nada.player.data.CatalogQuery
import app.nada.player.data.Track
import app.nada.player.data.CollectionBackup
import app.nada.player.data.LibraryBackup
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.catch
import app.nada.player.updates.AppUpdates
import app.nada.player.updates.UpdateState
import app.nada.player.updates.AppUpdater
import app.nada.player.updates.UpdateDownloadState
import app.nada.player.updates.UpdatePackage
import app.nada.player.updates.AppVersion
import app.nada.player.lyrics.LyricsCandidate
import app.nada.player.lyrics.LyricsRepository
import app.nada.player.lyrics.LyricsSearchState
import app.nada.player.lyrics.LyricsState
import kotlinx.coroutines.ensureActive
import app.nada.player.spotify.SpotifyAuth
import app.nada.player.spotify.SpotifyApi
import app.nada.player.spotify.SpotifyRemote
import app.nada.player.youtube.YouTubeCatalog
import app.nada.player.youtube.YouTubeLinks
import app.nada.player.youtube.YouTubePlaylistPreview
import app.nada.player.recommendations.ListeningHistory
import app.nada.player.recommendations.QualifiedListening
import app.nada.player.recommendations.RecommendationPreferences
import app.nada.player.recommendations.HomeRecommendations
import app.nada.player.recommendations.HomeRecommendationInput
import app.nada.player.playback.RadioCommands

data class MusicState(
    val home: List<Track> = emptyList(), val loading: Boolean = true, val homeError: String? = null,
    val recommendations: List<Track> = emptyList(), val frequent: List<Track> = emptyList(),
    val recommendationArtists: List<String> = emptyList(), val recommendationsLoading: Boolean = false, val recommendationsError: String? = null,
    val recommendationsHaveMore: Boolean = false, val homeRefreshing: Boolean = false,
    val recommendationPreferences: RecommendationPreferences = RecommendationPreferences(),
    val radioTitle: String? = null, val radioLoading: Boolean = false, val radioError: String? = null,
    val query: String = "", val results: List<Track> = emptyList(), val searching: Boolean = false, val searchError: String? = null,
    val liked: List<Track> = emptyList(), val history: List<Track> = emptyList(), val playlists: List<LocalPlaylist> = emptyList(),
    val current: Track? = null, val queue: List<QueuedTrack> = emptyList(), val playing: Boolean = false,
    val buffering: Boolean = false,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF,
    val sleepDeadline: Long = 0, val sleepEndOfTrack: Boolean = false, val recentSearches: List<String> = emptyList(),
    val message: String? = null, val genreTracks: List<Track> = emptyList(), val genreLoading: Boolean = false, val genreError: String? = null,
    val source: String = "audius", val spotifyClientId: String = "", val spotifyAuthorized: Boolean = false,
    val spotifyConnected: Boolean = false, val spotifyBusy: Boolean = false, val spotifyError: String? = null,
    val queueError: String? = null,
    val queueHasCurrent: Boolean = true,
    val queueSaveError: String? = null,
    val normalizeVolume: Boolean = true,
    val youtubeVideos: Boolean = false,
    val offline: OfflineState = OfflineState(),
    val batchDownloading: Boolean = false,
    val canSeek: Boolean = true, val canNext: Boolean = true, val canPrevious: Boolean = true, val canShuffle: Boolean = true, val canRepeat: Boolean = true,
)

data class PlaybackProgress(val position: Long = 0, val duration: Long = 0, val sleepRemainingSeconds: Long = 0)

data class CollectionTransferState(val busy: String? = null, val message: String? = null,
    val error: String? = null, val pending: CollectionBackup? = null)

internal data class PlaylistImportState(
    val loading: Boolean = false,
    val preview: YouTubePlaylistPreview? = null,
    val error: String? = null,
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicRepository(application)
    private val catalogCache = (application as NadaApplication).catalogCache
    private val recommendationCatalog = app.nada.player.recommendations.RadioCatalog(application as NadaApplication)
    private val recommendationSettings = (application as NadaApplication).recommendationSettings
    private val playbackSettings = (application as NadaApplication).playbackSettings
    private val lyricsRepository = LyricsRepository(application)
    private var lyricsJob: Job? = null
    var lyricsState by mutableStateOf(LyricsState())
        private set
    private var lyricsSearchJob: Job? = null
    var lyricsSearch by mutableStateOf(LyricsSearchState())
        private set
    val installedVersion: String = application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "0.0.0"
    var transferState by mutableStateOf(CollectionTransferState())
        private set
    internal var playlistImport by mutableStateOf(PlaylistImportState())
        private set
    private var playlistImportJob: Job? = null
    var updateState by mutableStateOf(UpdateState())
        private set
    private val updateNotices = (application as NadaApplication).updateNoticePreferences
    var updateNotice by mutableStateOf<app.nada.player.updates.AppRelease?>(null)
        private set
    var updateDownload by mutableStateOf(UpdateDownloadState())
        private set
    var updateActionError by mutableStateOf<String?>(null)
        private set
    var preparingUpdateInstall by mutableStateOf(false)
        private set
    private val appUpdater = AppUpdater.get(application)
    var updatesNavigationRequest by mutableStateOf(0)
        private set
    fun requestUpdatesScreen() { updatesNavigationRequest++ }
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
    var progress by mutableStateOf(PlaybackProgress())
        private set
    private var controller: MediaController? = null
    private var queueCurrentIndex = C.INDEX_UNSET
    private var progressJob: Job? = null
    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private val listeningHistory = ListeningHistory.get(application)
    private val spotifyListening = QualifiedListening()
    private val homeRecommendations = HomeRecommendations(viewModelScope, loadPool = { input, seed, force, cached ->
        if (input.source == "spotify") {
            val artist = app.nada.player.recommendations.ListeningRecommendations.artistName(seed.artist)
            if (input.spotifyAuthorized) spotifyApi.search("artist:\"${artist.replace("\"", "")}\"") else emptyList()
        } else recommendationCatalog.pool(seed, force, cached)
    })
    private var refreshJob: Job? = null
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
            progress = PlaybackProgress(playback.position, playback.duration)
            state = state.copy(current = playback.track, playing = playback.playing,
                shuffle = playback.shuffle, repeat = playback.repeat, buffering = false, canSeek = playback.canSeek, canNext = playback.canNext,
                canPrevious = playback.canPrevious, canShuffle = playback.canShuffle, canRepeat = playback.canRepeat)
            spotifyListening.sample(playback.track, playback.playing && state.spotifyConnected,
                SystemClock.elapsedRealtime())?.let { listeningHistory.record(it) }
            synchronizeProgressPolling()
        }
    }, status = { connected, error ->
        state = state.copy(spotifyConnected = connected, spotifyError = error, message = error ?: state.message)
        if (!connected) spotifyListening.sample(state.current.takeIf { state.source == "spotify" }, false,
            SystemClock.elapsedRealtime())?.let { listeningHistory.record(it) }
        synchronizeProgressPolling()
        if (connected) { pendingSpotifyPrompt = false; state.current?.takeIf { it.source == "spotify" }?.let(::loadSpotifyLike) }
    })
    private val future = MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) { updatePlayer() }
        }).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePlayer(refreshQueue = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED))
        }
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
        viewModelScope.launch { homeRecommendations.state.collect { result ->
            state = state.copy(recommendations = result.recommendations, frequent = result.frequent,
                recommendationArtists = result.artists, recommendationsLoading = result.loading,
                recommendationsError = result.error, recommendationsHaveMore = result.hasMore)
        } }
        viewModelScope.launch { playbackSettings.normalization.collect { state = state.copy(normalizeVolume = it) } }
        viewModelScope.launch { recommendationSettings.state.collect {
            state = state.copy(recommendationPreferences = it)
            refreshRecommendations()
        } }
        viewModelScope.launch { listeningHistory.state.collect { refreshRecommendations() } }
        viewModelScope.launch {
            appUpdater.state.catch { updateActionError = "Status pembaruan belum bisa dibaca. Buka ulang aplikasi." }
                .collect { updateDownload = it }
        }
        viewModelScope.launch { offlineLibrary.state.collect { state = state.copy(offline = it) } }
        future.addListener({
            runCatching { future.get() }.onSuccess { player ->
                controller = player
                player.addListener(listener)
                if (player.currentMediaItem?.toTrack()?.source?.let { it != state.source } == true) { player.pause(); player.clearMediaItems() }
                updatePlayer(refreshQueue = true)
            }.onFailure { state = state.copy(message = "Pemutar belum siap. Tutup lalu buka kembali aplikasi.") }
        }, ContextCompat.getMainExecutor(application))
        if (state.source != "audius") state = state.copy(home = emptyList())
        loadHome()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshRecommendations(force = true)
        loadHome(force = true)
        refreshJob = viewModelScope.launch {
            state = state.copy(homeRefreshing = true)
            try { homeJob?.join(); homeRecommendations.awaitIdle() }
            finally { state = state.copy(homeRefreshing = false) }
        }
    }

    private fun loadHome(force: Boolean = false) {
        if (!force && homeJob?.isActive == true) return
        homeJob?.cancel()
        val source = state.source
        val videos = state.youtubeVideos
        homeJob = viewModelScope.launch {
            state = state.copy(loading = true, homeError = null)
            try {
                val year = java.time.Year.now().value
                val tracks = when (source) {
                    "spotify" -> if (state.spotifyAuthorized) spotifyApi.search("year:${year - 1}-$year") else emptyList()
                    else -> catalogCache.load(CatalogQuery(source, videos, "home:musik Indonesia"), force,
                        cached = { state = state.copy(home = it) }) {
                        if (source == "youtube") YouTubeCatalog.search("musik Indonesia", videos, refresh = force) else repository.trending()
                    }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                state = state.copy(home = tracks, loading = false)
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state = state.copy(loading = false, homeError = catalogMessage(error)) }
        }
    }

    fun retryRecommendations() = refreshRecommendations(force = true)

    fun loadMoreRecommendations() = homeRecommendations.loadMore()

    private fun refreshRecommendations(force: Boolean = false) {
        homeRecommendations.refresh(HomeRecommendationInput(
            source = state.source, videos = state.youtubeVideos, spotifyAuthorized = state.spotifyAuthorized,
            stats = listeningHistory.state.value, history = state.history, preferences = state.recommendationPreferences), force)
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
        val queueId = java.util.UUID.randomUUID().toString()
        player.setMediaItems(local.map { it.toMediaItem(queueId) }, local.indexOf(selected).coerceAtLeast(0), 0)
        player.shuffleModeEnabled = shuffled
        player.prepare()
        player.play()
        updatePlayer()
    }

    fun togglePlayback() { if (state.source == "spotify") spotifyRemote.toggle() else controller?.togglePlayback() }
    fun setVolumeNormalization(enabled: Boolean) = playbackSettings.setNormalization(enabled)
    fun startRadio(track: Track) {
        if (!track.playableInNada) return
        if (state.source != track.source) selectSource(track.source)
        val player = controller ?: run { state = state.copy(message = "Pemutar sedang disiapkan. Coba sebentar lagi."); return }
        val result = player.sendCustomCommand(RadioCommands.start, Bundle().apply { putString(QueueCommands.TRACK, track.toJson().toString()) })
        result.addListener({
            if (runCatching { result.get().resultCode != SessionResult.RESULT_SUCCESS }.getOrDefault(true))
                state = state.copy(message = "Radio belum dapat dimulai. Coba lagi.")
        }, ContextCompat.getMainExecutor(getApplication()))
    }
    fun retryRadio() { controller?.sendCustomCommand(RadioCommands.retry, Bundle.EMPTY) }
    fun setAutoplay(enabled: Boolean) = recommendationSettings.setAutoplay(enabled)
    fun toggleDislike(track: Track) {
        val undo = recommendationSettings.state.value.dislikes(track)
        recommendationSettings.toggleDislike(track)
        state = state.copy(message = if (undo) "Lagu boleh direkomendasikan lagi" else "Lagu ini tidak akan direkomendasikan lagi")
    }
    fun toggleBlockedArtist(artist: String) {
        val undo = recommendationSettings.state.value.blocks(artist)
        recommendationSettings.toggleArtist(artist)
        state = state.copy(message = if (undo) "Artis boleh direkomendasikan lagi" else "Artis ini tidak akan direkomendasikan lagi")
    }
    fun loadLyrics(track: Track, refresh: Boolean = false, automatic: Boolean = false) {
        if (!refresh && lyricsState.trackId == track.id && (lyricsState.loading || lyricsState.document != null)) return
        val previous = lyricsState.takeIf { it.trackId == track.id && it.document != null }
        lyricsJob?.cancel()
        lyricsState = previous?.copy(loading = true, error = null) ?: LyricsState(trackId = track.id, loading = true)
        lyricsJob = viewModelScope.launch {
            try {
                val result = lyricsRepository.load(track, refresh, automatic)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                lyricsState = LyricsState(trackId = track.id, document = result.first, cached = result.second)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                lyricsState = previous?.copy(loading = false, error = "Lirik belum bisa diperbarui. Menampilkan lirik sebelumnya.")
                    ?: LyricsState(trackId = track.id, error = "Lirik belum bisa dimuat. Periksa internet, lalu coba lagi.")
            }
        }
    }
    fun closeLyrics() {
        lyricsJob?.cancel()
        if (lyricsState.loading) lyricsState = if (lyricsState.document != null) lyricsState.copy(loading = false) else LyricsState()
        lyricsSearchJob?.cancel()
        lyricsSearch = LyricsSearchState()
    }
    fun openLyricsSearch(track: Track, query: String) {
        if (lyricsSearch.trackId != track.id) searchLyrics(track, query)
    }
    fun searchLyrics(track: Track, query: String) {
        val text = query.trim().take(200)
        lyricsSearchJob?.cancel()
        lyricsSearch = LyricsSearchState(trackId = track.id, query = text, loading = text.isNotEmpty())
        if (text.isEmpty()) return
        lyricsSearchJob = viewModelScope.launch {
            try {
                val results = lyricsRepository.search(text, track)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                lyricsSearch = LyricsSearchState(trackId = track.id, query = text, results = results, searched = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                lyricsSearch = LyricsSearchState(trackId = track.id, query = text, searched = true,
                    error = "Pencarian lirik gagal. Periksa internet, lalu coba lagi.")
            }
        }
    }
    fun useLyrics(track: Track, candidate: LyricsCandidate) {
        val previous = lyricsState.takeIf { it.trackId == track.id && it.document != null }
        lyricsJob?.cancel()
        lyricsState = previous?.copy(loading = true, error = null) ?: LyricsState(trackId = track.id, loading = true)
        lyricsJob = viewModelScope.launch {
            try {
                val document = lyricsRepository.select(track, candidate)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                lyricsState = LyricsState(trackId = track.id, document = document)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                lyricsState = previous?.copy(loading = false, error = "Lirik pilihan belum bisa dipakai. Coba hasil lain.")
                    ?: LyricsState(trackId = track.id, error = "Lirik pilihan belum bisa dipakai. Coba hasil lain.")
            }
        }
    }
    fun lyricsPosition(): Long = if (state.source == "spotify") progress.position else controller?.currentPosition?.coerceAtLeast(0) ?: progress.position
    fun download(track: Track) {
        try {
            offlineLibrary.add(track)
            state = state.copy(message = "Lagu ditambahkan ke Unduhan")
        } catch (error: Exception) { state = state.copy(message = "Unduhan belum bisa dimulai. Coba lagi saat aplikasi terbuka.") }
    }
    fun downloadPlaylist(tracks: List<Track>) {
        if (state.batchDownloading || tracks.isEmpty()) return
        state = state.copy(batchDownloading = true)
        viewModelScope.launch {
            try {
                val result = offlineLibrary.addAll(tracks)
                val parts = buildList {
                    if (result.added > 0) add("${result.added} lagu ditambahkan ke Unduhan")
                    if (result.ready > 0) add("${result.ready} sudah offline")
                    if (result.queued > 0) add("${result.queued} sedang diproses")
                    if (result.unsupported > 0) add("${result.unsupported} tidak mendukung unduhan")
                    if (result.failed > 0) add("${result.failed} belum bisa ditambahkan; coba lagi")
                }
                state = state.copy(message = parts.joinToString(" · "))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { state = state.copy(message = "Unduhan playlist belum bisa dimulai. Buka Unduhan, lalu coba lagi.") }
            finally { state = state.copy(batchDownloading = false) }
        }
    }
    fun setUnmeteredDownloads(enabled: Boolean) = changeDownload { offlineLibrary.setUnmeteredOnly(enabled) }

    fun exportCollection(uri: Uri) {
        if (transferState.busy != null) return
        val snapshot = repository.snapshot()
        transferState = CollectionTransferState(busy = "Menyimpan backup…")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = LibraryBackup.encode(snapshot)
                    val resolver = getApplication<Application>().contentResolver
                    requireNotNull(resolver.openOutputStream(uri, "wt")) { "File belum bisa dibuka untuk menyimpan backup." }
                        .use { it.write(bytes); it.flush() }
                }
                transferState = CollectionTransferState(message = "Backup berhasil disimpan. Simpan file ini untuk memindahkan koleksimu.")
            } catch (error: CancellationException) { transferState = CollectionTransferState(); throw error }
            catch (error: Exception) { transferState = CollectionTransferState(error = "Backup belum tersimpan. Periksa ruang kosong dan pilih lokasi penyimpanan lagi.") }
        }
    }

    fun readCollectionBackup(uri: Uri) {
        if (transferState.busy != null) return
        transferState = CollectionTransferState(busy = "Membaca backup…")
        viewModelScope.launch {
            try {
                val backup = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    requireNotNull(resolver.openInputStream(uri)) { "File backup belum bisa dibuka." }.use(LibraryBackup::read)
                }
                transferState = CollectionTransferState(pending = backup)
            } catch (error: CancellationException) { transferState = CollectionTransferState(); throw error }
            catch (error: Exception) {
                transferState = CollectionTransferState(error = if (error is IllegalArgumentException) error.message?.takeIf { it.isNotBlank() }
                    ?: "File backup tidak valid. Pilih file backup NadaKuy yang lain."
                    else "File backup belum bisa dibaca. Unduh file ke perangkat atau pilih file lain.")
            }
        }
    }

    fun cancelCollectionRestore() { transferState = CollectionTransferState() }
    fun restoreCollection() {
        val backup = transferState.pending ?: return
        transferState = CollectionTransferState(busy = "Memulihkan koleksi…")
        try {
            repository.restore(backup)
            syncLibrary()
            state = state.copy(recentSearches = repository.searchHistory)
            transferState = CollectionTransferState(message = "Koleksi berhasil dipulihkan. Data lama tetap tersimpan dan duplikat dilewati.")
        } catch (error: Exception) {
            transferState = CollectionTransferState(error = "Koleksi belum berhasil disimpan. Periksa ruang kosong dan coba lagi.")
        }
    }

    fun checkForUpdatesOnOpen() {
        if (updateState.checking || !updateNotices.policy.shouldCheck(SystemClock.elapsedRealtime())) return
        checkForUpdates(automatic = true)
    }

    fun dismissUpdateNotice() {
        updateNotice?.let { updateNotices.snooze(it.version, System.currentTimeMillis()) }
        updateNotice = null
    }

    fun openUpdateNotice() {
        dismissUpdateNotice()
        requestUpdatesScreen()
    }

    fun checkForUpdates() = checkForUpdates(automatic = false)

    private fun checkForUpdates(automatic: Boolean) {
        if (updateState.checking) return
        val previous = updateState
        updateState = previous.copy(checking = true, error = null)
        viewModelScope.launch {
            try {
                val release = AppUpdates.latest()
                updateState = AppUpdates.result(release, installedVersion)
                updateNotices.policy.checked(SystemClock.elapsedRealtime(), succeeded = true)
                if (automatic && updateNotices.policy.offer(release, installedVersion, System.currentTimeMillis())) {
                    updateNotice = release
                }
            }
            catch (error: CancellationException) { updateState = previous; throw error }
            catch (error: Exception) {
                updateNotices.policy.checked(SystemClock.elapsedRealtime(), succeeded = false)
                updateState = if (automatic) previous.copy(checking = false) else UpdateState(error = if (error is IllegalStateException) error.message
                    else "Tidak bisa memeriksa pembaruan. Periksa internet, lalu coba lagi.")
            }
        }
    }
    fun downloadAppUpdate(pkg: UpdatePackage) {
        if (updateDownload.busy || preparingUpdateInstall) return
        if (!state.offline.loaded) { updateActionError = "Pengaturan unduhan sedang dimuat. Coba lagi sebentar."; return }
        if (AppVersion.parse(pkg.version)!! <= AppVersion.parse(installedVersion)!!) return
        updateActionError = null
        viewModelScope.launch {
            try { appUpdater.download(pkg, state.offline.unmeteredOnly) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { updateActionError = "Unduhan belum bisa dimulai. Periksa ruang kosong, lalu coba lagi." }
        }
    }
    fun cancelAppUpdate() { appUpdater.cancel(); updateActionError = null }
    fun updateInstallError(message: String?) { updateActionError = message }
    fun prepareAppUpdateInstall(ready: (Intent) -> Unit) {
        if (preparingUpdateInstall) return
        preparingUpdateInstall = true; updateActionError = null
        viewModelScope.launch {
            try { ready(appUpdater.installIntent()) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { updateActionError = error.message ?: "Paket pembaruan belum bisa dibuka. Coba lagi." }
            finally { preparingUpdateInstall = false }
        }
    }
    fun pauseDownload(id: String) = changeDownload { offlineLibrary.pause(id) }
    fun resumeDownload(id: String) = changeDownload { offlineLibrary.resume(id) }
    fun removeDownload(id: String) = changeDownload { offlineLibrary.remove(id) }
    fun refreshDownloads() = offlineLibrary.refresh()
    private fun changeDownload(action: () -> Unit) {
        try { action() } catch (error: Exception) { state = state.copy(message = "Unduhan belum bisa diubah. Coba lagi.") }
    }
    fun playQueueItem(mediaIndex: Int) { controller?.playQueueItem(mediaIndex); updatePlayer() }
    fun moveQueueItem(entryId: String, targetId: String) {
        val player = controller ?: return
        val result = player.sendCustomCommand(QueueCommands.move, Bundle().apply {
            putString(QueueCommands.ENTRY_ID, entryId); putString(QueueCommands.TARGET_ID, targetId)
        })
        result.addListener({
            if (runCatching { result.get().resultCode != SessionResult.RESULT_SUCCESS }.getOrDefault(true))
                state = state.copy(message = "Antrean berubah. Coba atur urutannya lagi.")
            updatePlayer()
        }, ContextCompat.getMainExecutor(getApplication()))
    }
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
    fun next() { if (state.source == "spotify") spotifyRemote.next() else controller?.let {
        if (it.hasNextMediaItem()) it.seekToNextMediaItem()
        else if (state.recommendationPreferences.autoplay) {
            if (state.radioLoading) state = state.copy(message = "Menyiapkan lagu berikutnya…") else retryRadio()
        } else it.seekTo(0)
    } }
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
    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): String {
        val result = repository.createPlaylist(name, tracks)
        syncLibrary()
        return result.id
    }

    internal fun resetPlaylistImport() {
        playlistImportJob?.cancel()
        playlistImportJob = null
        playlistImport = PlaylistImportState()
    }

    internal fun previewPlaylist(link: String) {
        if (playlistImport.loading) return
        val url = YouTubeLinks.playlistUrl(link)
        if (url == null) {
            playlistImport = PlaylistImportState(error = "Masukkan tautan playlist publik dari YouTube atau YouTube Music.")
            return
        }
        playlistImport = PlaylistImportState(loading = true)
        playlistImportJob = viewModelScope.launch {
            try {
                val result = withTimeoutOrNull(60_000) { YouTubeCatalog.playlistPreview(url) }
                ensureActive()
                playlistImport = when {
                    result == null -> PlaylistImportState(error = "Playlist terlalu lama dimuat. Periksa koneksi, lalu coba lagi.")
                    result.tracks.isEmpty() -> PlaylistImportState(error = "Tidak ada lagu yang dapat dibaca. Coba playlist publik lain.")
                    else -> PlaylistImportState(preview = result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                playlistImport = PlaylistImportState(error = "Playlist belum bisa dibaca. Periksa koneksi dan pastikan playlist dapat dibuka tanpa login.")
            }
        }
    }

    internal fun saveImportedPlaylist(): String? {
        val preview = playlistImport.preview ?: return null
        val id = createPlaylist(preview.name, preview.tracks)
        resetPlaylistImport()
        state = state.copy(message = "Playlist disimpan ke Koleksi")
        return id
    }

    fun saveQueuePlaylist(name: String, tracks: List<Track>) {
        if (name.isBlank() || tracks.isEmpty()) return
        createPlaylist(name, tracks)
        state = state.copy(message = "Antrean disimpan ke playlist ${name.trim().take(60)}")
    }
    fun addToPlaylist(id: String, track: Track) { repository.addToPlaylist(id, track); syncLibrary(); state = state.copy(message = "Lagu ditambahkan ke playlist") }
    fun removeFromPlaylist(id: String, track: Track) { repository.removeFromPlaylist(id, track.id); syncLibrary() }
    fun deletePlaylist(id: String) { repository.deletePlaylist(id); syncLibrary() }
    fun renamePlaylist(id: String, name: String) { repository.renamePlaylist(id, name); syncLibrary() }
    fun dismissMessage() { state = state.copy(message = null) }

    private fun syncLibrary() {
        state = state.copy(liked = repository.liked + spotifyLikedTracks, history = repository.history, playlists = repository.playlists)
        refreshRecommendations()
    }
    private fun loadSpotifyLike(track: Track) { spotifyRemote.libraryState(track) { added -> rememberSpotifyLike(track, added) } }
    private fun rememberSpotifyLike(track: Track, added: Boolean) {
        spotifyLikedTracks = spotifyLikedTracks.filterNot { it.id == track.id }.let { if (added) listOf(track) + it else it }
        syncLibrary()
    }
    private fun updatePlayer(refreshQueue: Boolean = false) {
        if (state.source == "spotify") { spotifyRemote.tick(); return }
        controller?.let { player ->
            val queueChanged = refreshQueue || queueCurrentIndex != player.currentMediaItemIndex
            val queue = if (queueChanged) player.upcomingQueue().also { queueCurrentIndex = player.currentMediaItemIndex } else state.queue
            state = state.copy(current = player.currentMediaItem?.toTrack(), playing = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                shuffle = player.shuffleModeEnabled, repeat = player.repeatMode,
                canSeek = true, canNext = true, canPrevious = true, canShuffle = true, canRepeat = true,
                sleepDeadline = player.sessionExtras.getLong(SleepCommands.DEADLINE), sleepEndOfTrack = player.sessionExtras.getBoolean(SleepCommands.END_OF_TRACK),
                radioTitle = player.sessionExtras.getString(RadioCommands.TITLE), radioLoading = player.sessionExtras.getBoolean(RadioCommands.LOADING),
                radioError = player.sessionExtras.getString(RadioCommands.ERROR),
                queue = queue, queueHasCurrent = true, queueSaveError = player.sessionExtras.getString(QueueCommands.SAVE_ERROR))
            updateProgress()
            synchronizeProgressPolling()
        }
    }

    private fun updateProgress() {
        if (state.source == "spotify") { spotifyRemote.tick(); return }
        controller?.let { player ->
            progress = PlaybackProgress(position = player.currentPosition.coerceAtLeast(0),
                duration = player.duration.takeIf { it > 0 } ?: ((state.current?.duration ?: 0) * 1000L),
                sleepRemainingSeconds = ((state.sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000)
        }
    }

    private fun synchronizeProgressPolling() {
        val active = foregroundActivity?.get() != null &&
            ((state.playing && (state.source != "spotify" || state.spotifyConnected)) || state.sleepDeadline > SystemClock.elapsedRealtime())
        if (!active) { progressJob?.cancel(); progressJob = null; return }
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(750)
                updateProgress()
            }
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
            sleepDeadline = 0, sleepEndOfTrack = false, queueError = null)
        progress = PlaybackProgress()
        queueCurrentIndex = C.INDEX_UNSET
        spotifyListening.sample(null, false, SystemClock.elapsedRealtime())?.let { listeningHistory.record(it) }
        synchronizeProgressPolling()
        refreshRecommendations(); loadHome()
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
            refreshRecommendations(); loadHome(); if (state.query.isNotBlank()) search(state.query)
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
                spotifyListening.sample(null, false, SystemClock.elapsedRealtime())?.let { listeningHistory.record(it) }
                synchronizeProgressPolling()
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
    fun onForeground(activity: Activity) {
        foregroundActivity = java.lang.ref.WeakReference(activity)
        if (state.source == "spotify" && state.spotifyAuthorized && !state.spotifyBusy) spotifyRemote.connect(activity, spotifyAuth.clientId, prompt = pendingSpotifyPrompt)
        updatePlayer()
        synchronizeProgressPolling()
    }
    fun onBackground() {
        foregroundActivity = null
        synchronizeProgressPolling()
        spotifyListening.sample(state.current.takeIf { state.source == "spotify" }, false,
            SystemClock.elapsedRealtime())?.let { listeningHistory.record(it) }
        spotifyRemote.disconnect()
        state = state.copy(spotifyConnected = false)
    }
    fun disconnectSpotify() {
        authJob?.cancel(); spotifyAuth.disconnect(); spotifyRemote.disconnect()
        spotifyLikedTracks = emptyList()
        homeJob?.cancel(); searchJob?.cancel(); queueJob?.cancel()
        state = state.copy(spotifyAuthorized = false, spotifyConnected = false, spotifyBusy = false, spotifyError = null,
            home = emptyList(), results = emptyList(), current = null, playing = false, queue = emptyList(), loading = false)
        progress = PlaybackProgress()
        synchronizeProgressPolling()
        spotifyListening.sample(null, false, SystemClock.elapsedRealtime())?.let { listeningHistory.record(it) }
        syncLibrary()
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
    override fun onCleared() { lyricsJob?.cancel(); lyricsRepository.close(); spotifyAuth.cancel(); spotifyRemote.disconnect(); controller?.removeListener(listener); MediaController.releaseFuture(future); super.onCleared() }
}
