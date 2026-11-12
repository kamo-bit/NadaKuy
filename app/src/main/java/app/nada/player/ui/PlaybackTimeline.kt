@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nada.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nada.player.PlaybackProgress

@Composable
internal fun PlaybackTimeline(trackId: String, progress: () -> PlaybackProgress, canSeek: Boolean, seek: (Long) -> Unit) {
    val current = progress()
    var seekPreview by remember(trackId) { mutableStateOf<Float?>(null) }
    val duration = current.duration.toFloat().coerceAtLeast(1f)
    Slider(value = seekPreview ?: current.position.toFloat().coerceIn(0f, duration), onValueChange = { seekPreview = it },
        enabled = current.duration > 0 && canSeek,
        onValueChangeFinished = { seekPreview?.let { seek(it.toLong()) }; seekPreview = null }, valueRange = 0f..duration,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Posisi pemutaran" },
        thumb = { Box(Modifier.size(10.dp).background(Color.White, CircleShape)) },
        track = { sliderState -> SliderDefaults.Track(sliderState, Modifier.height(3.dp), thumbTrackGapSize = 0.dp,
            trackInsideCornerSize = 1.5.dp, drawStopIndicator = {},
            colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f))) })
    Row(Modifier.fillMaxWidth()) {
        Text(timeText((seekPreview?.toLong() ?: current.position) / 1000), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(timeText(current.duration / 1000), color = NadaMuted, style = MaterialTheme.typography.bodyMedium)
    }
}
