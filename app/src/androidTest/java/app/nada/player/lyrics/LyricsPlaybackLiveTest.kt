package app.nada.player.lyrics

import android.content.ComponentName
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.MainActivity
import app.nada.player.data.Track
import app.nada.player.playback.PlaybackService
import app.nada.player.playback.toMediaItem
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.math.abs

/** Real catalog, resolver, Media3 service, view model and production lyrics UI. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LyricsPlaybackLiveTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var controller: MediaController
    private val measurements = mutableListOf<Map<String, Any>>()
    private fun main(block: () -> Unit) = rule.runOnUiThread(block)
    private fun awaitPlayer(label: String, timeout: Long = 40_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            main { assertNull("$label: ${controller.playerError?.errorCodeName}", controller.playerError); done = condition() }
            if (done) return
            Thread.sleep(50)
        }
        fail("Timeout: $label")
    }
    private fun expectSelected(line: LyricLine) {
        rule.waitUntil(8_000) { rule.onAllNodes(hasText(line.text) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText(line.text) and isSelected()).assertIsDisplayed()
    }
    private fun stableLines(document: LyricsDocument) = document.lines.filterIndexed { index, line ->
        line.text.isNotBlank() && line.timeMs > 5_000 && document.lines.getOrNull(index + 1)?.timeMs?.minus(line.timeMs)?.let { it >= 2_000 } == true &&
            document.lines.count { it.text == line.text } == 1
    }
    private fun seekCheck(key: String, document: LyricsDocument, fraction: Double) {
        val candidates = stableLines(document)
        val line = candidates.minBy { abs(it.timeMs - document.duration * 1000 * fraction) }
        val target = line.timeMs + 500
        val start = SystemClock.elapsedRealtime()
        main { controller.pause(); controller.seekTo(target) }
        awaitPlayer("seek $key/$fraction") { abs(controller.currentPosition - target) <= 800 && controller.playbackState == Player.STATE_READY }
        expectSelected(line)
        var position = 0L
        main { position = controller.currentPosition }
        measurements += mapOf("stage" to key, "fraction" to fraction, "targetMs" to target,
            "positionMs" to position, "deltaMs" to abs(position - target), "highlightSettledMs" to SystemClock.elapsedRealtime() - start)
    }
    @Test fun realPlaybackSeeksAndCrossLanguageTransitionsKeepLyricsAligned() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveLyricsPlayback") == "true")
        val key = args.getString("lyricsSong") ?: "yoru"
        val index = lyricsSamples.indexOfFirst { it.key == key }
        require(index >= 0)
        val context = rule.activity.applicationContext
        // Reuse metadata retained by LyricsCatalogLiveTest, not a second catalog interpretation.
        val records = File(context.noBackupFilesDir, "lyrics-audit-catalog.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
        val pair = listOf(lyricsSamples[index], lyricsSamples[(index + 3) % lyricsSamples.size])
        val tracks = pair.map { sample -> Gson().fromJson(records.first { it.asJsonObject["key"].asString == sample.key }.asJsonObject["track"], Track::class.java) }
        val repository = LyricsRepository(context)
        var connected = false
        try {
            val documents = tracks.map { repository.load(it).first ?: error("Lyrics unavailable for ${it.title}") }
            assertTrue(documents.all { it.synced })
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            controller = future.get(10, TimeUnit.SECONDS)
            connected = true
            main {
                controller.stop(); controller.clearMediaItems()
                controller.setMediaItems(tracks.map(Track::toMediaItem))
                controller.repeatMode = Player.REPEAT_MODE_OFF; controller.shuffleModeEnabled = false
                controller.prepare(); controller.play()
            }
            awaitPlayer("first audio advances") { controller.isPlaying && controller.currentPosition > 2_000 }
            rule.waitUntil(10_000) { rule.onAllNodes(hasText(tracks[0].title) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodes(hasText(tracks[0].title) and hasClickAction()).onLast().performClick()
            rule.onNodeWithText("Lirik").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Mengikuti pemutaran", substring = true).fetchSemanticsNodes().isNotEmpty() }
            seekCheck(key, documents[0], 0.15)
            seekCheck(key, documents[0], 0.65)
            seekCheck(key, documents[0], 0.30)
            val clickLine = stableLines(documents[0]).first()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(clickLine.text))
            rule.onNode(hasText(clickLine.text) and hasClickAction()).performClick()
            awaitPlayer("tapped line seeks") { abs(controller.currentPosition - clickLine.timeMs) < 800 }
            expectSelected(clickLine)
            if (clickLine.romaji != null) {
                val roman = clickLine.romaji
                rule.onNodeWithText(roman).assertIsDisplayed()
                rule.onNodeWithText("Romaji").performClick()
                rule.onAllNodesWithText(roman).assertCountEquals(0)
                rule.onNodeWithText("Romaji").performClick()
            }
            rule.onNodeWithContentDescription("Lagu berikutnya").performClick()
            main { controller.play() }
            awaitPlayer("next audio advances") { controller.currentMediaItemIndex == 1 && controller.isPlaying && controller.currentPosition > 1_000 }
            // A song title can also recur verbatim inside its lyrics (e.g. Dan...).
            rule.onAllNodesWithText(tracks[1].title).onFirst().assertIsDisplayed()
            seekCheck(pair[1].key, documents[1], 0.45)
            rule.onAllNodes(hasText(clickLine.text) and isSelected()).assertCountEquals(0)
            // First Previous restarts a song after 3s; from its beginning it returns to the prior item.
            main { controller.seekTo(0) }
            awaitPlayer("next at beginning") { controller.currentPosition < 1_000 }
            rule.onNodeWithContentDescription("Lagu sebelumnya").performClick()
            awaitPlayer("previous item") { controller.currentMediaItemIndex == 0 }
            seekCheck(key, documents[0], 0.5)
            main { controller.seekTo(controller.duration - 1_200); controller.play() }
            awaitPlayer("automatic song boundary") { controller.currentMediaItemIndex == 1 && controller.isPlaying && controller.currentPosition > 1_000 }
            rule.onAllNodesWithText(tracks[1].title).onFirst().assertIsDisplayed()
            seekCheck(pair[1].key, documents[1], 0.25)
            android.util.Log.i("NadaLyricsPlayback", "PASS $key -> ${pair[1].key}; forward/back/tap/next/previous/automatic; checks=${measurements.size}")
        } finally {
            File(context.noBackupFilesDir, "lyrics-playback-$key.json").writeText(Gson().toJson(measurements))
            if (connected) main { controller.stop(); controller.clearMediaItems(); controller.release() }
            repository.close()
        }
    }
}
