package app.nada.player.updates

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.nada.player.ui.AppUpdateSection
import app.nada.player.ui.NadaBackground
import app.nada.player.ui.NadaTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppUpdateUiTest {
    @get:Rule val rule = createComposeRule()
    private val pkg = UpdatePackage("v1.7.0", 40L * 1024 * 1024, "a".repeat(64))
    private val update = UpdateState(release = AppRelease("1.7.0", "Pembaruan aplikasi dapat diunduh langsung di NadaKuy.", "https://github.com/kamo-bit/NadaKuy/releases/tag/v1.7.0", pkg), newer = true)
    private fun show(state: UpdateDownloadState, error: String? = null, start: () -> Unit = {}, cancel: () -> Unit = {}, install: () -> Unit = {}) {
        rule.setContent { NadaTheme {
            Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().background(NadaBackground).safeDrawingPadding(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AppUpdateSection("1.6.2", update, state, false, error, {}, { start() }, cancel, install, {})
                }
            }
            }
        } }
    }

    @Test fun progressHasOneCancelActionAndCannotLaunchInstaller() {
        var cancelled = false
        show(UpdateDownloadState(UpdatePhase.DOWNLOADING, pkg, pkg.bytes / 2), cancel = { cancelled = true })
        rule.onNodeWithText("50%", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cek pembaruan").assertIsNotEnabled()
        rule.onNodeWithText("Pasang pembaruan").assertDoesNotExist()
        rule.onNodeWithText("Batalkan unduhan").performClick()
        assertTrue(cancelled)
    }

    @Test fun cancelledInstallationCanReuseTheReadyPackage() {
        var installs = 0; var downloads = 0
        show(UpdateDownloadState(UpdatePhase.READY, pkg, pkg.bytes), "Pemasangan belum selesai. Paket tetap tersimpan.", start = { downloads++ }, install = { installs++ })
        rule.onNodeWithText("Pembaruan siap dipasang").assertIsDisplayed()
        rule.onNodeWithText("Pasang pembaruan").performClick()
        assertEquals(1, installs); assertEquals(0, downloads)
        rule.onNodeWithText("Unduh pembaruan").assertDoesNotExist()
    }

    @Test fun integrityFailureProvidesAnExplicitRetry() {
        var retried = false
        show(UpdateDownloadState(UpdatePhase.FAILED, pkg, error = "Paket tidak lolos pemeriksaan integritas."), start = { retried = true })
        rule.onNodeWithText("Paket tidak lolos pemeriksaan integritas.").assertIsDisplayed()
        rule.onNodeWithText("Coba unduh lagi").performClick()
        assertTrue(retried)
        rule.onNodeWithText("Pasang pembaruan").assertDoesNotExist()
    }

    /** Native render evidence with explicitly synthetic transfer state; does not download a file. */
    @Test fun captureUpdateStatesForVisualReview() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("updateCapture")
        assumeTrue(phase != null)
        val state = when (phase) {
            "progress" -> UpdateDownloadState(UpdatePhase.DOWNLOADING, pkg, pkg.bytes * 63 / 100)
            "error" -> UpdateDownloadState(UpdatePhase.FAILED, pkg, error = "Koneksi terputus. Periksa internet, lalu coba lagi.")
            "waiting" -> UpdateDownloadState(UpdatePhase.WAITING, pkg)
            else -> UpdateDownloadState(UpdatePhase.READY, pkg, pkg.bytes)
        }
        show(state)
        rule.waitForIdle()
        rule.onNodeWithText("Pembaruan aplikasi").assertIsDisplayed()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "update-capture.png")
        rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
