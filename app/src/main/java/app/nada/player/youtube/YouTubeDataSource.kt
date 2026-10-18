package app.nada.player.youtube

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/** Resolve immediately before loading; retry an expired/forbidden URL once with fresh evidence. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class YouTubeDataSource(private val delegate: DataSource) : DataSource {
    override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != "nada-youtube") return delegate.open(dataSpec)
        val id = dataSpec.uri.lastPathSegment.orEmpty()
        fun spec(refresh: Boolean) = dataSpec.withUri(Uri.parse(YouTubeCatalog.resolve(id, refresh).url))
        return try { delegate.open(spec(false)) }
        catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode !in listOf(403, 410)) throw error
            delegate.close()
            delegate.open(spec(true))
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun close() = delegate.close()
}
