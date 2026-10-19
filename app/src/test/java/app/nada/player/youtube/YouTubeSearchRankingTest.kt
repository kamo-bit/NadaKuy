package app.nada.player.youtube

import app.nada.player.data.Track
import org.junit.Assert.*
import org.junit.Test

class YouTubeSearchRankingTest {
    private fun entry(title: String, artist: String, verified: Boolean = false) =
        YouTubeSearchRanking.Entry(Track("$title:$artist", title, artist, "", 240, "YouTube Music", source = "youtube"), verified)

    @Test fun matchesNamedArtistAndStudioBeforeCoverLiveAndUnrelatedSongs() {
        val cover = entry("Kangen - Dewa 19 (Cover)", "Penyanyi Lain", true)
        val live = entry("Kangen (Live Version)", "Dewa 19")
        val other = entry("Aku Milikmu", "Dewa 19")
        val original = entry("Kangen", "Dewa 19")
        val ranked = YouTubeSearchRanking.rank("Dewa19 Kangen", listOf(cover, live, other, original))
        assertEquals(original.track, ranked.first())
        assertTrue(ranked.indexOf(original.track) < ranked.indexOf(cover.track))
        assertEquals(4, ranked.size) // Keep alternatives available, preserving source metadata.
    }

    @Test fun titleOnlyQueryLowersLiveAndKeepsUpstreamOrderForUnknownOriginality() {
        val live = entry("Kangen (Live)", "Dewi Kirana")
        val original = entry("Kangen", "Dewa 19")
        val alternative = entry("Kangen", "Yura Yunita")
        val unrelated = entry("Tentang Aku Kau Dan Dia", "Kangen Band")
        val ranked = YouTubeSearchRanking.rank("Kangen", listOf(live, unrelated, original, alternative))
        assertEquals(listOf(original.track, alternative.track, live.track, unrelated.track), ranked)
    }

    @Test fun explicitLiveAndCoverRequestsRemainUseful() {
        val studio = entry("Dan", "Sheila On 7")
        val live = entry("Dan (Live at Jakarta)", "Sheila On 7")
        val cover = entry("Dan (Cover)", "Penyanyi Lain")
        assertEquals(live.track, YouTubeSearchRanking.rank("Sheila On 7 Dan live", listOf(studio, live, cover)).first())
        assertEquals(cover.track, YouTubeSearchRanking.rank("Dan cover", listOf(studio, live, cover)).first())
    }

    @Test fun artistRequestedByUserTakesPriorityOverAnotherRecording() {
        val original = entry("Kangen", "Dewa 19")
        val requested = entry("Kangen", "Yura Yunita")
        assertEquals(requested.track, YouTubeSearchRanking.rank("Yura Yunita Kangen", listOf(original, requested)).first())
    }

    @Test fun normalizesAccentsAndDoesNotTreatLiveForeverAsLivePerformance() {
        val other = entry("Other Song", "Beyonce")
        val wanted = entry("Halo", "Beyoncé")
        assertEquals(wanted.track, YouTubeSearchRanking.rank("Beyonce Halo", listOf(other, wanted)).first())
        val studio = entry("Live Forever", "Oasis")
        val concert = entry("Wonderwall (Live)", "Oasis")
        assertEquals(studio.track, YouTubeSearchRanking.rank("Oasis", listOf(concert, studio)).first())
    }

    @Test fun officialAudioOutranksTourVideoWithoutTheWordLive() {
        val concert = entry("Kangen - Dewa19 Feat Ari Lasso [Gelora Bung Karno Jakarta - Stadium Tour 2023]", "Dewa19", true)
        val studio = entry("Dewa 19 - Kangen (Official Audio)", "GP Records", true)
        assertEquals(studio.track, YouTubeSearchRanking.rank("Dewa19 Kangen official", listOf(concert, studio)).first())
    }

    @Test fun verifiedCoverCannotOutrankRelevantTopicRecordingAndIrrelevantOfficialCannotLead() {
        val cover = entry("Dewa 19 Kangen (Cover)", "Famous Cover Artist", true)
        val original = entry("Kangen", "Dewa 19 - Topic")
        val unrelated = entry("Another Song (Official Audio)", "Dewa 19", true)
        assertEquals(listOf(original.track, cover.track, unrelated.track), YouTubeSearchRanking.rank("Dewa19 Kangen", listOf(cover, unrelated, original)))
    }

    @Test fun japaneseCoverAndFirstTakeAreAlternativesUnlessRequested() {
        val cover = entry("Lemon 歌ってみた", "Singer", true)
        val live = entry("Lemon - THE FIRST TAKE", "Artist", true)
        val studio = entry("Lemon", "Artist - Topic")
        assertEquals(studio.track, YouTubeSearchRanking.rank("Lemon", listOf(cover, live, studio)).first())
        assertEquals(live.track, YouTubeSearchRanking.rank("Lemon first take", listOf(studio, live)).first())
    }

    @Test fun songNamedLiveForeverIsNotAnExplicitLiveRequest() {
        val studio = entry("Live Forever", "Oasis - Topic")
        val live = entry("Live Forever (Live at Wembley)", "Oasis", true)
        assertEquals(studio.track, YouTubeSearchRanking.rank("Live Forever", listOf(live, studio)).first())
    }
}
