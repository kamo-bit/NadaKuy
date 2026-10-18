package app.nada.player.spotify

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SpotifyConfig {
    const val REDIRECT_URI = "http://127.0.0.1:43821/callback"
    const val PACKAGE = "com.spotify.music"
    const val SCOPES = "user-read-playback-state"
    fun validClientId(value: String) = value.matches(Regex("[a-fA-F0-9]{32}"))
    fun randomToken(): String = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun challenge(verifier: String): String = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/** Tokens never enter ordinary preferences, Android backup, UI messages or logs. */
internal class SpotifyTokenStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "spotify-session.enc")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nada.spotify.session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nada.spotify.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject? = try {
        if (!file.exists()) null else {
            require(file.length() in 29..32_768)
            val bytes = file.readBytes()
            require(bytes.size in 29..32_768)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        }
    } catch (_: Exception) { clear(); null }
    fun write(json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val pending = File(file.parentFile, "spotify-session.pending")
        pending.outputStream().use { it.write(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))) }
        check(pending.renameTo(file)) { "Sesi Spotify belum dapat disimpan." }
    }
    fun clear() { file.delete(); File(file.parentFile, "spotify-session.pending").delete() }
}

class SpotifyAuth(context: Context) {
    private val prefs = context.getSharedPreferences("spotify_connection", Context.MODE_PRIVATE)
    private val store = SpotifyTokenStore(context)
    private val mutex = Mutex()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var generation = 0L
    var clientId: String
        get() = prefs.getString("client_id", "").orEmpty()
        private set(value) { prefs.edit().putString("client_id", value).apply() }
    val authorized: Boolean get() = store.read()?.optString("client_id") == clientId && clientId.isNotEmpty()

    fun configure(value: String) {
        require(SpotifyConfig.validClientId(value.trim())) { "Client ID harus berisi 32 karakter dari Spotify Dashboard." }
        if (clientId != value.trim()) { cancel(); store.clear(); clientId = value.trim() }
    }
    fun cancel() { generation++; server?.close(); server = null }
    fun disconnect() { cancel(); store.clear() }

    suspend fun authorize(activity: Activity) {
        cancel()
        val attempt = generation
        val selectedClientId = clientId
        require(SpotifyConfig.validClientId(selectedClientId)) { "Isi Client ID terlebih dahulu." }
        val verifier = SpotifyConfig.randomToken()
        val expectedState = SpotifyConfig.randomToken()
        val listener = withContext(Dispatchers.IO) {
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 43821), 4)
                soTimeout = 1000
            }
        }
        server = listener
        try {
            val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                .appendQueryParameter("client_id", selectedClientId).appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", SpotifyConfig.REDIRECT_URI).appendQueryParameter("scope", SpotifyConfig.SCOPES)
                .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", SpotifyConfig.challenge(verifier))
                .appendQueryParameter("state", expectedState).build()
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, url)
            val code = withContext(Dispatchers.IO) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 300_000
                var received: String? = null
                while (currentCoroutineContext().isActive && attempt == generation && android.os.SystemClock.elapsedRealtime() < deadline && received == null) {
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    socket.use {
                        it.soTimeout = 2000
                        val bytes = mutableListOf<Byte>()
                        val input = it.getInputStream()
                        while (bytes.size < 8192) {
                            val byte = input.read()
                            if (byte < 0 || byte == 10) break
                            bytes.add(byte.toByte())
                        }
                        val line = bytes.toByteArray().toString(Charsets.US_ASCII).trim()
                        val parsed = SpotifyCallback.parse(line, expectedState)
                        val valid = parsed != null
                        val body = if (valid) "<!doctype html><meta name=viewport content='width=device-width'><title>NadaKuy</title><h1>Kembali ke NadaKuy</h1><p>Permintaan Spotify diterima. Tutup tab ini untuk melanjutkan.</p>" else "Invalid callback"
                        val payload = body.toByteArray(Charsets.UTF_8)
                        it.getOutputStream().write(("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Security-Policy: default-src 'none'\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${payload.size}\r\n\r\n").toByteArray() + payload)
                        if (valid) {
                            check(parsed.error == null) { "Izin Spotify dibatalkan. Kamu bisa menghubungkan kembali." }
                            received = parsed.code
                        }
                    }
                }
                received ?: error("Waktu menghubungkan Spotify habis. Kembali ke NadaKuy dan coba lagi.")
            }
            val token = tokenRequest(mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to SpotifyConfig.REDIRECT_URI,
                "client_id" to selectedClientId, "code_verifier" to verifier))
            check(attempt == generation && selectedClientId == clientId) { "Koneksi Spotify dibatalkan." }
            persist(token, selectedClientId)
        } finally {
            listener.close()
            if (server === listener) server = null
        }
    }

    suspend fun token(forceRefresh: Boolean = false): String = mutex.withLock {
        val saved = store.read() ?: error("Hubungkan akun Spotify terlebih dahulu.")
        check(saved.optString("client_id") == clientId) { "Hubungkan kembali akun Spotify." }
        if (!forceRefresh && saved.optLong("expires_at") > System.currentTimeMillis() + 60_000) return@withLock saved.getString("access_token")
        val attempt = generation
        val selectedClientId = clientId
        val next = tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to saved.getString("refresh_token"), "client_id" to selectedClientId))
        if (!next.has("refresh_token")) next.put("refresh_token", saved.getString("refresh_token"))
        check(attempt == generation && selectedClientId == clientId) { "Koneksi Spotify dibatalkan." }
        persist(next, selectedClientId)
        next.getString("access_token")
    }
    private fun persist(token: JSONObject, id: String) {
        require(token.optString("access_token").isNotBlank() && token.optString("refresh_token").isNotBlank()) { "Sesi Spotify tidak lengkap. Hubungkan kembali." }
        store.write(token.put("client_id", id).put("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000))
    }
    private suspend fun tokenRequest(fields: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI("https://accounts.spotify.com/api/token").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(fields.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }.toByteArray()) }
            check(connection.responseCode == 200) { "Spotify belum mengizinkan koneksi. Periksa Client ID, Redirect URI, dan akses akun di Dashboard." }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
}

internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
