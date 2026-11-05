package app.nada.player.recommendations

import app.nada.player.data.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RadioContinuationTest {
    private fun song(id: String, artist: String = "Artist${id.takeLast(1)}") = Track(id, id, artist, "", 180, "Pop")
    private val anchor = song("seed", "Original")
    private fun request(excluded: List<Track>) = RadioRequest(anchor, excluded.lastOrNull() ?: anchor, excluded.takeLast(10), excluded)
    @Test fun moreThanTwentySongsConsumePagesAndKeepUnselectedCandidates() = runBlocking {
        var page = 0
        val source = RadioPages { if (page < 5) List(8) { song("page${page}song$it", "Artist${it % 4}") }.also { page++ } else null }
        val station = RadioContinuation(anchor, { source }, { it })
        val played = mutableListOf(anchor)
        repeat(8) {
            val result = station.next(request(played), RecommendationPreferences())
            val accepted = RadioSelection.select(result, anchor, played, RecommendationPreferences())
            assertTrue(accepted.size in 1..6)
            played += accepted
        }
        assertTrue(played.size >= 31); assertEquals(played.size, played.map(Track::id).distinct().size)
        assertTrue(page >= 4)
        assertTrue(played.any { it.id == "page0song6" }) // Reserve entries are not discarded with each batch.
    }
    @Test fun emptyOrRepeatedPagesAreNotMistakenForTheEndOfTheStation() = runBlocking {
        var page = 0
        val station = RadioContinuation(anchor, { RadioPages { when (page++) {
            0 -> emptyList()
            1 -> listOf(anchor)
            2 -> listOf(song("fresh", "Other"))
            else -> null
        } } }, { it })
        assertTrue(station.next(request(listOf(anchor)), RecommendationPreferences()).isEmpty())
        assertEquals(listOf("fresh"), station.next(request(listOf(anchor)), RecommendationPreferences()).map(Track::id))
    }
    @Test fun exhaustingPrimaryPagesExploresOriginalStationArtistsWithoutDrift() = runBlocking {
        val original = (0..7).map { song("first$it", "B") }
        val visited = mutableListOf<String>()
        val station = RadioContinuation(anchor, { seed ->
            visited += seed.id
            var read = false
            RadioPages { if (read) null else { read = true; if (seed == anchor) original else listOf(song("connected", "B"), song("unrelated", "Z")) } }
        }, { it })
        station.next(request(listOf(anchor)), RecommendationPreferences())
        val result = station.next(request(original + anchor), RecommendationPreferences())
        assertEquals(listOf("connected"), result.map(Track::id))
        assertTrue(visited.any { it.startsWith("first") }); assertFalse(result.any { it.artist == "Z" })
    }
    @Test fun failedMatchingKeepsBothItsHintAndPreviouslyResolvedCandidates() = runBlocking {
        var pages = 0
        var fail = true
        val station = RadioContinuation(anchor, { RadioPages { if (pages++ == 0) listOf(song("one"), song("two")) else null } }, {
            if (it.id == "two" && fail) { fail = false; throw IOException("temporary") }; it
        })
        assertEquals(listOf("one"), station.next(request(emptyList()), RecommendationPreferences()).map(Track::id))
        val result = station.next(request(listOf(song("one"))), RecommendationPreferences())
        assertEquals(listOf("two"), result.map(Track::id))
    }
    @Test fun feedbackAndRecordingDeduplicationAlsoApplyToReservedCandidates() = runBlocking {
        val songs = (0..7).map { song("song$it", "Artist$it") }
        var read = false
        val station = RadioContinuation(anchor, { RadioPages { if (read) null else { read = true; songs } } }, { it })
        station.next(request(emptyList()), RecommendationPreferences())
        val choices = RecommendationPreferences(disliked = listOf(songs[0]), blockedArtists = mapOf("artist1" to "Artist1"))
        val result = station.next(request(songs.drop(2)), choices)
        assertTrue(result.isEmpty())
    }
    @Test fun cancellationDoesNotLoseAnAlreadyFetchedPage() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var wait = true
        var reads = 0
        val station = RadioContinuation(anchor, { RadioPages { if (reads++ == 0) listOf(song("one"), song("two")) else null } }, {
            if (it.id == "two" && wait) { started.complete(Unit); awaitCancellation() }; it
        })
        val job = launch { station.next(request(emptyList()), RecommendationPreferences()) }
        started.await(); job.cancelAndJoin(); wait = false
        val reserved = station.next(request(emptyList()), RecommendationPreferences())
        val remainder = station.next(request(reserved), RecommendationPreferences())
        assertEquals(setOf("one", "two"), (reserved + remainder).map(Track::id).toSet())
    }
    @Test fun changedMetadataForAQueuedIdCannotStarveTheNextPage() = runBlocking {
        val queued = song("same-id", "Known artist")
        var page = 0
        val station = RadioContinuation(anchor, { RadioPages { when (page++) {
            0 -> listOf(queued.copy(artist = "Different credit"))
            1 -> listOf(song("fresh", "Other"))
            else -> null
        } } }, { it })
        val request = request(listOf(anchor, queued))
        assertEquals(listOf("fresh"), station.next(request, RecommendationPreferences()).map(Track::id))
    }
    @Test fun anEndlessDuplicateSourceCannotSpinOrGrowOneRefillWithoutBound() = runBlocking {
        var calls = 0
        val station = RadioContinuation(anchor, { RadioPages { calls++; listOf(anchor) } }, { it })
        assertTrue(station.next(request(listOf(anchor)), RecommendationPreferences()).isEmpty())
        assertEquals(2, calls)
        assertTrue(station.next(request(listOf(anchor)), RecommendationPreferences()).isEmpty())
        assertEquals(4, calls)
    }
}
