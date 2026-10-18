package app.nada.player.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Read at most limit + 1 bytes, retaining the extra byte so callers detect oversize input. */
internal fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
