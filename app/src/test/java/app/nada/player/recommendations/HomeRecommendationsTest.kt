package app.nada.player.recommendations

import app.nada.player.data.Track
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeRecommendationsTest {
    private fun song(id: String, artist: String = "Artist $id") = Track(id, "Song $id", artist, "", 180, "Pop")
    private val seed = song("seed", "Favorite")
    private fun input(seeds: List<Track> = listOf(seed)) = HomeRecommendationInput(
        source = "audius", videos = false, spotifyAuthorized = false,
        stats = seeds.mapIndexed { index, track -> ListeningStat(track, 3, 100L - index) },
        history = seeds, preferences = RecommendationPreferences(),
    )

    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    @Test fun unchangedInputReturnsActiveJobAndAutoplayDoesNotRecomputeHome() = runTest {
        val release = CompletableDeferred<List<Track>>()
        val compute = CountingDispatcher(StandardTestDispatcher(testScheduler))
        var calls = 0
        val owner = HomeRecommendations(this, { _, _, _, _ -> calls++; release.await() }, compute)
        val initial = input()
        val active = owner.refresh(initial)
        runCurrent()
        val computations = compute.dispatches
        assertSame(active, owner.refresh(initial.copy(history = initial.history.toList())))
        assertSame(active, owner.refresh(initial.copy(preferences = initial.preferences.copy(autoplay = true))))
        runCurrent()
        assertEquals(1, calls)
        assertEquals(computations, compute.dispatches)
        release.complete(listOf(song("fresh")))
        advanceUntilIdle()
        assertEquals(listOf("fresh"), owner.state.value.recommendations.map(Track::id))
        assertFalse(owner.state.value.loading)
    }

    @Test fun identicalCacheAndNetworkResultsRankOnceAndLoadMoreOnlyRevealsRankedItems() = runTest {
        val compute = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val tracks = List(60) { song("candidate$it", "Artist${it % 5}") }
        var calls = 0
        val owner = HomeRecommendations(this, { _, _, _, cached ->
            calls++
            cached(tracks)
            tracks.toList()
        }, compute)
        owner.refresh(input())
        advanceUntilIdle()
        assertEquals(6, owner.state.value.recommendations.size)
        assertTrue(owner.state.value.hasMore)
        assertEquals(2, compute.dispatches) // One profile calculation and one ranking of the accepted pool.
        val computations = compute.dispatches
        repeat(10) { owner.loadMore() }
        advanceUntilIdle()
        assertEquals(40, owner.state.value.recommendations.size)
        assertEquals(40, owner.state.value.recommendations.map(Track::id).distinct().size)
        assertFalse(owner.state.value.hasMore)
        assertEquals(computations, compute.dispatches)
        assertEquals(1, calls)
    }

    @Test fun changedFeedbackAndCountsReusePoolsAndFilterTheCurrentRanking() = runTest {
        var calls = 0
        val tracks = listOf(song("allowed", "Allowed artist"), song("blocked", "Blocked artist"))
        val owner = HomeRecommendations(this, { _, _, _, _ -> calls++; tracks }, StandardTestDispatcher(testScheduler))
        val initial = input()
        owner.refresh(initial)
        advanceUntilIdle()
        val updated = initial.copy(stats = listOf(ListeningStat(seed, 7, 200)),
            preferences = RecommendationPreferences(blockedArtists = mapOf("blockedartist" to "Blocked artist")))
        owner.refresh(updated)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(listOf("allowed"), owner.state.value.recommendations.map(Track::id))
        owner.refresh(updated.copy(preferences = RecommendationPreferences(disliked = listOf(tracks[0]))))
        advanceUntilIdle()
        assertEquals(listOf("blocked"), owner.state.value.recommendations.map(Track::id))
        assertEquals(1, calls)
    }

    @Test fun canceledLoadCannotPublishItsLateCacheOrFinalResultIntoNewSeeds() = runTest {
        val oldResult = CompletableDeferred<List<Track>>()
        var oldCached: ((List<Track>) -> Unit)? = null
        val newerSeed = song("new-seed", "New favorite")
        val owner = HomeRecommendations(this, { _, track, _, cached ->
            if (track.id == seed.id) {
                oldCached = cached
                withContext(NonCancellable) { oldResult.await() }
            } else listOf(song("new-result"))
        }, StandardTestDispatcher(testScheduler))
        val oldJob = owner.refresh(input())
        runCurrent()
        assertNotNull(oldCached)
        owner.refresh(input(listOf(newerSeed)))
        runCurrent()
        assertEquals(listOf("new-result"), owner.state.value.recommendations.map(Track::id))
        oldCached!!(listOf(song("late-cache")))
        oldResult.complete(listOf(song("late-result")))
        advanceUntilIdle()
        assertTrue(oldJob!!.isCancelled)
        assertEquals(listOf("new-result"), owner.state.value.recommendations.map(Track::id))
        assertEquals(listOf("New favorite"), owner.state.value.artists)
    }

    @Test fun cachedItemsRemainVisibleWhenOneSeedRefreshFails() = runTest {
        val other = song("other-seed", "Other favorite")
        val release = CompletableDeferred<Unit>()
        val owner = HomeRecommendations(this, { _, track, _, cached ->
            if (track.id == seed.id) {
                cached(listOf(song("cached")))
                release.await()
                throw IOException("offline")
            } else listOf(song("other-result"))
        }, StandardTestDispatcher(testScheduler))
        owner.refresh(input(listOf(seed, other)))
        runCurrent()
        assertTrue(owner.state.value.loading)
        assertEquals(setOf("cached", "other-result"), owner.state.value.recommendations.map(Track::id).toSet())
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(owner.state.value.loading)
        assertNotNull(owner.state.value.error)
        assertEquals(setOf("cached", "other-result"), owner.state.value.recommendations.map(Track::id).toSet())
    }

    @Test fun forceRefreshKeepsVisibleItemsUntilNewResultsAndRefetchesExistingSeeds() = runTest {
        var calls = 0
        val release = CompletableDeferred<List<Track>>()
        val owner = HomeRecommendations(this, { _, _, force, cached ->
            calls++
            if (!force) listOf(song("initial")) else {
                cached(listOf(song("initial")))
                release.await()
            }
        }, StandardTestDispatcher(testScheduler))
        owner.refresh(input())
        advanceUntilIdle()
        owner.refresh(input(), force = true)
        runCurrent()
        assertEquals(2, calls)
        assertTrue(owner.state.value.loading)
        assertEquals(listOf("initial"), owner.state.value.recommendations.map(Track::id))
        release.complete(listOf(song("refreshed")))
        advanceUntilIdle()
        assertFalse(owner.state.value.loading)
        assertEquals(listOf("refreshed"), owner.state.value.recommendations.map(Track::id))
    }

    @Test fun changedInputsWhileLoadingResumeIncompletePoolsWithoutLosingCachedItems() = runTest {
        var calls = 0
        val release = CompletableDeferred<List<Track>>()
        val owner = HomeRecommendations(this, { _, _, _, cached ->
            calls++
            cached(listOf(song("cached")))
            release.await()
        }, StandardTestDispatcher(testScheduler))
        val initial = input()
        owner.refresh(initial)
        runCurrent()
        owner.refresh(initial.copy(stats = listOf(ListeningStat(seed, 4, 200))))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf("cached"), owner.state.value.recommendations.map(Track::id))
        release.complete(listOf(song("loaded")))
        advanceUntilIdle()
        assertEquals(listOf("loaded"), owner.state.value.recommendations.map(Track::id))
        assertFalse(owner.state.value.loading)
    }

    @Test fun noSeedsClearsPriorRecommendationsWithoutCallingCatalog() = runTest {
        var calls = 0
        val owner = HomeRecommendations(this, { _, _, _, _ -> calls++; listOf(song("candidate")) }, StandardTestDispatcher(testScheduler))
        owner.refresh(input())
        advanceUntilIdle()
        owner.refresh(input(emptyList()))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(HomeRecommendationState(), owner.state.value)
    }

    @Test fun automaticCountUpdateKeepsForcedRefreshForTheSamePools() = runTest {
        val forceFlags = mutableListOf<Boolean>()
        val release = CompletableDeferred<List<Track>>()
        val owner = HomeRecommendations(this, { _, _, force, cached ->
            forceFlags += force
            cached(listOf(song("cached")))
            if (force) release.await() else listOf(song("cached"))
        }, StandardTestDispatcher(testScheduler))
        val initial = input()
        owner.refresh(initial)
        advanceUntilIdle()
        owner.refresh(initial, force = true)
        runCurrent()
        owner.refresh(initial.copy(stats = listOf(ListeningStat(seed, 4, 200))))
        runCurrent()
        assertEquals(listOf(false, true, true), forceFlags)
        assertTrue(owner.state.value.loading)
        release.complete(listOf(song("fresh")))
        advanceUntilIdle()
        assertEquals(listOf("fresh"), owner.state.value.recommendations.map(Track::id))
        assertFalse(owner.state.value.loading)
        owner.refresh(initial.copy(stats = listOf(ListeningStat(seed, 5, 300))))
        advanceUntilIdle()
        assertEquals(3, forceFlags.size)
    }

    @Test fun forceIntentSurvivesAnInputRevisionBeforeItsProfileHasFinished() = runTest {
        val forceFlags = mutableListOf<Boolean>()
        val owner = HomeRecommendations(this, { _, _, force, _ ->
            forceFlags += force
            listOf(song("fresh"))
        }, StandardTestDispatcher(testScheduler))
        val initial = input()
        owner.refresh(initial, force = true)
        owner.refresh(initial.copy(stats = listOf(ListeningStat(seed, 4, 200))))
        advanceUntilIdle()
        assertEquals(listOf(true), forceFlags)
        assertEquals(listOf("fresh"), owner.state.value.recommendations.map(Track::id))
    }

    @Test fun changingTheSeedPoolDoesNotCarryAnUnfinishedForceRequest() = runTest {
        val forceFlags = mutableListOf<Pair<String, Boolean>>()
        val release = CompletableDeferred<List<Track>>()
        val owner = HomeRecommendations(this, { _, track, force, _ ->
            forceFlags += track.id to force
            if (force) release.await() else listOf(song("result${track.id}"))
        }, StandardTestDispatcher(testScheduler))
        owner.refresh(input(), force = true)
        runCurrent()
        owner.refresh(input(listOf(song("different", "Different artist"))))
        advanceUntilIdle()
        assertEquals(listOf(seed.id to true, "different" to false), forceFlags)
        assertFalse(owner.state.value.loading)
        assertEquals(listOf("resultdifferent"), owner.state.value.recommendations.map(Track::id))
        release.complete(listOf(song("old")))
    }

    @Test fun awaitIdleFollowsAReplacementJobUntilItsRankingCompletes() = runTest {
        val first = CompletableDeferred<List<Track>>()
        val second = CompletableDeferred<List<Track>>()
        val owner = HomeRecommendations(this, { _, track, _, _ ->
            if (track.id == seed.id) first.await() else second.await()
        }, StandardTestDispatcher(testScheduler))
        owner.refresh(input())
        runCurrent()
        var finished = false
        val waiter = launch { owner.awaitIdle(); finished = true }
        runCurrent()
        owner.refresh(input(listOf(song("different", "Different artist"))))
        runCurrent()
        assertFalse(finished)
        assertTrue(waiter.isActive)
        second.complete(listOf(song("latest")))
        advanceUntilIdle()
        assertTrue(finished)
        assertEquals(listOf("latest"), owner.state.value.recommendations.map(Track::id))
        assertFalse(owner.state.value.loading)
        first.complete(emptyList())
    }
}
