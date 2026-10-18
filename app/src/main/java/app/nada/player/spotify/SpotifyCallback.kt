package app.nada.player.spotify

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/** Parse only the bounded request line belonging to this pending PKCE attempt. */
object SpotifyCallback {
    data class Result(val code: String?, val error: String?)
    fun parse(line: String, expectedState: String): Result? = runCatching {
        require(line.length <= 8192)
        val parts = line.split(' ')
        require(parts.size == 3 && parts[0] == "GET" && parts[2] in listOf("HTTP/1.0", "HTTP/1.1"))
        val uri = URI(parts[1])
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.path == "/callback" && uri.fragment == null)
        val pairs = uri.rawQuery.orEmpty().split('&').map {
            val pair = it.split('=', limit = 2)
            require(pair.size == 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        val query = pairs.toMap()
        require(MessageDigest.isEqual(query["state"].orEmpty().toByteArray(), expectedState.toByteArray()))
        val code = query["code"]?.takeIf { it.isNotBlank() }
        val error = query["error"]?.takeIf { it.isNotBlank() }
        require((code == null) != (error == null))
        Result(code, error)
    }.getOrNull()
}
