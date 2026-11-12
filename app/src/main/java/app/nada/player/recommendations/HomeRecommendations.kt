package app.nada.player.recommendations

import app.nada.player.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HomeRecommendationInput(
    val source: String,
    val videos: Boolean,
    val spotifyAuthorized: Boolean,
    val stats: List<ListeningStat>,
    val history: List<Track>,
    val preferences: RecommendationPreferences,
) {
    fun hasSameContent(other: HomeRecommendationInput): Boolean =
        source == other.source && videos == other.videos && spotifyAuthorized == other.spotifyAuthorized &&
            stats == other.stats && history == other.history &&
            preferences.disliked == other.preferences.disliked && preferences.blockedArtists == other.preferences.blockedArtists
}

internal data class HomeRecommendationState(
    val recommendations: List<Track> = emptyList(),
    val frequent: List<Track> = emptyList(),
    val artists: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
)

/** UI-scope owner; pool callbacks run on that scope, while profile and ranking work use the compute dispatcher. */
internal class HomeRecommendations(
    private val scope: CoroutineScope,
    private val loadPool: suspend (HomeRecommendationInput, Track, Boolean, (List<Track>) -> Unit) -> List<Track>,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class PoolKey(val source: String, val videos: Boolean, val authorized: Boolean, val seeds: List<String>)
    private data class Profile(val seeds: List<ListeningRecommendations.Seed>, val frequent: List<Track>, val forcedPool: PoolKey?)
    private val mutable = MutableStateFlow(HomeRecommendationState())
    val state = mutable.asStateFlow()
    private var input: HomeRecommendationInput? = null
    private var generation = 0L
    private var job: Job? = null
    private var rankingJob: Job? = null
    private var rankingVersion = 0L
    private var poolKey: PoolKey? = null
    private var pools: List<List<Track>> = emptyList()
    private var seeds: List<ListeningRecommendations.Seed> = emptyList()
    private var poolsComplete = false
    private var forceInput: HomeRecommendationInput? = null
    private var forcedPool: PoolKey? = null
    private var ranked: List<Track> = emptyList()
    private var visibleLimit = PAGE_SIZE

    fun refresh(next: HomeRecommendationInput, force: Boolean = false): Job? {
        if (!force && input?.hasSameContent(next) == true) return job
        val expected = ++generation
        input = next
        job?.cancel()
        rankingJob?.cancel()
        if (force) {
            forceInput = next
            forcedPool = null
        } else if (forceInput?.let { it.source != next.source || it.videos != next.videos || it.spotifyAuthorized != next.spotifyAuthorized } == true) {
            clearForceRequest()
        }
        val requestedForce = forceInput
        val knownForcedPool = forcedPool
        if (poolKey?.source != next.source) {
            ranked = emptyList()
            mutable.value = HomeRecommendationState()
        }
        return scope.launch {
            val profile = withContext(computeDispatcher) {
                val selectedSeeds = ListeningRecommendations.seeds(next.stats, next.history, next.source, next.preferences)
                val forceKey = knownForcedPool ?: requestedForce?.let { requested ->
                    val requestedSeeds = if (requested.hasSameContent(next)) selectedSeeds else
                        ListeningRecommendations.seeds(requested.stats, requested.history, requested.source, requested.preferences)
                    requested.poolKey(requestedSeeds)
                }
                Profile(selectedSeeds, ListeningRecommendations.frequent(next.stats, next.source), forceKey)
            }
            ensureActive()
            if (expected != generation) return@launch
            val key = next.poolKey(profile.seeds)
            val refreshPools = profile.forcedPool == key
            if (refreshPools) forcedPool = key else clearForceRequest()
            val changedPools = key != poolKey
            val fetch = refreshPools || changedPools || !poolsComplete
            if (changedPools) {
                poolKey = key
                pools = profile.seeds.map { emptyList() }
                ranked = emptyList()
                visibleLimit = PAGE_SIZE
                poolsComplete = false
            }
            seeds = profile.seeds
            mutable.value = mutable.value.copy(frequent = profile.frequent, artists = seeds.map { it.artist },
                recommendations = if (changedPools) emptyList() else mutable.value.recommendations,
                hasMore = if (changedPools) false else mutable.value.hasMore,
                loading = fetch && seeds.isNotEmpty(), error = if (fetch) null else mutable.value.error)
            scheduleRanking(next, expected)
            if (fetch && seeds.isNotEmpty()) {
                poolsComplete = false
                val failures = coroutineScope {
                    seeds.mapIndexed { index, seed -> async {
                        fun accept(tracks: List<Track>) {
                            if (expected != generation || pools[index] == tracks) return
                            pools = pools.mapIndexed { i, old -> if (i == index) tracks.toList() else old }
                            scheduleRanking(next, expected)
                        }
                        try {
                            val tracks = loadPool(next, seed.track, refreshPools, ::accept)
                            ensureActive()
                            accept(tracks)
                            false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            true
                        }
                    } }.awaitAll().count { it }
                }
                ensureActive()
                if (expected != generation) return@launch
                poolsComplete = true
                rankingJob?.join()
                clearForceRequest()
                mutable.value = mutable.value.copy(loading = false,
                    error = if (failures > 0) "Sebagian rekomendasi belum bisa dimuat. Periksa internet, lalu coba lagi." else null)
            } else {
                poolsComplete = true
                rankingJob?.join()
                if (expected == generation) {
                    clearForceRequest()
                    mutable.value = mutable.value.copy(loading = false)
                }
            }
        }.also { job = it }
    }

    suspend fun awaitIdle() {
        while (true) {
            val latest = job ?: return
            latest.join()
            if (latest === job) return
        }
    }

    private fun HomeRecommendationInput.poolKey(selectedSeeds: List<ListeningRecommendations.Seed>) =
        PoolKey(source, videos, spotifyAuthorized, selectedSeeds.map { it.track.id })

    private fun clearForceRequest() {
        forceInput = null
        forcedPool = null
    }

    fun loadMore() {
        if (!mutable.value.hasMore) return
        visibleLimit = (visibleLimit + PAGE_SIZE).coerceAtMost(MAX_RECOMMENDATIONS)
        publishRanked()
    }

    private fun scheduleRanking(snapshot: HomeRecommendationInput, expected: Long) {
        val version = ++rankingVersion
        rankingJob?.cancel()
        val selectedPools = pools
        val selectedSeeds = seeds
        if (selectedPools.all { it.isEmpty() }) {
            ranked = emptyList()
            publishRanked()
            return
        }
        rankingJob = scope.launch {
            val result = withContext(computeDispatcher) {
                ListeningRecommendations.select(selectedPools, selectedSeeds, snapshot.stats.map { it.track } + snapshot.history,
                    snapshot.source, limit = MAX_RECOMMENDATIONS, preferences = snapshot.preferences, stats = snapshot.stats)
            }
            ensureActive()
            if (expected != generation || version != rankingVersion) return@launch
            ranked = result
            publishRanked()
        }
    }

    private fun publishRanked() {
        mutable.value = mutable.value.copy(recommendations = ranked.take(visibleLimit), hasMore = ranked.size > visibleLimit)
    }

    private companion object {
        const val PAGE_SIZE = 6
        const val MAX_RECOMMENDATIONS = 40
    }
}
