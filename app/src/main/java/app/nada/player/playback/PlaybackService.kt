package app.nada.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import app.nada.player.MainActivity
import app.nada.player.data.Track
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var sleepTimer: SleepTimer? = null
    private var listening: ListeningObserver? = null

    override fun onCreate() {
        super.onCreate()
        val playback = createMusicPlayback(this)
        val player = playback.player
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                private fun isOwnController(controller: MediaSession.ControllerInfo) =
                    controller.uid == Process.myUid() && controller.packageName == packageName

                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    if (isOwnController(controller)) { commands.add(QueueCommands.playNext); commands.add(SleepCommands.set) }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build()).build()
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (!isOwnController(controller))
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    if (customCommand == SleepCommands.set) {
                        val accepted = sleepTimer?.configure(args.getString(SleepCommands.MODE), args.getLong(SleepCommands.DURATION)) == true
                        return Futures.immediateFuture(SessionResult(if (accepted) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
                    }
                    if (customCommand != QueueCommands.playNext) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    val track = runCatching { Track.fromSaved(JSONObject(requireNotNull(args.getString(QueueCommands.TRACK)))) }
                        .getOrNull()?.takeIf { it.playableInNada }
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    if (player.mediaItemCount == 0) {
                        player.setMediaItem(track.toMediaItem()); player.prepare(); player.play()
                    } else player.insertNext(track.toMediaItem())
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        sleepTimer = SleepTimer(playback.engine) { extras -> session?.setSessionExtras(extras) }
        val history = app.nada.player.recommendations.ListeningHistory.get(this)
        listening = ListeningObserver(playback.engine) { history.record(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        listening?.close(); listening = null
        sleepTimer?.close(); sleepTimer = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
