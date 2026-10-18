package app.nada.player.downloads

import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test

class PlaylistDownloadsTest {
    private fun song(id: Int) = Track("song$id", "Judul $id", "Artis", "", 120, "Pop")
    private fun saved(id: Int, status: OfflineStatus, missing: Boolean = false) =
        OfflineTrack(song(id).id, song(id), status, 100, 100, 100f, missingMedia = missing)

    @Test fun skipsCompletedActiveRemovingAndUnsupportedSongsButRetriesPausedAndFailed() {
        val input = (1..6).map(::song) + song(6) + song(7).copy(source = "spotify")
        val saved = listOf(saved(1, OfflineStatus.COMPLETE), saved(2, OfflineStatus.DOWNLOADING),
            saved(3, OfflineStatus.QUEUED), saved(4, OfflineStatus.REMOVING),
            saved(5, OfflineStatus.PAUSED), saved(6, OfflineStatus.FAILED))
        val plan = planPlaylistDownloads(input, saved)
        assertEquals(listOf(song(5), song(6)), plan.pending)
        assertEquals(1, plan.ready)
        assertEquals(3, plan.queued)
        assertEquals(1, plan.unsupported)
    }

    @Test fun emptyAndAlreadyOfflinePlaylistsDoNotScheduleMoreWork() {
        assertTrue(planPlaylistDownloads(emptyList(), emptyList()).pending.isEmpty())
        val plan = planPlaylistDownloads(listOf(song(1), song(1)), listOf(saved(1, OfflineStatus.COMPLETE)))
        assertTrue(plan.pending.isEmpty())
        assertEquals(1, plan.ready)
    }
}
