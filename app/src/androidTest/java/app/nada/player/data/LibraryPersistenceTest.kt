package app.nada.player.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryPersistenceTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val preferenceName = "library_test_${UUID.randomUUID()}"
    private lateinit var context: Context
    private val song = Track("test42", "A song", "An artist", "", 120, "Jazz")

    @Before fun setUp() {
        context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(preferenceName, mode)
        }
    }

    @After fun cleanUp() { base.deleteSharedPreferences(preferenceName) }

    @Test fun likedSongsSurviveRepositoryRecreationAndCanBeRemoved() {
        MusicRepository(context).toggleLike(song)
        val reopened = MusicRepository(context)
        assertEquals(listOf(song), reopened.liked)
        reopened.toggleLike(song)
        assertTrue(MusicRepository(context).liked.isEmpty())
    }

    @Test fun playlistDeduplicatesSongsAndPersistsRemoval() {
        val repository = MusicRepository(context)
        val playlist = repository.createPlaylist("  Perjalanan  ")
        repository.addToPlaylist(playlist.id, song)
        repository.addToPlaylist(playlist.id, song)
        val reopened = MusicRepository(context)
        assertEquals("Perjalanan", reopened.playlists.single().name)
        assertEquals(listOf(song), reopened.playlists.single().tracks)
        reopened.removeFromPlaylist(playlist.id, song.id)
        assertTrue(MusicRepository(context).playlists.single().tracks.isEmpty())
        reopened.deletePlaylist(playlist.id)
        assertTrue(MusicRepository(context).playlists.isEmpty())
    }

    @Test fun historyIsBoundedAndMovesReplayedTracksToTheFront() {
        val repository = MusicRepository(context)
        repeat(45) { repository.rememberPlayed(song.copy(id = "song$it")) }
        repository.rememberPlayed(song.copy(id = "song10"))
        val history = MusicRepository(context).history
        assertEquals(40, history.size)
        assertEquals("song10", history.first().id)
        assertEquals(40, history.map(Track::id).distinct().size)
    }

    @Test fun renamingPlaylistPreservesItsSongsAndIdentity() {
        val repository = MusicRepository(context)
        val playlist = repository.createPlaylist("Before")
        repository.addToPlaylist(playlist.id, song)
        repository.renamePlaylist(playlist.id, "  After  ")
        val reopened = MusicRepository(context).playlists.single()
        assertEquals(playlist.id, reopened.id)
        assertEquals("After", reopened.name)
        assertEquals(listOf(song), reopened.tracks)
    }

    @Test fun recentSearchesDeduplicateCaseAndPersistClearing() {
        val repository = MusicRepository(context)
        repeat(12) { repository.rememberSearch("Query $it") }
        repository.rememberSearch(" query 8 ")
        val reopened = MusicRepository(context)
        assertEquals(10, reopened.searchHistory.size)
        assertEquals("query 8", reopened.searchHistory.first())
        assertEquals(1, reopened.searchHistory.count { it.equals("query 8", true) })
        reopened.clearSearchHistory()
        assertTrue(MusicRepository(context).searchHistory.isEmpty())
    }

    @Test fun backupRestoresIntoAnEmptyLibraryAndRepeatedMergePreservesLocalChanges() {
        val repository = MusicRepository(context)
        repository.toggleLike(song)
        repository.rememberPlayed(song)
        repository.rememberSearch("  Musik Indonesia  ")
        val playlist = repository.createPlaylist("Perjalanan")
        repository.addToPlaylist(playlist.id, song)
        val bytes = LibraryBackup.encode(repository.snapshot())
        val backupFile = java.io.File.createTempFile("nadakuy-backup-", ".json", base.cacheDir)
        try {
            backupFile.writeBytes(bytes)
            base.getSharedPreferences(preferenceName, 0).edit().clear().commit()
            val empty = MusicRepository(context)
            assertTrue(empty.playlists.isEmpty())
            val backup = backupFile.inputStream().use(LibraryBackup::read)
            empty.restore(backup)
            assertEquals(repository.snapshot(), MusicRepository(context).snapshot())
            empty.renamePlaylist(playlist.id, "Nama baru")
            val second = song.copy(id = "another42")
            empty.addToPlaylist(playlist.id, second)
            empty.restore(backup)
            empty.restore(backup)
            val restored = MusicRepository(context)
            assertEquals("Nama baru", restored.playlists.single().name)
            assertEquals(listOf(song, second), restored.playlists.single().tracks)
            assertEquals(listOf(song), restored.liked)
            assertEquals(listOf("Musik Indonesia"), restored.searchHistory)
        } finally { backupFile.delete() }
    }

    @Test fun invalidBackupDoesNotModifyAnyPreferences() {
        val repository = MusicRepository(context)
        repository.toggleLike(song)
        val before = base.getSharedPreferences(preferenceName, 0).all.toMap()
        assertThrows(Exception::class.java) { LibraryBackup.decode("{\"version\":999}".toByteArray()) }
        assertEquals(before, base.getSharedPreferences(preferenceName, 0).all)
    }
}
