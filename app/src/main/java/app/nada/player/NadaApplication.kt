package app.nada.player

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import app.nada.player.data.CatalogCache

class NadaApplication : Application(), SingletonImageLoader.Factory {
    internal val catalogCache by lazy { CatalogCache(cacheDir.resolve("catalog")) }
    internal val recommendationSettings by lazy { app.nada.player.recommendations.RecommendationSettings(this) }
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder().maxSizeBytes(minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)).build()
        }
        .diskCache {
            DiskCache.Builder().directory(context.cacheDir.resolve("album_artwork").toOkioPath()).maxSizeBytes(96L * 1024 * 1024).build()
        }
        .build()
}
