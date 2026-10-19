package app.nada.player.lyrics

import app.nada.player.data.Track
import app.nada.player.youtube.YouTubeCatalog
import java.text.Normalizer
import java.util.Locale

/** Metadata only; copyrighted lyric text is fetched by opt-in tests, never checked in. */
internal data class LyricsSample(val key: String, val query: String, val artists: Set<String>,
    val titles: Set<String>, val duration: IntRange) {
    suspend fun find(): Track {
        val results = YouTubeCatalog.search(query)
        return results.firstOrNull { track ->
            artists.any { normalized(it) == normalized(track.artist) } &&
                titles.any { normalized(it) == normalized(track.title) } && track.duration in duration
        } ?: error("No matching studio recording for $key: " + results.take(8).joinToString { "${it.title}/${it.artist}/${it.duration}" })
    }
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}

internal val lyricsSamples = listOf(
    LyricsSample("yoru", "YOASOBI Yoru ni kakeru", setOf("YOASOBI"), setOf("夜に駆ける", "Yoru ni Kakeru"), 255..265),
    LyricsSample("lemon", "Kenshi Yonezu Lemon", setOf("Kenshi Yonezu", "米津玄師"), setOf("Lemon"), 245..270),
    LyricsSample("refrain", "Aimer Ref:rain", setOf("Aimer"), setOf("Ref:rain"), 265..310),
    LyricsSample("kangen", "Dewa 19 Kangen", setOf("Dewa 19"), setOf("Kangen"), 295..310),
    LyricsSample("dan", "Sheila On 7 Dan", setOf("Sheila On 7"), setOf("Dan", "Dan..."), 280..295),
    LyricsSample("yangterdalam", "Peterpan Yang Terdalam", setOf("Peterpan"), setOf("Yang Terdalam"), 180..250),
)
