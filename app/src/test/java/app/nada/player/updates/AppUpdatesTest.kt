package app.nada.player.updates

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(version: String = "1.5.0", assetUrl: String = "https://github.com/kamo-bit/NadaKuy/releases/download/v$version/NadaKuy-$version.apk") = """
        {"tag_name":"v$version","html_url":"https://github.com/kamo-bit/NadaKuy/releases/tag/v$version",
        "draft":false,"prerelease":false,"body":"Perbaikan dan fitur baru","assets":[
        {"name":"NadaKuy-$version.apk","state":"uploaded","size":1024,"digest":"sha256:${"a".repeat(64)}","browser_download_url":"$assetUrl"}]}
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
        assertNotNull(actual.apk)
        val spoofed = AppUpdates.parseRelease(release(assetUrl = "https://example.com/fake.apk"))
        assertNull(spoofed.apk)
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
        assertNull(result.apk)
        assertTrue(AppUpdates.result(result, "1.4.0").newer)
    }

    @Test fun refusesMissingOrInvalidIntegrityMetadataAndUnboundedPackageSizes() {
        listOf(
            release().replace("sha256:", "sha512:"),
            release().replace("a".repeat(64), "not-a-checksum"),
            release().replace("\"size\":1024", "\"size\":0"),
            release().replace("\"size\":1024", "\"size\":209715201"),
            release().replace("\"size\":1024", "\"size\":1024.5")
        ).forEach { assertNull(AppUpdates.parseRelease(it).apk) }
        val pkg = AppUpdates.parseRelease(release()).apk!!
        assertEquals(1024L, pkg.bytes)
        assertEquals("a".repeat(64), pkg.sha256)
        assertTrue(pkg.url.startsWith("https://github.com/kamo-bit/NadaKuy/releases/download/v1.5.0/"))
    }

    @Test fun ambiguousAssetsAndLookalikeUrlsCannotBecomeInstallablePackages() {
        listOf("https://github.com.example.com/kamo-bit/NadaKuy/releases/download/v1.5.0/NadaKuy-1.5.0.apk",
            "http://github.com/kamo-bit/NadaKuy/releases/download/v1.5.0/NadaKuy-1.5.0.apk",
            "https://github.com/kamo-bit/NadaKuy/releases/download/v1.5.0/NadaKuy-1.5.0.apk?redirect=elsewhere"
        ).forEach { assertNull(AppUpdates.parseRelease(release(assetUrl = it)).apk) }
        val json = com.google.gson.JsonParser.parseString(release()).asJsonObject
        json.getAsJsonArray("assets").add(json.getAsJsonArray("assets")[0].deepCopy())
        assertNull(AppUpdates.parseRelease(json.toString()).apk)
        assertThrows(IllegalArgumentException::class.java) { UpdatePackage("v1.5.0/other", 1024, "a".repeat(64)) }
    }
}
