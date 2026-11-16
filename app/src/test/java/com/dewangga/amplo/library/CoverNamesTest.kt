package com.dewangga.amplo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverNamesTest {
    @Test
    fun findsTheCoverWithTheNameOfThePlaylist() {
        val names = listOf("Road Trip.m3u", "road trip.PNG", "Road Trip.webp", "folder.jpg", "Late Night.m3u8")
        assertEquals("Road Trip", CoverNames.stem("/storage/emulated/0/Music/Road Trip.m3u"))
        assertEquals("road trip.PNG", CoverNames.pick(names, "Road Trip"))
        assertNull(CoverNames.pick(names, "Late Night"))
        assertEquals("Mix.jpg", CoverNames.pick(listOf("Mix.png", "Mix.jpg"), "Mix"), "a JPEG comes first, as on the desktop")
    }

    @Test
    fun givesTheDocumentIdOfAPath() {
        val root = "/storage/emulated/0"
        assertEquals("primary:Music/Playlists", CoverNames.documentId("/storage/emulated/0/Music/Playlists", root))
        assertEquals("primary:", CoverNames.documentId("/storage/emulated/0", root))
        assertEquals("1A2B-3C4D:Music", CoverNames.documentId("/storage/1A2B-3C4D/Music", root))
        assertNull(CoverNames.documentId("/storage/emulated/10/Music", root))
        assertNull(CoverNames.documentId("/data/user/0/x", root))
    }

    @Test
    fun knowsWhichFolderHasAFile() {
        assertTrue(CoverNames.isInside("primary:Music/Playlists", "primary:Music"))
        assertTrue(CoverNames.isInside("primary:music", "primary:Music"))
        assertTrue(CoverNames.isInside("primary:Music", "primary:"))
        assertFalse(CoverNames.isInside("primary:Musical", "primary:Music"))
        assertFalse(CoverNames.isInside("1A2B-3C4D:Music", "primary:Music"))
    }

    private fun assertEquals(expected: Any?, actual: Any?, message: String) = assertEquals(message, expected, actual)
}
