package app.nada.player.updates

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(version: String = "1.5.0", assetUrl: String = "https://github.com/kamo-bit/NadaKuy/releases/download/v$version/NadaKuy-$version.apk") = """
        {"tag_name":"v$version","html_url":"https://github.com/kamo-bit/NadaKuy/releases/tag/v$version",
        "draft":false,"prerelease":false,"body":"Perbaikan dan fitur baru","assets":[
        {"name":"NadaKuy-$version.apk","state":"uploaded","size":1024,"browser_download_url":"$assetUrl"}]}
    """.trimIndent()

    @Test fun comparesVersionsNumericallyAndNeverOffersADowngrade() {
        val newer = AppUpdates.parseRelease(release("1.10.0"))
        assertTrue(AppUpdates.result(newer, "1.9.0").newer)
        assertFalse(AppUpdates.result(newer, "2.0.0").newer)
        assertFalse(AppUpdates.result(newer, "1.10.0").newer)
        listOf("1.5.0-beta", "v1.5", "01.5.0", "1.5.0.1", "9999999999999.0.0").forEach { assertNull(AppVersion.parse(it)) }
    }

    @Test fun onlyOffersTheNamedApkFromTheOfficialRelease() {
        val actual = AppUpdates.parseRelease(release())
        assertEquals("1.5.0", actual.version)
        assertNotNull(actual.apkUrl)
        val spoofed = AppUpdates.parseRelease(release(assetUrl = "https://example.com/fake.apk"))
        assertNull(spoofed.apkUrl)
        assertEquals("https://github.com/kamo-bit/NadaKuy/releases/tag/v1.5.0", spoofed.pageUrl)
    }

    @Test fun rejectsDraftPrereleaseAndForeignReleasePages() {
        listOf(release().replace("\"draft\":false", "\"draft\":true"),
            release().replace("\"prerelease\":false", "\"prerelease\":true"),
            release().replace("/releases/tag/", "/different/tag/"), release("1.5.0-beta")
        ).forEach { assertThrows(IllegalArgumentException::class.java) { AppUpdates.parseRelease(it) } }
    }

    @Test fun aReleaseWithoutAnApkStillHasAUsableReleasePage() {
        val result = AppUpdates.parseRelease(release().replace("\"state\":\"uploaded\"", "\"state\":\"new\""))
        assertNull(result.apkUrl)
        assertTrue(AppUpdates.result(result, "1.4.0").newer)
    }
}
