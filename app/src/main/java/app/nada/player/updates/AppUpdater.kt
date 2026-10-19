package app.nada.player.updates

import android.content.Context
import android.app.Application
import android.content.Intent
import android.util.AtomicFile
import app.nada.player.data.readBounded
import androidx.core.content.FileProvider
import androidx.work.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class UpdatePhase { IDLE, WAITING, DOWNLOADING, VERIFYING, READY, CANCELLED, FAILED }
data class UpdateDownloadState(val phase: UpdatePhase = UpdatePhase.IDLE, val pkg: UpdatePackage? = null,
    val bytes: Long = 0, val error: String? = null) {
    val busy: Boolean get() = phase in setOf(UpdatePhase.WAITING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
}

internal fun updatePhase(work: WorkInfo.State?, ready: Boolean, verifying: Boolean): UpdatePhase = when {
    ready && (work == null || work == WorkInfo.State.SUCCEEDED || work == WorkInfo.State.CANCELLED) -> UpdatePhase.READY
    work == WorkInfo.State.ENQUEUED || work == WorkInfo.State.BLOCKED -> UpdatePhase.WAITING
    work == WorkInfo.State.RUNNING -> if (verifying) UpdatePhase.VERIFYING else UpdatePhase.DOWNLOADING
    work == WorkInfo.State.CANCELLED -> UpdatePhase.CANCELLED
    else -> UpdatePhase.FAILED
}

/** WorkManager owns transfer lifetime; this boundary owns the selected job and its private files. */
class AppUpdater private constructor(private val context: Application) {
    private data class Session(val id: UUID, val pkg: UpdatePackage, val error: String? = null)
    private val manager = WorkManager.getInstance(context)
    private val lock = Mutex()
    private val directory = File(context.filesDir, "updates").apply { mkdirs() }
    private val record = AtomicFile(File(directory, "session.json"))
    private val current = MutableStateFlow(runCatching {
        val json = JsonParser.parseString(record.openRead().use { it.readBounded(2048).toString(Charsets.UTF_8) }).asJsonObject
        Session(UUID.fromString(json["id"].asString), parsePackage(json["package"].toString()), json.get("error")?.takeUnless { it.isJsonNull }?.asString?.take(500))
    }.getOrNull())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<UpdateDownloadState> = current.flatMapLatest { session ->
        if (session == null) flowOf(UpdateDownloadState()) else manager.getWorkInfoByIdFlow(session.id).map { work ->
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            if (AppVersion.parse(session.pkg.version)!! <= (AppVersion.parse(installed) ?: AppVersion(0, 0, 0))) return@map UpdateDownloadState()
            if (session.error != null) return@map UpdateDownloadState(UpdatePhase.FAILED, session.pkg, error = session.error)
            val phase = updatePhase(work?.state, packageFile(context, session.id, true).isFile, work?.progress?.getBoolean("verifying", false) == true)
            UpdateDownloadState(phase, session.pkg, if (phase == UpdatePhase.READY) session.pkg.bytes else work?.progress?.getLong("bytes", 0) ?: 0,
                if (phase == UpdatePhase.FAILED) work?.outputData?.getString("error") ?: "Paket pembaruan belum lengkap. Unduh kembali." else null)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun download(pkg: UpdatePackage, unmeteredOnly: Boolean) = withContext(Dispatchers.IO) { lock.withLock {
        val old = current.value
        if (old != null) {
            val work = manager.getWorkInfoById(old.id).get(10, TimeUnit.SECONDS)
            if (work != null && !work.state.isFinished) return@withLock
            if (old.pkg == pkg && old.error == null && work?.state != WorkInfo.State.FAILED && packageFile(context, old.id, true).isFile) return@withLock
            // Keep the selected session until its files have actually been removed.
            for (complete in listOf(false, true)) {
                val file = packageFile(context, old.id, complete)
                check(!file.exists() || file.delete()) { "Paket lama belum bisa dibersihkan. Coba lagi." }
            }
        }
        val request = OneTimeWorkRequestBuilder<AppUpdateWorker>()
            .setInputData(workDataOf("package" to packageJson(pkg).toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        val session = Session(request.id, pkg)
        persist(session)
        current.value = session
        manager.enqueueUniqueWork("nadakuy-app-update", ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
    } }

    private fun persist(session: Session) {
        val json = JsonObject().apply { addProperty("id", session.id.toString()); add("package", packageJson(session.pkg)); addProperty("error", session.error) }
        val stream = record.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); record.finishWrite(stream) }
        catch (error: Exception) { record.failWrite(stream); throw error }
    }

    fun cancel() { current.value?.let { manager.cancelWorkById(it.id) } }

    suspend fun installIntent(): Intent = withContext(Dispatchers.IO) {
        val session = requireNotNull(current.value) { "Unduh pembaruan terlebih dahulu." }
        val work = manager.getWorkInfoById(session.id).get(10, TimeUnit.SECONDS)
        check(work == null || work.state.isFinished) { "Paket pembaruan belum siap." }
        val file = packageFile(context, session.id, true)
        try { UpdateDownloader(context).verify(session.pkg, file) }
        catch (error: InvalidUpdate) {
            val invalid = session.copy(error = error.message)
            persist(invalid); current.value = invalid
            throw error
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        @Volatile private var instance: AppUpdater? = null
        fun get(context: Context): AppUpdater = instance ?: synchronized(this) {
            instance ?: AppUpdater(context.applicationContext as Application).also { instance = it }
        }
        internal fun packageFile(context: Context, id: UUID, complete: Boolean) =
            File(File(context.filesDir, "updates"), "$id.${if (complete) "apk" else "part"}")
        internal fun packageJson(pkg: UpdatePackage) = JsonObject().apply {
            addProperty("tag", pkg.tag); addProperty("bytes", pkg.bytes); addProperty("sha256", pkg.sha256)
        }
        internal fun parsePackage(value: String): UpdatePackage {
            require(value.length <= 1024)
            val json = JsonParser.parseString(value).asJsonObject
            return UpdatePackage(json["tag"].asString, requireNotNull(json["bytes"].asString.toLongOrNull()), json["sha256"].asString)
        }
    }
}
