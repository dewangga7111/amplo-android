package com.dewangga.amplo.spotify

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/*
 * Reads the user's Spotify playlists through the Spotify Web API, the same way as the desktop
 * app. The login uses the authorization code flow with PKCE, so the app needs only a client ID
 * and no secret. Spotify sends the browser back to a small server on the loopback address of the
 * phone, with the same redirect URI as the desktop app, so one Spotify app serves both.
 */

/** Must be listed exactly like this in the app's settings on the Spotify dashboard. */
const val REDIRECT_URI = "http://127.0.0.1:43821/callback"
private const val REDIRECT_PORT = 43821
private const val CALLBACK_PATH = "/callback"
private const val SCOPES = "playlist-read-private playlist-read-collaborative"
private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
private const val API = "https://api.spotify.com/v1"
/** How long the app waits for the browser to come back from the login page. */
private const val LOGIN_TIMEOUT_MS = 300_000L
private const val HTTP_TIMEOUT_MS = 20_000
/** An access token this close to its end counts as expired, so it cannot expire on the way. */
private const val EXPIRY_MARGIN_MS = 60_000L
/** Spotify asks clients that send too many requests to wait. Waits beyond this give up. */
private const val MAX_RETRY_WAIT_S = 30L
private const val RETRIES = 3
const val NOT_SHARED = "Spotify only shares the songs of playlists you own or collaborate on."
const val LOGIN_EXPIRED = "Your Spotify login has expired. Log in again."
const val CANCELED = "Spotify login was canceled."
/** Brings amplo back from the browser once the login is done. */
const val RETURN_LINK = "intent://spotify#Intent;scheme=amplo;package=com.dewangga.amplo;end"

data class SpotifyUser(val id: String, val name: String)

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val tracks: Int,
    /** A temporary link, so it is not saved. */
    val image: String?,
)

data class SpotifyTrack(
    val name: String,
    val artists: List<String>,
    val album: String,
    val image: String?,
    val durationMs: Long,
)

/** Why a request failed. [LoggedOut] means the saved login no longer works. */
sealed class SpotifyFailure(message: String) : Exception(message) {
    class LoggedOut : SpotifyFailure(LOGIN_EXPIRED)
    class Status(val status: Int, message: String) : SpotifyFailure(message)
    class Other(message: String) : SpotifyFailure(message)
}

/** The tokens of a login. [refresh] goes into the settings, so the login lasts. */
data class Tokens(val access: String, val refresh: String?, val lifetimeMs: Long)

object Spotify {
    private val random = SecureRandom()
    private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Unpadded base64url, as PKCE uses. */
    fun base64url(bytes: ByteArray): String {
        val text = StringBuilder((bytes.size + 2) / 3 * 4)
        for (start in bytes.indices step 3) {
            val chunk = bytes.copyOfRange(start, minOf(start + 3, bytes.size))
            var bits = 0
            chunk.forEachIndexed { index, byte -> bits = bits or ((byte.toInt() and 0xFF) shl (16 - 8 * index)) }
            for (index in 0..chunk.size) text.append(BASE64URL[(bits shr (18 - 6 * index)) and 63])
        }
        return text.toString()
    }

    fun codeChallenge(verifier: String): String =
        base64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    fun randomText(bytes: Int): String = base64url(ByteArray(bytes).also(random::nextBytes))

