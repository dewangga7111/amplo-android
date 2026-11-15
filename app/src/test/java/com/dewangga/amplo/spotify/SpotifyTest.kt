package com.dewangga.amplo.spotify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class SpotifyTest {
    @Test
    fun makesThePkceChallengeOfTheSpecificationExample() {
        // RFC 7636, appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Spotify.codeChallenge(verifier))
        assertEquals("Zg", Spotify.base64url("f".toByteArray()))
        assertEquals("Zm8", Spotify.base64url("fo".toByteArray()))
        assertEquals("Zm9v", Spotify.base64url("foo".toByteArray()))
        assertEquals("-_8", Spotify.base64url(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    @Test
    fun verifiersUseOnlyAllowedCharacters() {
        val verifier = Spotify.randomText(64)
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
    }

    @Test
    fun buildsTheLoginLink() {
        val url = Spotify.authorizeUrl("abc", "challenge", "state")
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?client_id=abc&response_type=code&"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A43821%2Fcallback"))
        assertTrue(url.contains("scope=playlist-read-private%20playlist-read-collaborative"))
        assertTrue(url.contains("code_challenge_method=S256&code_challenge=challenge&state=state"))
    }

    @Test
    fun readsTheLoginAnswer() {
        fun line(query: String) = "GET /callback?$query HTTP/1.1"
        assertEquals("a+b", Spotify.parseCallback(line("code=a%2Bb&state=s"), "s")?.getOrNull())
        assertEquals(CANCELED, Spotify.parseCallback(line("error=access_denied&state=s"), "s")?.exceptionOrNull()?.message)
        assertTrue(Spotify.parseCallback(line("code=a&state=other"), "s")!!.isFailure)
        assertNull(Spotify.parseCallback("GET /favicon.ico HTTP/1.1", "s"))
        assertNull(Spotify.parseCallback("POST /callback HTTP/1.1", "s"))
    }

    private fun request(port: Int, text: String): String = Socket(InetAddress.getByName("127.0.0.1"), port).use {
        it.getOutputStream().write(text.toByteArray())
        String(it.getInputStream().readBytes())
    }

    @Test
    fun theLoginServerAnswersTheBrowser() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            var icon = ""
            var answer = ""
            // Browsers open connections before they know what to ask, and may not use them.
            val idle = Socket(InetAddress.getByName("127.0.0.1"), server.localPort)
            val browser = thread {
                icon = request(server.localPort, "GET /favicon.ico HTTP/1.1\r\n\r\n")
                answer = request(server.localPort, "GET /callback?code=c0de&state=s HTTP/1.1\r\nHost: x\r\n\r\n")
            }
            assertEquals("c0de", Spotify.waitForCode(server, "s", timeoutMs = 10_000))
            browser.join()
            idle.close()
            assertTrue(icon.startsWith("HTTP/1.1 404"))
            assertTrue(answer.startsWith("HTTP/1.1 200 OK"))
            assertTrue(answer.contains("You are logged in"))
            assertTrue(answer.contains(RETURN_LINK))
        }
    }

    @Test
    fun aCanceledLoginStopsWaiting() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread {
            Thread.sleep(300)
            server.close()
        }
        val error = runCatching { Spotify.waitForCode(server, "s", timeoutMs = 10_000) }.exceptionOrNull()
        assertEquals(CANCELED, error?.message)
    }

    @Test
    fun readsPlaylistsInTheNewAndTheOldFormat() {
        val page = JSONObject(
            """{"items":[
                {"id":"p1","name":"Road","owner":{"id":"me","display_name":"Me"},"items":{"total":12},
                 "images":[{"url":"big","width":640},{"url":"mid","width":300},{"url":"small","width":60}]},
                {"id":"p2","name":"Old","owner":{"id":"friend","display_name":null},"tracks":{"total":3},"images":[{"url":"one","width":null}]},
                {"id":"p3","name":"Bare","owner":{},"images":null},
                {"name":"No id"}
            ],"next":"https://api.spotify.com/v1/me/playlists?offset=50&limit=50"}""",
        )
        val (playlists, next) = Spotify.parsePlaylists(page)
        assertEquals("https://api.spotify.com/v1/me/playlists?offset=50&limit=50", next)
        assertEquals(3, playlists.size)
        assertEquals(SpotifyPlaylist("p1", "Road", "Me", 12, "mid"), playlists[0])
        assertEquals(Triple("friend", 3, "one"), Triple(playlists[1].owner, playlists[1].tracks, playlists[1].image))
        assertEquals(0 to null, playlists[2].tracks to playlists[2].image)
    }

    @Test
    fun readsPlaylistSongsAndSkipsTheRest() {
        val page = JSONObject(
            """{"items":[
                {"item":{"type":"track","name":"Song","duration_ms":200000,"artists":[{"name":"A"},{"name":"B"}],
                         "album":{"name":"Album","images":[{"url":"big","width":640},{"url":"small","width":64}]}},"is_local":false},
                {"track":{"type":"track","name":"Older","artists":[{"name":"C"}],"album":{"name":"","images":[]}}},
                {"item":{"type":"episode","name":"Podcast"}},
                {"item":null,"track":true}
            ],"next":null}""",
        )
        val (tracks, next) = Spotify.parseTracks(page)
        assertNull(next)
        assertEquals(2, tracks.size)
        assertEquals(SpotifyTrack("Song", listOf("A", "B"), "Album", "small", 200_000), tracks[0])
        assertEquals("Older" to null, tracks[1].name to tracks[1].image)
    }

    @Test
    fun readsSpotifyErrors() {
        assertEquals("Spotify answered 403: Forbidden", Spotify.errorText(403, JSONObject("""{"error":{"status":403,"message":"Forbidden"}}""")))
        assertEquals("Spotify answered 400: Invalid client", Spotify.errorText(400, JSONObject("""{"error":"invalid_client","error_description":"Invalid client"}""")))
        assertEquals("Spotify answered 500: no details", Spotify.errorText(500, null))
    }

    @Test
    fun normalizesTitlesForMatching() {
        assertEquals("rock and roll", normalize("  Rock & Roll!  "))
        assertEquals("beyoncé halo", normalize("Beyoncé — Halo"))
        assertEquals("song", baseTitle("Song (Live) [2011 Remaster]"))
        assertEquals("song", baseTitle("Song - Radio Edit"))
        assertEquals("song", baseTitle("Song feat. Someone"))
        assertEquals("intro", baseTitle("(Intro)"))
        assertEquals("夜に駆ける", baseTitle("夜に駆ける"))
    }

    private fun track(name: String, vararg artists: String) = SpotifyTrack(name, artists.toList(), "", null, 0)

    @Test
    fun findsLocalSongsByTitleAndArtist() {
        val index = LocalIndex(
            listOf(
                "Blinding Lights" to "The Weeknd",
                "Levitating (feat. DaBaby)" to "Dua Lipa, DaBaby",
                "Untitled" to "",
                "Hello" to "Adele",
            ),
        )
        assertEquals(0, index.find(track("Blinding Lights", "The Weeknd")))
        assertEquals(1, index.find(track("Levitating", "Dua Lipa")))
        assertEquals(2, index.find(track("Untitled - 2020 Remaster", "Anyone")))
        assertNull(index.find(track("Hello", "Lionel Richie")))
        assertNull(index.find(track("Someone Like You", "Adele")))
    }
}
