package app.nada.player.updates

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import okhttp3.*
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpdateDownloadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun fixture(name: String): File {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAppUpdateFixtures") == "true")
        return File(context.getExternalFilesDir(null), name).also { check(it.isFile) { "Missing local APK fixture: $name" } }
    }
    private fun client(file: File, slow: Boolean = false): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val input = file.inputStream()
        val source = (if (slow) object : InputStream() {
            override fun read(): Int = input.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { Thread.sleep(30); return input.read(bytes, offset, minOf(length, 8192)) }
            override fun close() = input.close()
        } else input).source().buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = file.length()
            override fun source() = source
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Local fixture").body(body).build()
    }.build()

    @Test fun validFuturePackageRetainsExactBytesAndPassesRealPackageManagerChecks() = runBlocking {
        val fixture = fixture("update-final.apk")
        val pkg = UpdatePackage("v1.7.0", fixture.length(), hash(fixture))
        val id = UUID.randomUUID(); val partial = AppUpdater.packageFile(context, id, false); val ready = AppUpdater.packageFile(context, id, true)
        var received = 0L
        try {
            UpdateDownloader(context, client(fixture)).download(pkg, partial, ready) { received = it }
            assertFalse(partial.exists()); assertEquals(pkg.bytes, received)
            assertEquals(pkg.sha256, hash(ready))
            UpdateDownloader(context).verify(pkg, ready)
        } finally { partial.delete(); ready.delete() }
    }

    @Test fun corruptAndWrongSignerPackagesNeverBecomeReady() = runBlocking {
        val good = fixture("update-final.apk")
        val wrong = fixture("update-wrong-signer.apk")
        for ((file, digest) in listOf(good to "0".repeat(64), wrong to hash(wrong))) {
            val id = UUID.randomUUID(); val partial = AppUpdater.packageFile(context, id, false); val ready = AppUpdater.packageFile(context, id, true)
            try {
                var rejected = false
                try { UpdateDownloader(context, client(file)).download(UpdatePackage("v1.7.0", file.length(), digest), partial, ready) {} }
                catch (error: InvalidUpdate) { rejected = true }
                assertTrue("Bad package rejected", rejected); assertFalse(partial.exists()); assertFalse(ready.exists())
            } finally { partial.delete(); ready.delete() }
        }
    }

    @Test fun sameVersionAndMismatchedVersionCannotReplaceTheInstalledApp() = runBlocking {
        val file = fixture("update-final.apk")
        val current = File(context.applicationInfo.sourceDir)
        for ((apk, version) in listOf(current to context.packageManager.getPackageInfo(context.packageName, 0).versionName!!, file to "9.9.9")) {
            var rejected = false
            try { UpdateDownloader(context).verify(UpdatePackage("v$version", apk.length(), hash(apk)), apk) }
            catch (error: InvalidUpdate) { rejected = true }
            assertTrue("Non-update rejected", rejected)
        }
    }

    @Test fun cancellationRemovesPartialBytesAndDoesNotPublishAnInstallableFile() = runBlocking {
        val file = fixture("update-final.apk")
        val id = UUID.randomUUID(); val partial = AppUpdater.packageFile(context, id, false); val ready = AppUpdater.packageFile(context, id, true)
        try {
            val task = launch { UpdateDownloader(context, client(file, true)).download(UpdatePackage("v1.7.0", file.length(), hash(file)), partial, ready) {} }
            withTimeout(5_000) { while (!partial.exists() || partial.length() == 0L) delay(10) }
            task.cancelAndJoin()
            assertTrue(task.isCancelled); assertFalse(partial.exists()); assertFalse(ready.exists())
        } finally { partial.delete(); ready.delete() }
    }
}