    /** Percent-encodes everything but letters, digits and `-._~`. */
    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
        .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    fun decode(text: String): String {
        val bytes = text.toByteArray()
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index]
            val hex = if (index + 2 < bytes.size) String(bytes, index + 1, 2).toIntOrNull(16) else null
            when {
                byte == '%'.code.toByte() && hex != null -> {
                    out.write(hex)
                    index += 3
                    continue
                }
                byte == '+'.code.toByte() -> out.write(' '.code)
                else -> out.write(byte.toInt())
            }
            index++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun authorizeUrl(clientId: String, challenge: String, state: String): String {
        val query = listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "state" to state,
            "scope" to SCOPES,
        )
        return AUTHORIZE_URL + "?" + query.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    /**
     * Reads the first line of the request the login page sends the browser back with. Null means
     * some other request, such as the browser asking for an icon. Otherwise the code, or a
     * failure with a message for the user.
     */
    fun parseCallback(requestLine: String, state: String): Result<String>? {
        if (!requestLine.startsWith("GET ")) return null
        val target = requestLine.removePrefix("GET ").substringBefore(' ')
        val path = target.substringBefore('?')
        val query = if (target.contains('?')) target.substringAfter('?') else ""
        if (path != CALLBACK_PATH) return null
        val params = query.split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to decode(it.substringAfter('=')) }
        if (params["state"] != state) {
            return Result.failure(SpotifyFailure.Other("The Spotify login answer did not match this login. Try again."))
        }
        params["error"]?.let { error ->
            return Result.failure(SpotifyFailure.Other(if (error == "access_denied") CANCELED else "Spotify login failed: $error"))
        }
        val code = params["code"]?.takeIf(String::isNotEmpty)
            ?: return Result.failure(SpotifyFailure.Other("Spotify sent no login code."))
        return Result.success(code)
    }

    private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** The page the browser shows after the login. A button brings amplo back. */
    fun page(title: String, text: String): String =
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>amplo</title>" +
            "<body style=\"background:#0e0f12;color:#f2f3f5;font:16px system-ui,sans-serif;display:grid;place-items:center;height:90vh;margin:0 16px\">" +
            "<div style=\"text-align:center\"><h1 style=\"font-size:24px\">${escapeHtml(title)}</h1>" +
            "<p style=\"color:#9aa1ac\">${escapeHtml(text)}</p>" +
            "<p><a href=\"$RETURN_LINK\" style=\"display:inline-block;margin-top:12px;padding:12px 22px;border-radius:999px;" +
            "background:#c8f25a;color:#0e0f12;font-weight:600;text-decoration:none\">Back to amplo</a></p></div>"

    /**
     * Answers one browser request. Returns the result once the right request came. A browser can
     * open a connection before it knows what to ask, and may then not use it. Such a connection
     * closes without an answer, as an answer on it could go to a later request.
     */
    fun answer(socket: Socket, state: String): Result<String>? = socket.use {
        it.soTimeout = 15_000
        val input = it.getInputStream()
        val request = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (request.size() < 16 * 1024 && !String(request.toByteArray(), Charsets.ISO_8859_1).contains("\r\n")) {
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (read <= 0) break
            request.write(buffer, 0, read)
        }
        val firstLine = String(request.toByteArray(), Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
        if (firstLine.isBlank()) return@use null
        val result = parseCallback(firstLine, state)
        val (status, body) = when {
            result == null -> "404 Not Found" to ""
            result.isSuccess -> "200 OK" to page("You are logged in", "Go back to amplo. You can close this tab.")
            else -> "400 Bad Request" to page("Login failed", result.exceptionOrNull()?.message.orEmpty())
        }
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        try {
            it.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
        } catch (_: IOException) {
        }
        result
    }

    /** Opens the server the browser comes back to. */
    fun openServer(): ServerSocket = try {
        ServerSocket(REDIRECT_PORT, 8, InetAddress.getByName("127.0.0.1"))
    } catch (error: IOException) {
        throw SpotifyFailure.Other("Could not open 127.0.0.1:$REDIRECT_PORT for the Spotify login: ${error.message}")
    }

    /**
     * Waits for the browser to come back with the login code. Closing [server] from another
     * thread stops the wait as a canceled login.
     */
    fun waitForCode(server: ServerSocket, state: String, timeoutMs: Long = LOGIN_TIMEOUT_MS): String {
        val started = System.currentTimeMillis()
        // Each connection gets its own thread, so one the browser keeps open does not hold up
        // the others.
        val results = LinkedBlockingQueue<Result<String>>()
        server.soTimeout = 200
        while (true) {
            results.poll()?.let { return it.getOrThrow() }
            if (server.isClosed) throw SpotifyFailure.Other(CANCELED)
            if (System.currentTimeMillis() - started > timeoutMs) {
                throw SpotifyFailure.Other("The Spotify login took too long. Try again.")
            }
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: IOException) {
                if (server.isClosed) throw SpotifyFailure.Other(CANCELED)
                throw SpotifyFailure.Other("Spotify login stopped: ${error.message}")
            }
            thread(isDaemon = true, name = "spotify-login") { answer(socket, state)?.let(results::add) }
        }
    }

    /** The error text of a Web API answer, or of an accounts answer. */
    fun errorText(status: Int, body: JSONObject?): String {
        val error = body?.opt("error")
        val message = (error as? JSONObject)?.optString("message")?.takeIf(String::isNotEmpty)
            ?: body?.optString("error_description")?.takeIf(String::isNotEmpty)
            ?: (error as? String)?.takeIf(String::isNotEmpty)
            ?: "no details"
        return "Spotify answered $status: $message"
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
    }

    private fun json(text: String): JSONObject = try {
        JSONObject(text)
    } catch (error: Exception) {
        throw SpotifyFailure.Other("Spotify sent an answer amplo does not understand: ${error.message}")
    }

    private fun connect(url: String): HttpURLConnection = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
        }
    } catch (error: IOException) {
        throw SpotifyFailure.Other("Could not reach Spotify: ${error.message}")
    }

    /** Sends a form to the accounts service and returns the new tokens. */
    fun tokenRequest(form: List<Pair<String, String>>): Tokens {
        val connection = connect(TOKEN_URL)
        val (status, text) = try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { out ->
                out.write(form.joinToString("&") { (key, value) -> "$key=${encode(value)}" }.toByteArray())
            }
            connection.responseCode to readBody(connection)
        } catch (error: IOException) {
            throw SpotifyFailure.Other("Could not reach Spotify: ${error.message}")
        } finally {
            connection.disconnect()
        }
        val body = json(text)
        if (status == 400 && body.optString("error") == "invalid_grant") throw SpotifyFailure.LoggedOut()
        if (status !in 200..299) throw SpotifyFailure.Status(status, errorText(status, body))
        val access = body.optString("access_token").takeIf(String::isNotEmpty)
            ?: throw SpotifyFailure.Other("Spotify sent no access token.")
        val refresh = body.optString("refresh_token").takeIf(String::isNotEmpty)
        return Tokens(access, refresh, body.optLong("expires_in", 3600) * 1000)
    }

    /** Swaps the code from the login page for the tokens. */
    fun exchangeCode(clientId: String, code: String, verifier: String): Tokens = tokenRequest(
        listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId,
            "code_verifier" to verifier,
        ),
    )

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).takeIf(String::isNotEmpty)

    /** Spotify lists the largest image first. The smallest that is still large enough looks sharp. */
    fun bestImage(images: JSONArray?, smallest: Int): String? {
        if (images == null || images.length() == 0) return null
        val list = (0 until images.length()).mapNotNull(images::optJSONObject)
        val large = list.lastOrNull { it.isNull("width") || it.optInt("width") >= smallest }
        return (large ?: list.firstOrNull())?.text("url")
    }

    /** One page of `GET /me/playlists`, and the link to the next page. */
    fun parsePlaylists(page: JSONObject): Pair<List<SpotifyPlaylist>, String?> {
        val items = page.optJSONArray("items") ?: JSONArray()
        val playlists = (0 until items.length()).mapNotNull { index ->
            val playlist = items.optJSONObject(index) ?: return@mapNotNull null
            val id = playlist.text("id") ?: return@mapNotNull null
            val owner = playlist.optJSONObject("owner")
            SpotifyPlaylist(
                id = id,
                name = playlist.text("name").orEmpty(),
                owner = owner?.text("display_name") ?: owner?.text("id").orEmpty(),
                // `tracks` was renamed to `items` in February 2026. Older answers still work.
                tracks = (playlist.optJSONObject("items") ?: playlist.optJSONObject("tracks"))?.optInt("total", 0) ?: 0,
                image = bestImage(playlist.optJSONArray("images"), 120),
            )
        }
        return playlists to page.text("next")
    }

    /** One page of `GET /playlists/{id}/items`. Podcast episodes and removed songs are left out. */
    fun parseTracks(page: JSONObject): Pair<List<SpotifyTrack>, String?> {
        val items = page.optJSONArray("items") ?: JSONArray()
        val tracks = (0 until items.length()).mapNotNull { index ->
            val row = items.optJSONObject(index) ?: return@mapNotNull null
            // `track` was renamed to `item` in February 2026.
            val item = row.optJSONObject("item") ?: row.optJSONObject("track") ?: return@mapNotNull null
            if (item.text("type")?.let { it != "track" } == true) return@mapNotNull null
            val artistList = item.optJSONArray("artists") ?: JSONArray()
            val artists = (0 until artistList.length()).mapNotNull { artistList.optJSONObject(it)?.text("name") }
            val album = item.optJSONObject("album")
            SpotifyTrack(
                name = item.text("name").orEmpty(),
                artists = artists,
                album = album?.text("name").orEmpty(),
                image = bestImage(album?.optJSONArray("images"), 64),
                durationMs = item.optLong("duration_ms", 0),
            )
        }
        return tracks to page.text("next")
    }

    /** A login that waits in the browser: the page to show, and what is needed when the code comes. */
    class PendingLogin(val clientId: String, val url: String, val verifier: String, val state: String, val server: ServerSocket)

    /** Opens the login server and makes the login page. */
    fun startLogin(clientId: String): PendingLogin {
        val id = clientId.trim()
        if (id.isEmpty()) throw SpotifyFailure.Other("Paste the Client ID of your Spotify app first.")
        val server = openServer()
        val verifier = randomText(64)
        val state = randomText(16)
        return PendingLogin(id, authorizeUrl(id, codeChallenge(verifier), state), verifier, state, server)
    }
}

