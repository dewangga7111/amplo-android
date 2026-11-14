package com.dewangga.amplo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uTest {
    private val folder = "/storage/emulated/0/Music"

    @Test
    fun reads_entries_and_ignores_comments() {
        val text = "\uFEFF#EXTM3U\r\nsub/a.flac\r\n\r\n#EXTINF:1,x\r\nb.flac\r\n"
        assertEquals(listOf("sub/a.flac", "b.flac"), M3u.entries(text))
        assertEquals("storage/emulated/0/music/sub/a.flac", M3u.resolve(folder, "sub\\A.flac"))
        assertEquals("storage/emulated/0/x.mp3", M3u.resolve(folder, "../x.mp3"))
        assertEquals("g:/music/a.flac", M3u.resolve(folder, "G:\\Music\\a.flac"))
    }

    @Test
    fun adds_songs_once_and_keeps_the_line_ends() {
        val (text, added) = M3u.add("#EXTM3U\r\nsub\\a.flac\r\n", folder, listOf("$folder/sub/a.flac", "$folder/b.flac", "/other/c.mp3"))
        assertEquals(2, added)
        assertEquals("#EXTM3U\r\nsub\\a.flac\r\nb.flac\r\n/other/c.mp3\r\n", text)
        assertEquals(0, M3u.add(text, folder, listOf("$folder/B.flac")).second)
        assertEquals("#EXTM3U\na.mp3\n" to 1, M3u.add("#EXTM3U", folder, listOf("$folder/a.mp3")).let { it.first.replace("\r\n", "\n") to it.second })
        assertEquals("#EXTM3U\r\na.mp3\r\n" to 1, M3u.add("", folder, listOf("$folder/a.mp3")))
    }

    @Test
    fun removes_songs_with_their_extinf_lines() {
        val text = "#EXTM3U\n#EXTINF:3,A\na.mp3\n# note\nb.mp3\n"
        assertEquals("#EXTM3U\n# note\nb.mp3\n" to 1, M3u.remove(text, folder, listOf("$folder/a.mp3")))
        assertEquals(text to 0, M3u.remove(text, folder, listOf("$folder/c.mp3")))
    }

    @Test
    fun makes_names_from_files_and_files_from_names() {
        assertEquals("SamsungMusic_favorites", M3u.displayName("!#SamsungMusic_favorites#!.m3u"))
        assertEquals("Road Trip", M3u.displayName("Road Trip.m3u8"))
        assertEquals("ACDC Mix", M3u.fileNameFor("  AC/DC   Mix.. "))
        assertNull(M3u.fileNameFor(" ?* "))
        assertEquals("#EXTM3U\r\na.mp3\r\n/x/b.mp3\r\n", M3u.create(folder, listOf("$folder/a.mp3", "/x/b.mp3")))
    }

    @Test
    fun writes_songs_beside_the_playlist_folder_relative_to_it() {
        val playlists = "$folder/Playlists"
        assertEquals("../Band/a.mp3", M3u.entryFor(playlists, "$folder/Band/a.mp3"))
        assertEquals("a.mp3", M3u.entryFor(playlists, "$playlists/a.mp3"))
        // Outside Music, or on another storage, a relative path would not work on a computer.
        assertEquals("/storage/emulated/0/Download/a.mp3", M3u.entryFor(playlists, "/storage/emulated/0/Download/a.mp3"))
        assertEquals("/storage/1234-ABCD/Music/a.mp3", M3u.entryFor(playlists, "/storage/1234-ABCD/Music/a.mp3"))
        assertEquals("../a.mp3", M3u.entryFor("/storage/1234-ABCD/Music/Playlists", "/storage/1234-ABCD/Music/a.mp3"))
        assertEquals(
            "storage/emulated/0/music/band/a.mp3",
            M3u.resolve(playlists, M3u.entryFor(playlists, "$folder/Band/a.mp3")),
        )
    }

    @Test
    fun finds_songs_of_playlists_made_on_a_computer() {
        fun track(id: Long, path: String) = Track(id, "", "", "", "", "", 0, 0, 0, 0, path = path)
        val index = TrackIndex(
            listOf(
                track(1, "/storage/emulated/0/Music/Band/One/song.flac"),
                track(2, "/storage/emulated/0/Music/Band/Two/song.flac"),
                track(3, "/storage/emulated/0/Music/solo.mp3"),
            ),
        )
        assertEquals(3L, index.find(M3u.resolve(folder, "solo.mp3")))
        assertEquals(2L, index.find(M3u.resolve(folder, "G:\\Music\\Band\\Two\\song.flac")))
        assertEquals(3L, index.find(M3u.pathKey("D:/Old/solo.mp3")))
        // The desktop path of a song counts as that song when adding and removing.
        val text = "G:\\Music\\solo.mp3\r\n"
        assertEquals(0, M3u.add(text, folder, listOf("$folder/solo.mp3"), index::identify).second)
        assertEquals("" to 1, M3u.remove(text, folder, listOf("$folder/solo.mp3"), index::identify))
        // Both songs end in "song.flac", so a bare name is not enough.
        assertNull(index.find(M3u.pathKey("D:/song.flac")))
        assertNull(index.find(M3u.pathKey("D:/missing.flac")))
    }
}
