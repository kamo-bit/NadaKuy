package app.nada.player.youtube

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.MediaFormat
import java.net.URI

internal object YouTubeAudioSelection {
    fun best(streams: List<AudioStream>): AudioStream? {
        val supported = streams.asSequence()
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        .filter { runCatching { URI(it.content).scheme == "https" }.getOrDefault(false) }
        .toList()
        // A high-bitrate dub/descriptive track must not replace the original performance.
        val priority = supported.maxOfOrNull { trackPriority(it) } ?: return null
        val candidates = supported.filter { trackPriority(it) == priority }
        // AAC-LC has a separate platform decoder path from Opus. Prefer a full-quality AAC
        // rendition for compatibility; do not fall back to YouTube's 48/64kbps AAC just for codec.
        return candidates.filter {
            it.format == MediaFormat.M4A && (it.codec == null || it.codec.startsWith("mp4a.40.2")) &&
                (it.averageBitrate >= 128 || bitrate(it) >= 128_000)
        }.maxByOrNull { bitrate(it) } ?: candidates.maxByOrNull { bitrate(it) }
    }

    private fun trackPriority(stream: AudioStream) = when (stream.audioTrackType) {
        AudioTrackType.ORIGINAL -> 2
        null -> 1
        else -> 0
    }

    fun bitrate(stream: AudioStream): Int = stream.bitrate.takeIf { it > 0 }
        ?: stream.averageBitrate.coerceAtLeast(0).times(1000)
}
