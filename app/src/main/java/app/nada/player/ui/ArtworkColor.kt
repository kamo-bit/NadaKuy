package app.nada.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import app.nada.player.data.Track
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberArtworkColor(track: Track?): State<Color> {
    val context = LocalContext.current
    return produceState(Color(0xFF283A32), track?.artwork) {
        value = Color(0xFF283A32)
        if (track?.artwork.isNullOrBlank()) return@produceState
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(track?.artwork).size(96).allowHardware(false).build())
        if (result is SuccessResult) {
            value = withContext(Dispatchers.Default) {
                val palette = Palette.from(result.image.toBitmap()).maximumColorCount(12).generate()
                var rgb = palette.getVibrantColor(palette.getDominantColor(0xFF385447.toInt()))
                repeat(12) {
                    if (ColorUtils.calculateContrast(android.graphics.Color.WHITE, rgb) < 7.0)
                        rgb = ColorUtils.blendARGB(rgb, android.graphics.Color.BLACK, .12f)
                }
                Color(rgb)
            }
        }
    }
}
