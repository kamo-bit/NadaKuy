package app.nada.player.lyrics

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.data.Track
import app.nada.player.downloads.OfflineLibrary
import app.nada.player.playback.toMediaItem
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in local acoustic audit using the same media identity, cache and resolver as playback. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LyricsAcousticExportTest {
    @Test fun retainExactPlaybackRecordingsForLocalAlignment() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("acousticExport") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val records = File(context.noBackupFilesDir, "lyrics-audit-catalog.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
        assertEquals(6, records.size())
        val directory = File(context.noBackupFilesDir, "lyrics-acoustic").apply { mkdirs() }
        val factory = OfflineLibrary.get(context).playbackFactory()
        val provenance = mutableListOf<Map<String, Any>>()
        for (record in records) {
            val json = record.asJsonObject
            val key = json["key"].asString
            require(key in lyricsSamples.map { it.key })
            val track = Gson().fromJson(json["track"], Track::class.java)
            val file = File(directory, "$key.m4a")
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val uri = track.toMediaItem().localConfiguration!!.uri
            val probe = factory.createDataSource()
            val expected = try { probe.open(DataSpec.Builder().setUri(uri).setKey(track.id).build()) } finally { probe.close() }
            check(expected in 1..40L * 1024 * 1024) { "Unknown or oversized recording length: $key/$expected" }
            file.outputStream().use { output ->
                while (count < expected) {
                    val source = factory.createDataSource()
                    val length = minOf(512L * 1024, expected - count)
                    var received = 0L
                    try {
                        source.open(DataSpec.Builder().setUri(uri).setKey(track.id).setPosition(count).setLength(length).build())
                        val buffer = ByteArray(64 * 1024)
                        while (received < length) {
                            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), length - received).toInt())
                            check(read > 0) { "Truncated recording: $key" }
                            received += read
                            count += read
                            check(count <= 40 * 1024 * 1024) { "Acoustic sample exceeded bound" }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                    } finally { source.close() }
                }
            }
            assertEquals(expected, count)
            assertTrue("Empty recording: $key", count > 0)
            provenance += mapOf("key" to key, "id" to track.id, "bytes" to count,
                "sha256" to digest.digest().joinToString("") { "%02x".format(it) })
            android.util.Log.i("NadaAcoustic", "$key: ${track.id}; retainedBytes=$count")
        }
        File(directory, "provenance.json").writeText(Gson().toJson(provenance))
    }
}
