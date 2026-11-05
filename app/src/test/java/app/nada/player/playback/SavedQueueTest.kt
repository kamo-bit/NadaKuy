package app.nada.player.playback

import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test

class SavedQueueTest {
    private val seed = Track("youtube:rfSFrPi1YuE", "Kangen", "Dewa 19", "", 330, "Pop", source = "youtube")
    private val other = seed.copy(id = "youtube:IQkJweWue9Q", title = "Dan", artist = "Sheila On 7")
    private val queue = SavedQueue(listOf(SavedQueueEntry("first", "radio", false, seed),
        SavedQueueEntry("second", "radio", true, other), SavedQueueEntry("duplicate", "radio", false, seed)),
        listOf(2, 0, 1), 1, 42_345, true, 2, RadioStation(seed, "radio", "Radio · Kangen"))

    @Test fun roundTripPreservesOccurrencesShuffleAndOriginalStation() {
        val encoded = SavedQueueCodec.encode(queue, "revision")
        assertEquals("revision" to queue, SavedQueueCodec.decode(encoded))
        assertFalse(encoded.contains("googlevideo"))
        assertFalse(encoded.contains("streamUrl"))
        assertFalse(encoded.contains("nada-youtube"))
    }
    @Test fun positionAndCurrentAreCheckpointsButModesAndOrderAreStructure() {
        assertEquals(queue.structure(), queue.copy(current = 2, positionMs = 999).structure())
        assertNotEquals(queue.structure(), queue.copy(order = listOf(1, 0, 2)).structure())
        assertNotEquals(queue.structure(), queue.copy(shuffle = false).structure())
        assertNotEquals(queue.structure(), queue.copy(repeat = 0).structure())
    }
    @Test fun rejectsInconsistentQueueIdentitiesAndPositions() {
        listOf(queue.copy(current = -1), queue.copy(current = 3), queue.copy(positionMs = -1),
            queue.copy(order = listOf(0, 0, 2)), queue.copy(order = listOf(0, 1)), queue.copy(repeat = 3),
            queue.copy(entries = queue.entries + queue.entries.first(), order = listOf(0, 1, 2, 3)),
            queue.copy(station = queue.station!!.copy(queueId = "another")),
            queue.copy(entries = queue.entries.map { it.copy(entryId = "") }))
            .forEach { assertThrows(IllegalArgumentException::class.java) { SavedQueueCodec.validate(it) } }
    }
    @Test fun rejectsMalformedFutureAndMistypedDocuments() {
        val json = SavedQueueCodec.encode(queue, "r")
        listOf("{}", "[]", "broken", json.replace("\"version\":1", "\"version\":2"),
            json.replace("\"version\":1", "\"version\":1.5"), json.replace("\"shuffle\":true", "\"shuffle\":\"true\""),
            json.replace("\"current\":1", "\"current\":1.5"), json.replace("\"duration\":330", "\"duration\":-1"),
            json.replace("youtube:rfSFrPi1YuE", "unsupported"), json.replace("\"source\":\"youtube\"", "\"source\":\"spotify\""))
            .forEach { assertThrows(Exception::class.java) { SavedQueueCodec.decode(it) } }
    }
    @Test fun limitsInputAndOutputBeforeKeepingUnboundedData() {
        assertThrows(IllegalArgumentException::class.java) { SavedQueueCodec.decode(" ".repeat(SavedQueueCodec.MAX_BYTES + 1)) }
        val entries = (0..SavedQueueCodec.MAX_ENTRIES).map { queue.entries.first().copy(entryId = "$it") }
        assertThrows(IllegalArgumentException::class.java) { SavedQueueCodec.encode(queue.copy(entries = entries, order = entries.indices.toList()), "r") }
        val heavy = (0 until 2000).map { queue.entries.first().copy(entryId = "$it", track = seed.copy(artwork = "a".repeat(4096), permalink = "b".repeat(4096))) }
        assertThrows(IllegalArgumentException::class.java) { SavedQueueCodec.encode(queue.copy(entries = heavy, order = heavy.indices.toList()), "r") }
    }
}
