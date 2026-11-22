package com.dewangga.amplo.sync

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
    private fun song(path: String, size: Long = 100) = SyncSong(path, size)

    @Test
    fun eachSideGetsTheSongsItDoesNotHave() {
        val plan = SyncPlan.make(
            computerSongs = listOf(song("Band/a.flac"), song("b.mp3")),
            computerPlaylists = emptyList(),
            phoneSongs = listOf(song("band/A.flac"), song("Download/c.opus")),
            phonePlaylists = emptyList(),
        )
        assertEquals(listOf(song("b.mp3")), plan.toPhone)
        assertEquals(listOf(song("Download/c.opus")), plan.toComputer)
        assertTrue(plan.phonePlaylists.isEmpty() && plan.computerPlaylists.isEmpty())
    }

    @Test
    fun songsInOtherFoldersMatchByNameAndSize() {
        val plan = SyncPlan.make(
            computerSongs = listOf(song("Mix/one.flac", 5), song("A/two.mp3", 7), song("B/two.mp3", 9)),
            computerPlaylists = emptyList(),
            phoneSongs = listOf(song("Download/one.flac", 6), song("Other/two.mp3", 9)),
            phonePlaylists = emptyList(),
        )
        // "one.flac" is the only song of its name on each side, so the size may differ.
        // "two.mp3" is on the computer twice, so only the one of the same size matches.
        assertEquals(listOf(song("A/two.mp3", 7)), plan.toPhone)
        assertTrue(plan.toComputer.isEmpty())
    }

    @Test
    fun playlistsGetTheSongsOfThePlaylistOfTheSameNameOnTheOtherSide() {
        val plan = SyncPlan.make(
            computerSongs = listOf(song("x.flac"), song("Mix/y.flac"), song("z.flac")),
            computerPlaylists = listOf(
                SyncPlaylist("Workout", listOf("x.flac", "Mix/y.flac")),
                SyncPlaylist("Chill", listOf("z.flac")),
            ),
            phoneSongs = listOf(song("Download/y.flac"), song("p.mp3")),
            phonePlaylists = listOf(
                SyncPlaylist("workout", listOf("Download/y.flac", "p.mp3")),
                SyncPlaylist("Road", listOf("p.mp3")),
            ),
        )
        // The phone adds the computer's songs with its own paths: y.flac is in Download there.
        assertEquals(
            listOf(
                PlaylistChange("workout", listOf("x.flac"), create = false),
                PlaylistChange("Chill", listOf("z.flac"), create = true),
            ),
            plan.phonePlaylists,
        )
        assertEquals(
            listOf(
                PlaylistChange("Workout", listOf("p.mp3"), create = false),
                PlaylistChange("Road", listOf("p.mp3"), create = true),
            ),
            plan.computerPlaylists,
        )
        assertEquals(setOf("workout", "chill", "road"), plan.playlistNames)
    }

    @Test
    fun theSameSongsAndPlaylistsNeedNothing() {
        val songs = listOf(song("a.flac"))
        val playlists = listOf(SyncPlaylist("Mix", listOf("a.flac")))
        assertTrue(SyncPlan.make(songs, playlists, songs, playlists).isEmpty)
    }

    @Test
    fun phonePathsBecomePathsInTheMusicFolder() {
        assertEquals("Band/a.flac", SyncPlan.relativePath("/storage/emulated/0/Music/Band/a.flac"))
        assertEquals("Download/a.mp3", SyncPlan.relativePath("/storage/1234-ABCD/Download/a.mp3"))
        assertEquals("a.mp3", SyncPlan.relativePath("/storage/emulated/0/music/a.mp3"))
        assertEquals("Music", SyncPlan.relativePath("/storage/emulated/0/Music"))
        assertNull(SyncPlan.relativePath("/data/a.mp3"))
    }

    @Test
    fun computerAddressesAreRead() {
        assertEquals(Computer("192.168.1.20", "192.168.1.20", 47821), Computer.parse(" 192.168.1.20 "))
        assertEquals(Computer("PC", "10.0.2.2", 5000), Computer.parse("10.0.2.2:5000", "PC"))
        assertNull(Computer.parse("10.0.2.2:x"))
        assertNull(Computer.parse("a b"))
        assertNull(Computer.parse(""))
    }

    @Test
    fun requestsAndFilesGoBothWays() {
        val server = ServerSocket(0)
        val received = ByteArrayOutputStream()
        val worker = thread {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                fun line(): JSONObject {
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read()
                        if (byte == '\n'.code || byte < 0) break
                        bytes.write(byte)
                    }
                    return JSONObject(bytes.toString("UTF-8"))
                }
                // "library", then "put" with 5 bytes, then "get".
                val library = line()
                assertEquals("k", library.getString("key"))
                output.write("{\"ok\":true,\"songs\":[{\"path\":\"a\\/b.flac\",\"size\":3,\"lyrics\":true}],\"playlists\":[{\"name\":\"Mix\",\"songs\":[\"a/b.flac\"]}]}\n".toByteArray())
                val put = line()
                val body = ByteArray(put.getInt("size"))
                var read = 0
                while (read < body.size) read += input.read(body, read, body.size - read)
                received.write(body)
                output.write("{\"ok\":false,\"error\":\"exists\"}\n".toByteArray())
                line()
                output.write("{\"ok\":true,\"size\":4}\nabcd".toByteArray())
                output.flush()
            }
        }
        SyncConnection(Computer.parse("127.0.0.1:${server.localPort}")!!).use { connection ->
            connection.key = "k"
            val (songs, playlists) = SyncConnection.parseLibrary(connection.ask(JSONObject().put("op", "library")))
            assertEquals(listOf(SyncSong("a/b.flac", 3, lyrics = true)), songs)
            assertEquals(listOf(SyncPlaylist("Mix", listOf("a/b.flac"))), playlists)
            val refused = try {
                connection.send(JSONObject().put("op", "put"), ByteArrayInputStream("hello".toByteArray()), 5) {}
                null
            } catch (error: SyncRefused) {
                error.message
            }
            assertEquals("exists", refused)
            val file = ByteArrayOutputStream()
            var progress = 0L
            assertEquals(4L, connection.receive(JSONObject().put("op", "get"), file) { progress = it })
            assertEquals("abcd", file.toString("UTF-8"))
            assertEquals(4L, progress)
        }
        worker.join()
        server.close()
        assertEquals("hello", received.toString("UTF-8"))
    }
}
