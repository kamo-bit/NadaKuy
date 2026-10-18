package app.nada.player.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class LibraryBackupTest {
    private val song = Track("abc42", "Judul lagu", "Penyanyi", "https://example.com/cover.jpg", 120, "Pop", "/artist/song")
    private val collection = LibrarySnapshot(listOf(song), listOf(song), listOf(LocalPlaylist("p1", "Perjalanan", listOf(song))), listOf("Dewa 19"))
    private val date = "2026-10-19T00:00:00Z"

    @Test fun portableBackupRoundTripsAllCollectionFieldsWithoutRuntimeState() {
        val bytes = LibraryBackup.encode(collection, date)
        assertEquals(CollectionBackup(date, collection), LibraryBackup.read(ByteArrayInputStream(bytes)))
        val json = bytes.toString(Charsets.UTF_8)
        assertFalse(json.contains("streamUrl"))
        assertFalse(json.contains("spotify-session"))
    }

    @Test fun restoringTwiceIsIdempotentAndPreservesLocalEdits() {
        val second = song.copy(id = "def43", title = "Lagu kedua")
        val local = collection.copy(playlists = listOf(LocalPlaylist("p1", "Nama lokal", listOf(second))))
        val result = LibraryBackup.merge(local, collection)
        assertEquals("Nama lokal", result.playlists.single().name)
        assertEquals(listOf(second, song), result.playlists.single().tracks)
        assertEquals(result, LibraryBackup.merge(result, collection))
    }

    @Test fun differentPlaylistIdsWithSameNameRemainSeparateAndHistoryIsBounded() {
        val incoming = collection.copy(playlists = listOf(LocalPlaylist("p2", "Perjalanan", listOf(song))),
            history = (0..45).map { song.copy(id = "song$it") })
        val result = LibraryBackup.merge(collection, incoming)
        assertEquals(2, result.playlists.size)
        assertEquals(40, result.history.size)
        assertEquals(song, result.history.first())
    }

    @Test fun rejectsForeignFutureMalformedAndUnsafeBackups() {
        val json = LibraryBackup.encode(collection, date).toString(Charsets.UTF_8)
        listOf("{}", "not json", json.replace("nadakuy.collection", "another.app"),
            json.replace("\"version\":1", "\"version\":2"),
            json.replace("https://example.com/cover.jpg", "file:///private/image"),
            json.replace("\"duration\":120", "\"duration\":-1"),
            json.replace("\"source\":\"audius\"", "\"source\":\"unknown\"")
        ).forEach { value -> assertThrows(Exception::class.java) { LibraryBackup.decode(value.toByteArray()) } }
    }

    @Test fun boundsInputEvenWhenTheProviderDoesNotReportAFileSize() {
        val stream = ByteArrayInputStream(ByteArray(LibraryBackup.MAX_BYTES + 100))
        assertThrows(IllegalArgumentException::class.java) { LibraryBackup.read(stream) }
        assertEquals(99, stream.available())
    }

    @Test fun preservesSpotifyMetadataWithoutImportingAnAccount() {
        val spotify = song.copy(id = "spotify:track:abc123", source = "spotify", permalink = "https://open.spotify.com/track/abc123")
        val snapshot = collection.copy(history = listOf(spotify))
        assertEquals(snapshot, LibraryBackup.decode(LibraryBackup.encode(snapshot, date)).collection)
    }
}
