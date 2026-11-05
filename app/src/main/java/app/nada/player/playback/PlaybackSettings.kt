package app.nada.player.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One application-owned preference, also read by the playback thread without blocking audio. */
internal class PlaybackSettings(context: Context, name: String = "nada_playback") {
    private val storage = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(storage.getBoolean("normalize_volume", true))
    val normalization = mutable.asStateFlow()
    fun setNormalization(enabled: Boolean) {
        storage.edit { putBoolean("normalize_volume", enabled) }
        mutable.value = enabled
    }
}
