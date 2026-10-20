package app.nada.player.recommendations

import app.nada.player.NadaApplication
import app.nada.player.data.CatalogQuery
import app.nada.player.data.MusicRepository
import app.nada.player.data.Track
import app.nada.player.youtube.YouTubeCatalog
import app.nada.player.youtube.YouTubeSearchRanking
import kotlinx.coroutines.CancellationException

internal data class RadioRequest(val anchor: Track, val frontier: Track, val recent: List<Track> = emptyList()) {
    fun expansionSeeds(origin: List<Track>): List<Track> {
        if (frontier.id == anchor.id || frontier.source != anchor.source) return emptyList()
        val window = recent.takeLast(10).map { ListeningRecommendations.artistKey(it.artist) }
        val counts = window.groupingBy { it }.eachCount()
        val tailArtist = ListeningRecommendations.artistKey(frontier.artist)
        val alternatives = origin.filter { it.source == anchor.source && it.id != anchor.id }
            .distinctBy { ListeningRecommendations.artistKey(it.artist) }
            .filter { ListeningRecommendations.artistKey(it.artist) != tailArtist }
            .sortedWith(compareBy<Track> { counts[ListeningRecommendations.artistKey(it.artist)] ?: 0 }
                .thenBy { window.lastIndexOf(ListeningRecommendations.artistKey(it.artist)) })
        // Explore other original-mix artists too, so a tail artist cannot own every later refill.
        return listOf(frontier) + alternatives.take(2)
    }

    fun mergePools(origin: List<Track>, neighbors: List<Track>): List<Track> {
        val artists = (origin + anchor).mapTo(hashSetOf()) { ListeningRecommendations.artistKey(it.artist) }
        val nearby = neighbors.filter { it.source == anchor.source && ListeningRecommendations.artistKey(it.artist) in artists }
        // New recordings can extend the station, but their artists must remain connected to its original mix.
        return (0 until maxOf(origin.size, nearby.size)).flatMap { listOfNotNull(origin.getOrNull(it), nearby.getOrNull(it)) }.distinctBy(Track::id)
    }
}

internal class RadioCatalog(private val app: NadaApplication) {
    private val audius = MusicRepository(app)

    suspend fun related(request: RadioRequest): List<Track> {
        val origin = pool(request.anchor)
        val nearby = mutableListOf<Track>()
        for (seed in request.expansionSeeds(origin)) {
            try { nearby += pool(seed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retain the original mix and any successful neighboring pools. */ }
        }
        return request.mergePools(origin, nearby)
    }

    suspend fun pool(seed: Track, refresh: Boolean = false, cached: (List<Track>) -> Unit = {}): List<Track> =
        app.catalogCache.load(CatalogQuery(seed.source, false, "radio-mix-v4:${seed.id}"), refresh, cached) {
            if (seed.source == "youtube") {
                val mix = try { YouTubeCatalog.radio(seed) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { YouTubeCatalog.related(seed) }
                val hints = RadioSelection.select(mix, seed, listOf(seed), RecommendationPreferences(), limit = 8)
                val music = mutableListOf<Track>()
                var failure: Exception? = null
                for (hint in hints) {
                    // Mix bylines can be channel names. Resolve artist identity once in the songs catalog.
                    val matching = RelatedRecordingMatch(hint)
                    try {
                        matching.select(YouTubeCatalog.search(matching.query, refresh = refresh))?.let(music::add)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failure = error }
                }
                if (music.isEmpty() && failure != null) throw failure
                music
            } else (audius.search(seed.artist) + audius.trending(seed.genre.takeIf(String::isNotBlank))).distinctBy(Track::id)
        }
}

/** A performer named in a cover title is not the recording artist. Keep identity in its metadata field. */
internal class RelatedRecordingMatch(private val hint: Track) {
    private val title = hint.title.replace(Regex("\\s*\\(${Regex.escape(hint.artist)}\\)\\s*$", RegexOption.IGNORE_CASE), "").trim()
    private val prefix = creditedPrefix.matchEntire(title)?.groupValues?.get(1)
    private val artists = listOfNotNull(hint.artist, prefix).mapTo(hashSetOf(), ListeningRecommendations::artistKey)
    val query: String = if (prefix != null) title else "$title ${ListeningRecommendations.artistName(hint.artist)}"
    fun select(candidates: List<Track>): Track? {
        val eligible = candidates.filter { it.source == hint.source && it.duration in 30..900 && YouTubeSearchRanking.originalPreferred(it.title) &&
            kotlin.math.abs(it.duration - hint.duration) <= maxOf(25, hint.duration / 8) }
        return eligible.firstOrNull { it.id == hint.id }
            ?: eligible.firstOrNull { ListeningRecommendations.artistKey(it.artist) in artists }
    }
    companion object { private val creditedPrefix = Regex("^(.{1,100}?)\\s+[-–—]\\s+.+$") }
}

internal object RadioSelection {
    fun select(candidates: List<Track>, seed: Track, excluded: List<Track>, preferences: RecommendationPreferences,
        stats: List<ListeningStat> = emptyList(), limit: Int = 6): List<Track> {
        val ids = excluded.mapTo(hashSetOf()) { it.id }
        val recordings = excluded.mapTo(hashSetOf(), ListeningRecommendations::recordingKey)
        val eligible = candidates.filter { it.source == seed.source && it.playableInNada && it.duration in 30..900 && preferences.allows(it) &&
            (it.source != "youtube" || YouTubeSearchRanking.originalPreferred(it.title)) &&
            it.id !in ids && recordings.add(ListeningRecommendations.recordingKey(it)) }
        return RecommendationRanking.choose(eligible, excluded.ifEmpty { listOf(seed) }, stats, limit)
    }
}
