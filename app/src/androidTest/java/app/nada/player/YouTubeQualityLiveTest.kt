package app.nada.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.youtube.YouTubeCatalog
import app.nada.player.playback.createMusicPlayback
import app.nada.player.playback.toMediaItem
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

@RunWith(AndroidJUnit4::class)
class YouTubeQualityLiveTest {
    @Test fun searchAndPlaybackQuality() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveQuality") == "true")
        for (query in listOf("Dewa 19 Kangen", "Kangen", "Mahalini Sisa Rasa", "Sheila On 7 Dan")) {
            val tracks = YouTubeCatalog.search(query)
            assertTrue("No results for $query", tracks.isNotEmpty())
            val handler = ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf("music_songs"), "")
            SearchInfo.getInfo(ServiceList.YouTube, handler).relatedItems.filterIsInstance<StreamInfoItem>().take(12).forEach {
                android.util.Log.i("NadaQualityTest", "Raw [$query]: ${it.name} | ${it.uploaderName} | verified=${it.isUploaderVerified} | ${it.url}")
            }
            tracks.take(5).forEach { android.util.Log.i("NadaQualityTest", "Ranked [$query]: ${it.title} | ${it.artist} | ${it.id}") }
            if (query == "Dewa 19 Kangen") assertTrue(tracks.first().artist.contains("Dewa", true))
            if (query == "Kangen") {
                assertTrue("Song title must outrank unrelated Kangen Band songs", tracks.first().title.equals("Kangen", true))
                assertTrue(tracks.first().artist.contains("Dewa", true))
            }
            if (query == "Sheila On 7 Dan") assertTrue(tracks.first().artist.contains("Sheila", true))
            if (query == "Dewa 19 Kangen") {
                val info = StreamInfo.getInfo(ServiceList.YouTube, tracks.first().permalink)
                info.audioStreams.forEach {
                    android.util.Log.i("NadaQualityTest", "Stream: id=${it.id}; format=${it.format}; codec=${it.codec}; kbps=${it.averageBitrate}; bps=${it.bitrate}; type=${it.audioTrackType}; track=${it.audioTrackName}; delivery=${it.deliveryMethod}")
                }
            }
        }
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    @Test fun productionDecoderAndAudioTrack() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveQuality") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        try {
        val args = InstrumentationRegistry.getArguments()
        val queries = args.getString("qualityQueries")?.split(',')?.map { it.replace('_', ' ') }
            ?: listOf("Dewa 19 Kangen", "Sheila On 7 Dan")
        for (query in queries) {
            val track = YouTubeCatalog.search(query).first()
            val resolved = YouTubeCatalog.resolve(track.id.removePrefix("youtube:"))
            android.util.Log.i("NadaQualityTest", "Selected [$query]: ${track.title} | ${track.artist}; codec=${resolved.codec}; bps=${resolved.bitrate}")
            val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
            var underruns = 0
            var format: Format? = null
            lateinit var player: androidx.media3.exoplayer.ExoPlayer
            lateinit var controls: androidx.media3.common.Player
            instrumentation.runOnMainSync {
                val playback = createMusicPlayback(instrumentation.targetContext)
                player = playback.engine
                controls = playback.player
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, value: Format, evaluation: DecoderReuseEvaluation?) {
                        format = value
                        android.util.Log.i("NadaQualityTest", "Decoded [$query]: ${value.sampleMimeType}; ${value.sampleRate} Hz; ${value.channelCount} channels; ${value.bitrate} bps")
                    }
                    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        android.util.Log.i("NadaQualityTest", "Decoder [$query]: $name")
                    }
                    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                        underruns++
                        android.util.Log.i("NadaQualityTest", "Underrun [$query]: bufferMs=$bufferSizeMs; elapsed=$elapsedSinceLastFeedMs")
                    }
                    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) { errors += error.errorCodeName }
                })
                controls.setMediaItem(track.toMediaItem()); controls.prepare(); controls.play()
            }
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
                var position = 0L
                var playing = false
                while (android.os.SystemClock.elapsedRealtime() < deadline && errors.isEmpty()) {
                    Thread.sleep(500)
                    instrumentation.runOnMainSync { position = player.currentPosition; playing = player.isPlaying }
                    if (position >= 20_000 && playing) break
                }
                assertTrue("Playback errors: $errors", errors.isEmpty())
                assertNotNull("No decoded audio format", format)
                args.getString("expectedMime")?.let { assertEquals("Unexpected decoder path for $query", it, format?.sampleMimeType) }
                assertTrue("Playback failed to advance: $position", position >= 20_000 && playing)
                android.util.Log.i("NadaQualityTest", "Playback [$query]: position=$position; underruns=$underruns; id=${track.id}")
                assertEquals("AudioTrack underruns for $query", 0, underruns)
            } finally { instrumentation.runOnMainSync { controls.release() } }
        }
        } finally { scenario.close() }
    }
}
