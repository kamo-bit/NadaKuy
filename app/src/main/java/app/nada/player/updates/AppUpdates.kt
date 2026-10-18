package app.nada.player.updates

import com.google.gson.JsonParser
import app.nada.player.data.readBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(value) ?: return null
            val numbers = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return AppVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

data class AppRelease(val version: String, val notes: String, val pageUrl: String, val apkUrl: String?)
data class UpdateState(val checking: Boolean = false, val release: AppRelease? = null,
    val newer: Boolean = false, val checkedAt: String? = null, val error: String? = null)

object AppUpdates {
    const val RELEASES_URL = "https://github.com/kamo-bit/NadaKuy/releases/latest"
    private const val API_URL = "https://api.github.com/repos/kamo-bit/NadaKuy/releases/latest"

    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val connection = URI(API_URL).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            connection.setRequestProperty("User-Agent", "NadaKuy-Android")
            when (connection.responseCode) {
                200 -> Unit
                403, 429 -> error("Batas pemeriksaan GitHub tercapai. Coba lagi nanti atau buka halaman rilis.")
                404 -> error("Rilis terbaru belum tersedia. Coba lagi nanti.")
                else -> error("Pembaruan belum bisa diperiksa. Coba lagi atau buka halaman rilis.")
            }
            val bytes = connection.inputStream.use { it.readBounded(1024 * 1024) }
            check(bytes.size <= 1024 * 1024) { "Informasi rilis terlalu besar. Buka halaman rilis untuk melanjutkan." }
            parseRelease(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    fun parseRelease(json: String): AppRelease {
        val root = JsonParser.parseString(json).asJsonObject
        require(!root.get("draft").asBoolean && !root.get("prerelease").asBoolean) { "Rilis stabil belum tersedia." }
        val tag = root.get("tag_name").asString
        require(AppVersion.parse(tag) != null) { "Versi rilis belum dikenali. Buka halaman rilis." }
        val page = root.get("html_url").asString
        require(page == "https://github.com/kamo-bit/NadaKuy/releases/tag/$tag") { "Tautan rilis tidak valid." }
        val apk = root.getAsJsonArray("assets").firstOrNull { value ->
            val asset = value.asJsonObject
            asset.get("name")?.asString == "NadaKuy-${tag.removePrefix("v")}.apk" &&
                asset.get("state")?.asString == "uploaded" &&
                asset.get("size")?.asLong?.let { it > 0 } == true &&
                asset.get("browser_download_url")?.asString == "https://github.com/kamo-bit/NadaKuy/releases/download/$tag/NadaKuy-${tag.removePrefix("v")}.apk"
        }?.asJsonObject?.get("browser_download_url")?.asString
        return AppRelease(tag.removePrefix("v"), root.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(6000), page, apk)
    }

    fun result(release: AppRelease, installed: String): UpdateState {
        val current = requireNotNull(AppVersion.parse(installed)) { "Versi aplikasi belum dikenali." }
        return UpdateState(release = release, newer = requireNotNull(AppVersion.parse(release.version)) > current, checkedAt = Instant.now().toString())
    }
}
