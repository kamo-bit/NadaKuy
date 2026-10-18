package app.nada.player.downloads

import app.nada.player.data.Track

data class PlaylistDownloadPlan(
    val pending: List<Track>, val ready: Int, val queued: Int, val unsupported: Int,
)

fun planPlaylistDownloads(tracks: List<Track>, saved: List<OfflineTrack>): PlaylistDownloadPlan {
    val existing = saved.associateBy { it.id }
    var ready = 0
    var queued = 0
    var unsupported = 0
    val pending = tracks.distinctBy(Track::id).filter { track ->
        val entry = existing[track.id]
        when {
            !track.playableInNada -> { unsupported++; false }
            entry?.ready == true -> { ready++; false }
            entry?.status in listOf(OfflineStatus.DOWNLOADING, OfflineStatus.QUEUED, OfflineStatus.REMOVING) -> { queued++; false }
            else -> true
        }
    }
    return PlaylistDownloadPlan(pending, ready, queued, unsupported)
}
