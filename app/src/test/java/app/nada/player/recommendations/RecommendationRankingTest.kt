package app.nada.player.recommendations

import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test

class RecommendationRankingTest {
    private fun song(id: String, artist: String = "A") = Track(id, id, artist, "", 180, "Pop")
    @Test fun aLargeFavoriteArtistPoolCannotCrowdOutRelatedArtists() {
        val pool = (0..11).map { song("a$it") } + (0..4).map { song("b$it", "B") } + (0..4).map { song("c$it", "C") }
        val result = RecommendationRanking.choose(pool, listOf(song("seed")), listOf(ListeningStat(song("favorite"), 10000, 1)), 6)
        assertEquals(6, result.size)
        assertEquals(setOf("A", "B", "C"), result.map { it.artist }.toSet())
        assertTrue(result.zipWithNext().all { it.first.artist != it.second.artist })
        assertTrue(result.groupingBy { it.artist }.eachCount().values.all { it <= 2 })
        assertNotEquals("A", result.first().artist)
    }
    @Test fun queueTailAndPlayedAutomaticSongsInfluenceTheNextWindow() {
        val pool = listOf(song("a1"), song("b1", "B"), song("c1", "C"), song("b2", "B"), song("c2", "C"))
        val result = RecommendationRanking.choose(pool, listOf(song("old1"), song("old2")), emptyList(), 3)
        assertEquals(listOf("B", "C", "B"), result.map { it.artist })
    }
    @Test fun historyCanBoostOnlyRelatedCandidatesAndCannotInjectAnUnrelatedFavorite() {
        val a = song("a"); val b = song("b", "B")
        val stats = listOf(ListeningStat(b, 10, 1), ListeningStat(song("unrelated", "Elsewhere"), 10000, 2))
        assertEquals(listOf(b, a), RecommendationRanking.choose(listOf(a, b), emptyList(), stats, 6))
    }
    @Test fun paginationKeepsItsPrefixAndSingleArtistPoolsRemainUsable() {
        val pool = (0..29).map { song("id$it", ('A' + it % 5).toString()) }
        assertEquals(RecommendationRanking.choose(pool, emptyList(), emptyList(), 6), RecommendationRanking.choose(pool, emptyList(), emptyList(), 12).take(6))
        val single = (0..9).map { song("single$it") }
        assertEquals(single.take(6), RecommendationRanking.choose(single, listOf(song("seed")), emptyList(), 6))
    }
    @Test fun channelAliasesAndRemastersKeepOneRecordingIdentity() {
        assertEquals(ListeningRecommendations.artistKey("NOAH"), ListeningRecommendations.artistKey("NOAH OFFICIAL"))
        assertEquals(ListeningRecommendations.artistKey("Sheila On 7"), ListeningRecommendations.artistKey("Sheila on 7 Tv"))
        assertEquals(ListeningRecommendations.recordingKey(song("a").copy(title = "Song")),
            ListeningRecommendations.recordingKey(song("b").copy(title = "Song (Remastered 2023)")))
    }
    @Test fun neighborsCannotDriftAwayFromTheOriginalStationOrInjectAnotherSource() {
        val seed = song("seed")
        val related = song("related", "B")
        val next = song("newRelated", "B")
        val request = RadioRequest(seed, related)
        assertEquals(listOf(related, next, song("newSeedArtist")), request.mergePools(listOf(related),
            listOf(next, song("remote", "Unconnected"), song("newSeedArtist"), song("wrongSource", "B").copy(source = "youtube"))))
        assertTrue(request.mergePools(emptyList(), listOf(song("remote", "Unconnected"))).isEmpty())
    }
    @Test fun aRecentInterestGetsASeedWithoutDisplacingBothLongTermFavorites() {
        val stats = listOf(ListeningStat(song("favorite"), 100, 1), ListeningStat(song("second", "B"), 40, 2),
            ListeningStat(song("third", "C"), 30, 3), ListeningStat(song("recent", "D"), 1, 100))
        assertEquals(listOf("A", "B", "D"), ListeningRecommendations.seeds(stats, emptyList(), "audius").map { it.artist })
    }
    @Test fun continuationExploresOtherOriginalArtistsInsteadOfOnlyFollowingTheTail() {
        val seed = song("seed")
        val pool = listOf(song("b", "B"), song("c", "C"), song("d", "D"), song("e", "E"))
        val tail = song("tail", "E")
        val recent = listOf(song("old", "D"), song("b1", "B"), song("b2", "B"), song("e1", "E"), tail)
        assertEquals(listOf(tail, pool[1], pool[2]), RadioRequest(seed, tail, recent).expansionSeeds(pool))
        assertTrue(RadioRequest(seed, seed, recent).expansionSeeds(pool).isEmpty())
        val foreign = song("foreign").copy(source = "youtube")
        assertFalse(RadioRequest(seed, tail).expansionSeeds(listOf(foreign)).contains(foreign))
    }
}
