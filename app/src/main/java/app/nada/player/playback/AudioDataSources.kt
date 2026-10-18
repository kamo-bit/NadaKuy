package app.nada.player.playback

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import app.nada.player.youtube.YouTubeDataSource

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun audioUpstream(context: Context): DataSource.Factory {
    val factory = DefaultDataSource.Factory(context.applicationContext, DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000).setReadTimeoutMs(25_000)
        .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"))
    return DataSource.Factory { YouTubeDataSource(factory.createDataSource()) }
}
