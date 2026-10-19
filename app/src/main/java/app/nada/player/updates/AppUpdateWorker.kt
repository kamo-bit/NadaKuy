package app.nada.player.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.nada.player.MainActivity
import kotlinx.coroutines.CancellationException
import java.io.IOException

class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pkg = runCatching { AppUpdater.parsePackage(requireNotNull(inputData.getString("package"))) }.getOrNull()
            ?: return Result.failure(workDataOf("error" to "Informasi pembaruan tidak valid. Cek pembaruan lagi."))
        val partial = AppUpdater.packageFile(applicationContext, id, false)
        val ready = AppUpdater.packageFile(applicationContext, id, true)
        return try {
            // A process may end after the validated rename but before WorkManager records success.
            if (ready.isFile) { UpdateDownloader(applicationContext).verify(pkg, ready); return Result.success() }
            setForeground(notification(pkg, 0))
            var notifiedAt = 0L
            UpdateDownloader(applicationContext).download(pkg, partial, ready) { bytes ->
                setProgress(workDataOf("bytes" to bytes))
                if (bytes == pkg.bytes) setProgress(workDataOf("bytes" to bytes, "verifying" to true))
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - notifiedAt >= 1000 || bytes == pkg.bytes) { setForeground(notification(pkg, bytes)); notifiedAt = now }
            }
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: InvalidUpdate) { Result.failure(workDataOf("error" to error.message)) }
        catch (error: UpdateStorageFull) { Result.failure(workDataOf("error" to error.message)) }
        catch (error: IOException) {
            if (runAttemptCount < 2) Result.retry() else Result.failure(workDataOf("error" to "Unduhan terhenti. Periksa koneksi dan ruang kosong, lalu coba lagi."))
        } catch (error: Exception) { Result.failure(workDataOf("error" to "Pembaruan belum bisa diunduh. Coba lagi.")) }
    }

    private fun notification(pkg: UpdatePackage, bytes: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app-updates", "Pembaruan NadaKuy", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(applicationContext, 4201, MainActivity.updatesIntent(applicationContext), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, "app-updates")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Mengunduh NadaKuy ${pkg.version}")
            .setContentText("Progres tersedia di Pengaturan · Pembaruan aplikasi")
            .setContentIntent(intent).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Batalkan", androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .setProgress(100, (bytes * 100 / pkg.bytes).toInt(), bytes == 0L).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(4201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(4201, notification)
    }
}