/**
 * A login that works. The lock stays held while a new token comes, so two requests never use the
 * same refresh token, as Spotify may accept each one only once. [onTokenChanged] gets a new
 * refresh token to save.
 */
class SpotifySession(
    private val clientId: String,
    refreshToken: String,
    access: String = "",
    lifetimeMs: Long = 0,
    private val onTokenChanged: (String) -> Unit,
) {
    private val lock = ReentrantLock()
    private var refresh = refreshToken
    private var access = access
    private var expires = System.currentTimeMillis() + lifetimeMs

    private fun accessToken(force: Boolean): String = lock.withLock {
        if (!force && access.isNotEmpty() && System.currentTimeMillis() + EXPIRY_MARGIN_MS < expires) return access
        val tokens = Spotify.tokenRequest(
            listOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId),
        )
        access = tokens.access
        expires = System.currentTimeMillis() + tokens.lifetimeMs
        val newRefresh = tokens.refresh
        if (newRefresh != null && newRefresh != refresh) {
            refresh = newRefresh
            onTokenChanged(newRefresh)
        }
        access
    }

    private fun get(url: String): JSONObject {
        var renewed = false
        var retries = 0
        while (true) {
            val token = accessToken(renewed)
            val connection = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer $token")
                }
            } catch (error: IOException) {
                throw SpotifyFailure.Other("Could not reach Spotify: ${error.message}")
            }
            try {
                val status = connection.responseCode
                when {
                    status in 200..299 -> return JSONObject(String(connection.inputStream.use { it.readBytes() }, Charsets.UTF_8))
                    status == 401 && !renewed -> renewed = true
                    (status == 429 || status == 502 || status == 503) && retries < RETRIES -> {
                        retries++
                        val wait = connection.getHeaderField("retry-after")?.trim()?.toLongOrNull() ?: 2
                        if (wait > MAX_RETRY_WAIT_S) throw SpotifyFailure.Status(status, "Spotify is busy. Try again in a minute.")
                        Thread.sleep(wait.coerceAtLeast(1) * 1000)
                    }
                    else -> {
                        val text = connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                        val body = try {
                            JSONObject(text)
                        } catch (_: Exception) {
                            null
                        }
                        throw SpotifyFailure.Status(status, Spotify.errorText(status, body))
                    }
                }
            } catch (error: IOException) {
                throw SpotifyFailure.Other("Could not reach Spotify: ${error.message}")
            } catch (error: org.json.JSONException) {
                throw SpotifyFailure.Other("Spotify sent an answer amplo does not understand: ${error.message}")
            } finally {
                connection.disconnect()
            }
        }
    }

    /** The user and every playlist of theirs. Blocks, so it runs on a background thread. */
    fun playlists(): Pair<SpotifyUser, List<SpotifyPlaylist>> {
        val me = get("$API/me")
        val id = me.optString("id")
        val name = me.optString("display_name").takeIf { it.isNotEmpty() && !me.isNull("display_name") } ?: id
        val playlists = ArrayList<SpotifyPlaylist>()
        var next: String? = "$API/me/playlists?limit=50"
        while (next != null) {
            val (page, link) = Spotify.parsePlaylists(get(next))
            playlists += page
            next = link
        }
        return SpotifyUser(id, name) to playlists
    }

    /** Every song of a playlist. Blocks, so it runs on a background thread. */
    fun tracks(playlist: String): List<SpotifyTrack> {
        val tracks = ArrayList<SpotifyTrack>()
        var next: String? = "$API/playlists/${Spotify.encode(playlist)}/items?limit=50&market=from_token&additional_types=track"
        try {
            while (next != null) {
                val (page, link) = Spotify.parseTracks(get(next))
                tracks += page
                next = link
            }
        } catch (failure: SpotifyFailure.Status) {
            if (failure.status == 403) throw SpotifyFailure.Other(NOT_SHARED)
            throw failure
        }
        return tracks
    }
}
