package app.nada.player.spotify

import android.app.Activity
import android.os.SystemClock
import androidx.media3.common.Player
import app.nada.player.data.Track
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState

data class SpotifyPlayback(val track: Track?, val playing: Boolean, val position: Long, val duration: Long, val shuffle: Boolean, val repeat: Int,
    val canSeek: Boolean, val canNext: Boolean, val canPrevious: Boolean, val canShuffle: Boolean, val canRepeat: Boolean)

/** Owns one bound App Remote connection; late connects are explicitly disconnected. */
class SpotifyRemote(private val changed: (SpotifyPlayback) -> Unit, private val status: (Boolean, String?) -> Unit) {
    private var remote: SpotifyAppRemote? = null
    private var subscription: Subscription<PlayerState>? = null
    private var generation = 0
    private var connecting = false
    private var lastState: PlayerState? = null
    private var observedAt = 0L
    val connected: Boolean get() = remote?.isConnected == true

    fun connect(activity: Activity, clientId: String, prompt: Boolean) {
        if (connected || connecting) return
        connecting = true
        val attempt = ++generation
        val params = ConnectionParams.Builder(clientId).setRedirectUri(SpotifyConfig.REDIRECT_URI).showAuthView(prompt).build()
        SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                if (attempt != generation) { SpotifyAppRemote.disconnect(appRemote); return }
                connecting = false; remote = appRemote
                status(true, null)
                subscription = appRemote.playerApi.subscribeToPlayerState().setEventCallback { player ->
                    if (attempt != generation) return@setEventCallback
                    lastState = player; observedAt = SystemClock.elapsedRealtime(); tick()
                }
                subscription?.setErrorCallback { if (attempt == generation) { disconnect(); status(false, "Koneksi pemutar Spotify terputus. Hubungkan kembali.") } }
            }
            override fun onFailure(throwable: Throwable) {
                if (attempt != generation) return
                connecting = false
                status(false, if (prompt) "Pemutar belum terhubung. Pastikan Spotify sudah login dan package serta SHA-1 NadaKuy terdaftar di Dashboard." else null)
            }
        })
    }
    fun disconnect() {
        generation++; connecting = false
        subscription?.cancel(); subscription = null
        remote?.let(SpotifyAppRemote::disconnect); remote = null; lastState = null
    }
    fun tick() {
        val player = lastState ?: return
        val item = player.track
        val image = item?.imageUri?.raw.orEmpty().removePrefix("spotify:image:")
        val track = item?.takeIf { it.uri.startsWith("spotify:track:") }?.let {
            Track(it.uri, it.name, it.artists?.joinToString(", ") { artist -> artist.name } ?: it.artist.name,
                if (image.matches(Regex("[a-fA-F0-9]+"))) "https://i.scdn.co/image/$image" else "", (it.duration / 1000).toInt(), "",
                "https://open.spotify.com/track/${it.uri.substringAfterLast(':')}", "spotify")
        }
        val elapsed = if (player.isPaused) 0L else ((SystemClock.elapsedRealtime() - observedAt) * player.playbackSpeed).toLong()
        changed(SpotifyPlayback(track, !player.isPaused, (player.playbackPosition + elapsed).coerceIn(0, item?.duration ?: Long.MAX_VALUE), item?.duration ?: 0,
            player.playbackOptions.isShuffling, when (player.playbackOptions.repeatMode) { 1 -> Player.REPEAT_MODE_ONE; 2 -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF },
            player.playbackRestrictions.canSeek, player.playbackRestrictions.canSkipNext, player.playbackRestrictions.canSkipPrev,
            player.playbackRestrictions.canToggleShuffle, player.playbackRestrictions.canRepeatContext || player.playbackRestrictions.canRepeatTrack))
    }
    private fun <T> command(action: (SpotifyAppRemote) -> CallResult<T>) {
        val current = remote?.takeIf { it.isConnected } ?: run { status(false, "Hubungkan pemutar Spotify terlebih dahulu."); return }
        val attempt = generation
        action(current).setErrorCallback { if (attempt == generation) status(connected, "Perintah belum dapat dijalankan oleh Spotify. Coba lagi.") }
    }
    fun play(track: Track) { command { it.playerApi.play(track.id) } }
    fun queue(track: Track) { command { it.playerApi.queue(track.id) } }
    fun toggle() { command { if (lastState?.isPaused != false) it.playerApi.resume() else it.playerApi.pause() } }
    fun pause() { command { it.playerApi.pause() } }
    fun next() { command { it.playerApi.skipNext() } }
    fun previous() { command { it.playerApi.skipPrevious() } }
    fun seek(position: Long) { command { it.playerApi.seekTo(position.coerceAtLeast(0)) } }
    fun shuffle() { command { it.playerApi.toggleShuffle() } }
    fun repeat() { command { it.playerApi.toggleRepeat() } }
    fun libraryState(track: Track, changed: (Boolean) -> Unit) {
        val connection = remote ?: return
        val attempt = generation
        connection.userApi.getLibraryState(track.id).setResultCallback { if (attempt == generation) changed(it.isAdded) }
    }
    fun toggleLike(track: Track, changed: (Boolean) -> Unit) {
        val connection = remote?.takeIf { it.isConnected } ?: run { status(false, "Hubungkan pemutar Spotify terlebih dahulu."); return }
        val attempt = generation
        connection.userApi.getLibraryState(track.id).setResultCallback { library ->
            if (attempt == generation) {
                val result = if (library.isAdded) connection.userApi.removeFromLibrary(track.id) else connection.userApi.addToLibrary(track.id)
                result.setResultCallback { if (attempt == generation) changed(!library.isAdded) }
                    .setErrorCallback { if (attempt == generation) status(connected, "Lagu belum dapat disimpan di Spotify. Coba lagi.") }
            }
        }.setErrorCallback { if (attempt == generation) status(connected, "Status lagu disukai belum dapat dibaca dari Spotify.") }
    }
}
