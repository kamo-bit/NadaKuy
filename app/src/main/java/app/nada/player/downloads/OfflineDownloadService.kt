package app.nada.player.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import app.nada.player.MainActivity
import app.nada.player.R

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class OfflineDownloadService : DownloadService(4101, 1000L, "offline-downloads", R.string.download_channel_name, 0) {
    override fun getDownloadManager(): DownloadManager = OfflineLibrary.get(this).manager
    override fun getScheduler(): Scheduler = PlatformScheduler(this, 4102)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val percent = active.map { it.percentDownloaded }.takeIf { it.isNotEmpty() && it.all { percent -> percent >= 0 } }?.average()?.toInt()
        val activity = PendingIntent.getActivity(this, 4101, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "offline-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (notMetRequirements != 0) "Menunggu koneksi internet" else "Mengunduh musik")
            .setContentText(if (active.size == 1) "1 lagu · Tersimpan di NadaKuy" else "${active.size} lagu · Tersimpan di NadaKuy")
            .setContentIntent(activity).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null).setSilent(true).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        getDownloadManager().pauseDownloads()
        stopSelf()
    }
}
