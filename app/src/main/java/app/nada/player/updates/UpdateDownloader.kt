package app.nada.player.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class InvalidUpdate(message: String) : IOException(message)
internal class UpdateStorageFull : IOException("Ruang penyimpanan belum cukup untuk pembaruan. Kosongkan ruang, lalu coba lagi.")

/** Streaming into app-private storage; an APK becomes installable only after all checks pass. */
internal class UpdateDownloader(private val context: Context, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.MINUTES).followSslRedirects(false).build()) {

    suspend fun download(pkg: UpdatePackage, partial: File, ready: File, progress: suspend (Long) -> Unit) = withContext(Dispatchers.IO) {
        check(partial.parentFile?.mkdirs() == true || partial.parentFile?.isDirectory == true)
        val storage = context.getSystemService(StorageManager::class.java)
        if (storage.getAllocatableBytes(storage.getUuidForPath(partial.parentFile!!)) < pkg.bytes + 5 * 1024 * 1024) throw UpdateStorageFull()
        val call = client.newCall(Request.Builder().url(pkg.url).header("User-Agent", "NadaKuy-Android").build())
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unduhan pembaruan belum berhasil. Coba lagi.")
                if (!response.request.url.isHttps || response.request.url.host !in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                    throw InvalidUpdate("Alamat unduhan tidak sesuai dengan sumber resmi.")
                }
                val body = response.body ?: throw IOException("Paket pembaruan kosong.")
                if (body.contentLength() >= 0 && body.contentLength() != pkg.bytes) throw InvalidUpdate("Ukuran paket berbeda dari rilis resmi. Cek pembaruan lagi.")
                var count = 0L
                var lastProgress = 0L
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > pkg.bytes) throw InvalidUpdate("Ukuran paket melebihi informasi rilis resmi.")
                        output.write(buffer, 0, read)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 300 || count == pkg.bytes) { progress(count); lastProgress = now }
                    }
                } }
            }
            currentCoroutineContext().ensureActive()
            verify(pkg, partial)
            currentCoroutineContext().ensureActive()
            check(partial.renameTo(ready)) { "Paket pembaruan belum berhasil disimpan. Coba lagi." }
        } finally { call.cancel(); if (partial.exists()) partial.delete() }
    }

    suspend fun verify(pkg: UpdatePackage, file: File) = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() != pkg.bytes) throw InvalidUpdate("Paket pembaruan belum lengkap. Unduh kembali untuk melanjutkan.")
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it) }
        if (actual != pkg.sha256) throw InvalidUpdate("Paket tidak lolos pemeriksaan integritas. Cek pembaruan dan unduh lagi.")
        verifyArchive(pkg, file)
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(pkg: UpdatePackage, file: File) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(file.path, flags) ?: throw InvalidUpdate("Berkas yang diunduh bukan APK yang valid.")
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (archive.packageName != context.packageName || archive.versionName != pkg.version || archiveCode <= installedCode ||
            requireNotNull(AppVersion.parse(pkg.version)) <= requireNotNull(AppVersion.parse(installed.versionName.orEmpty()))) {
            throw InvalidUpdate("Paket bukan pembaruan yang lebih baru untuk NadaKuy ini.")
        }
        fun signers(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val expected = signers(installed)
        if (expected.isEmpty() || signers(archive) != expected) throw InvalidUpdate("Sertifikat paket berbeda dari NadaKuy yang terpasang.")
    }
}
