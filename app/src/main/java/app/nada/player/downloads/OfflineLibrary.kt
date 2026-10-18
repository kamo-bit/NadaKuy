package app.nada.player.downloads

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import app.nada.player.data.Track
import app.nada.player.playback.audioUpstream
import app.nada.player.playback.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

enum class OfflineStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED, REMOVING }

data class OfflineTrack(
    val id: String, val track: Track?, val status: OfflineStatus,
    val bytes: Long, val totalBytes: Long, val percent: Float?,
    val waitingForNetwork: Boolean = false, val missingMedia: Boolean = false, val waitingForUnmetered: Boolean = false,
) {
    val ready: Boolean get() = status == OfflineStatus.COMPLETE && track != null
}

data class OfflineState(val tracks: List<OfflineTrack> = emptyList(), val loaded: Boolean = false, val error: String? = null,
    val unmeteredOnly: Boolean = false)

data class PlaylistDownloadResult(val added: Int, val ready: Int, val queued: Int, val unsupported: Int, val failed: Int)

/** Application-scoped owner of the persistent index and non-evicting audio cache. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class OfflineLibrary private constructor(private val context: Context) {
    private val database = StandaloneDatabaseProvider(context)
    internal val cache = SimpleCache(File(context.noBackupFilesDir, "offline-audio"), NoOpCacheEvictor(), database)
    private val upstream = audioUpstream(context)
    private val preferences = context.getSharedPreferences("nada_downloads", Context.MODE_PRIVATE)
    internal val manager = DownloadManager(context, database, cache, upstream, Executor { it.run() }).apply {
        maxParallelDownloads = 2
        requirements = downloadRequirements(preferences.getBoolean("unmetered_only", false))
    }
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(OfflineState())
    val state = mutableState.asStateFlow()

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                refresh()
                if (downloadManager.currentDownloads.any { it.stopReason == Download.STOP_REASON_NONE && it.state != Download.STATE_FAILED }) {
                    // Opening the app resumes unfinished work; an explicit per-track pause is retained.
                    DownloadService.start(context, OfflineDownloadService::class.java)
                }
            }
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
            override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) = refresh()
            override fun onDownloadsPausedChanged(downloadManager: DownloadManager, downloadsPaused: Boolean) = refresh()
        })
        scope.launch {
            for (ignored in changes) {
                do {
                    try {
                        val waiting = manager.notMetRequirements != 0
                        val records = withContext(Dispatchers.IO) { readIndex(waiting) }
                        mutableState.value = OfflineState(records, loaded = true,
                            unmeteredOnly = manager.requirements.isUnmeteredNetworkRequired)
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        mutableState.value = mutableState.value.copy(loaded = true, error = "Unduhan belum bisa dibaca. Coba muat ulang.")
                    }
                    if (manager.currentDownloads.none { it.state == Download.STATE_DOWNLOADING }) break
                    delay(750)
                    changes.tryReceive()
                } while (true)
            }
        }
        if (manager.isInitialized) refresh()
    }

    fun refresh() { changes.trySend(Unit) }

    internal fun playbackFactory(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setUpstreamDataSourceFactory(upstream)
        // Only explicit downloads write media; streaming does not grow offline storage.
        .setCacheWriteDataSinkFactory(null)

    fun add(track: Track) {
        require(track.playableInNada) { "Sumber ini tidak menyediakan unduhan di NadaKuy." }
        val existing = state.value.tracks.firstOrNull { it.id == track.id }
        if (existing?.status in listOf(OfflineStatus.COMPLETE, OfflineStatus.DOWNLOADING, OfflineStatus.QUEUED, OfflineStatus.REMOVING)) return
        submit(track)
    }

    private fun submit(track: Track) {
        val media = track.toMediaItem().localConfiguration!!
        val request = DownloadRequest.Builder(track.id, media.uri)
            .setCustomCacheKey(track.id)
            .setMimeType(if (track.source == "youtube") MimeTypes.AUDIO_MP4 else MimeTypes.AUDIO_MPEG)
            .setData(track.toJson().toString().toByteArray(Charsets.UTF_8)).build()
        DownloadService.sendAddDownload(context, OfflineDownloadService::class.java, request, true)
    }

    suspend fun addAll(tracks: List<Track>): PlaylistDownloadResult {
        withTimeout(15_000) { state.first { it.loaded && it.error == null } }
        val saved = withContext(Dispatchers.IO) { readIndex(manager.notMetRequirements != 0) }
        val plan = planPlaylistDownloads(tracks, saved)
        var added = 0
        var failed = 0
        plan.pending.forEachIndexed { index, track ->
            try { submit(track); added++ } catch (error: Exception) { failed++ }
            if (index % 16 == 15) yield()
        }
        return PlaylistDownloadResult(added, plan.ready, plan.queued, plan.unsupported, failed)
    }

    fun setUnmeteredOnly(enabled: Boolean) {
        manager.requirements = downloadRequirements(enabled)
        preferences.edit { putBoolean("unmetered_only", enabled) }
        refresh()
    }

    private fun downloadRequirements(unmetered: Boolean) = Requirements(
        Requirements.NETWORK or if (unmetered) Requirements.NETWORK_UNMETERED else 0)

    fun pause(id: String) = DownloadService.sendSetStopReason(context, OfflineDownloadService::class.java, id, USER_PAUSED, true)
    fun resume(id: String) = DownloadService.sendSetStopReason(context, OfflineDownloadService::class.java, id, Download.STOP_REASON_NONE, true)
    fun remove(id: String) = DownloadService.sendRemoveDownload(context, OfflineDownloadService::class.java, id, true)

    private fun readIndex(waiting: Boolean): List<OfflineTrack> {
        val records = mutableListOf<Pair<Long, OfflineTrack>>()
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                val request = download.request
                val track = runCatching { Track.fromSaved(JSONObject(String(request.data, Charsets.UTF_8))) }
                    .getOrNull()?.takeIf { it.id == request.id && it.playableInNada }
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(request.customCacheKey ?: request.id))
                    .takeIf { it > 0 } ?: download.contentLength.takeIf { it > 0 } ?: download.bytesDownloaded
                val missing = download.state == Download.STATE_COMPLETED && (length <= 0 || !cache.isCached(request.customCacheKey ?: request.id, 0, length))
                val status = when {
                    missing -> OfflineStatus.FAILED
                    download.state == Download.STATE_COMPLETED -> OfflineStatus.COMPLETE
                    download.state == Download.STATE_DOWNLOADING -> OfflineStatus.DOWNLOADING
                    download.state == Download.STATE_FAILED -> OfflineStatus.FAILED
                    download.state == Download.STATE_REMOVING || download.state == Download.STATE_RESTARTING -> OfflineStatus.REMOVING
                    download.stopReason != Download.STOP_REASON_NONE -> OfflineStatus.PAUSED
                    else -> OfflineStatus.QUEUED
                }
                records += download.startTimeMs to OfflineTrack(request.id, track, status, download.bytesDownloaded, length,
                    download.percentDownloaded.takeIf { it >= 0 }?.coerceIn(0f, 100f), waiting, missing,
                    manager.notMetRequirements and Requirements.NETWORK_UNMETERED != 0)
            }
        }
        return records.sortedByDescending { it.first }.map { it.second }
    }

    companion object {
        private const val USER_PAUSED = 1
        @Volatile private var instance: OfflineLibrary? = null
        fun get(context: Context): OfflineLibrary = instance ?: synchronized(this) {
            instance ?: OfflineLibrary(context.applicationContext).also { instance = it }
        }
    }
}
