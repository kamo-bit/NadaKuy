package app.nada.player.youtube

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.MediaFormat

class YouTubeAudioSelectionTest {
    private fun stream(id: String, kbps: Int, type: AudioTrackType? = null,
        url: String = "https://media.example/audio", delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP) =
        AudioStream.Builder().setId(id).setContent(url, true).setAverageBitrate(kbps)
            .setAudioTrackType(type).setDeliveryMethod(delivery).build()

    @Test fun originalAudioWinsOverHigherBitrateDubAndUnknownLanguageTrack() {
        val original = stream("original", 128, AudioTrackType.ORIGINAL)
        assertSame(original, YouTubeAudioSelection.best(listOf(
            stream("dub", 256, AudioTrackType.DUBBED), stream("unknown", 160), original)))
    }

    @Test fun highestQualityWithinOriginalTrackIsRetained() {
        val high = stream("high", 160, AudioTrackType.ORIGINAL)
        assertSame(high, YouTubeAudioSelection.best(listOf(stream("low", 48, AudioTrackType.ORIGINAL), high)))
        assertEquals(160_000, YouTubeAudioSelection.bitrate(high))
    }

    @Test fun unknownTrackMetadataStillPlaysHighestBitrate() {
        val high = stream("high", 160)
        assertSame(high, YouTubeAudioSelection.best(listOf(stream("unknown", -1), stream("low", 70), high)))
    }

    @Test fun rejectsUnsupportedDeliveryAndNonHttpsBeforeSelection() {
        assertNull(YouTubeAudioSelection.best(listOf(stream("http", 320, url = "http://media.example/audio"),
            stream("dash", 320, delivery = DeliveryMethod.DASH))))
    }

    private fun aac(kbps: Int, type: AudioTrackType? = null) = AudioStream.Builder().setId("aac-$kbps")
        .setContent("https://media.example/aac", true).setMediaFormat(MediaFormat.M4A)
        .setAverageBitrate(kbps).setAudioTrackType(type).build()

    @Test fun prefersFullQualityAacWithinTheSameOriginalTrack() {
        val compatible = aac(128, AudioTrackType.ORIGINAL)
        assertSame(compatible, YouTubeAudioSelection.best(listOf(stream("opus", 160, AudioTrackType.ORIGINAL), compatible)))
        val higher = aac(256, AudioTrackType.ORIGINAL)
        assertSame(higher, YouTubeAudioSelection.best(listOf(compatible, higher)))
    }

    @Test fun codecPreferenceNeverReplacesOriginalWithDubOrLowQualityAac() {
        val original = stream("opus", 160, AudioTrackType.ORIGINAL)
        assertSame(original, YouTubeAudioSelection.best(listOf(original, aac(256, AudioTrackType.DUBBED))))
        assertSame(original, YouTubeAudioSelection.best(listOf(original, aac(48, AudioTrackType.ORIGINAL))))
    }
}
