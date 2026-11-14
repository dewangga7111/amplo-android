package com.dewangga.amplo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SortTest {
    private fun track(
        id: Long,
        title: String,
        artist: String = "",
        album: String = "",
        disc: Int = 0,
        number: Int = 0,
        added: Long = 0,
    ) = Track(id, title, artist, album, "", "", disc, number, 0, added)

    private fun List<Track>.titles() = map { it.title }

    @Test
    fun recently_added_shows_the_newest_first_and_keeps_ties_in_place() {
        val songs = listOf(track(1, "old", added = 10), track(2, "new", added = 30), track(3, "tie a", added = 20), track(4, "tie b", added = 20))
        assertEquals(listOf("new", "tie a", "tie b", "old"), songs.sortedBy(Sort.RecentlyAdded).titles())
    }

    @Test
    fun title_ignores_case_and_then_sorts_by_artist_with_empty_names_last() {
        val songs = listOf(track(1, "b"), track(2, "A", artist = "z"), track(3, "a", artist = ""), track(4, "a", artist = "y"))
        assertEquals(listOf(4L, 2L, 3L, 1L), songs.sortedBy(Sort.Title).map { it.id })
    }

    @Test
    fun artist_keeps_each_album_in_disc_and_track_order() {
        val songs = listOf(
            track(1, "untagged", artist = "Band", album = "One"),
            track(2, "second disc", artist = "Band", album = "One", disc = 2, number = 1),
            track(3, "two", artist = "Band", album = "One", number = 2),
            track(4, "one", artist = "Band", album = "One", disc = 1, number = 1),
            track(5, "no artist", album = "One"),
            track(6, "other", artist = "band", album = "Another"),
        )
        assertEquals(
            listOf("other", "one", "two", "untagged", "second disc", "no artist"),
            songs.sortedBy(Sort.Artist).titles(),
        )
    }

    @Test
    fun album_puts_songs_without_an_album_last() {
        val songs = listOf(track(1, "loose"), track(2, "b2", album = "B", number = 2), track(3, "a", album = "a"), track(4, "b1", album = "B", number = 1))
        assertEquals(listOf("a", "b1", "b2", "loose"), songs.sortedBy(Sort.Album).titles())
    }

    @Test
    fun reads_keys_and_names_from_files() {
        assertEquals(Sort.Artist, Sort.fromKey("artist"))
        assertEquals(null, Sort.fromKey("size"))
        assertEquals("Medusa - 2026 Repented" to "Bring Me The Horizon", nameFromFile("Medusa - 2026 Repented - Bring Me The Horizon"))
        assertEquals("plain" to "", nameFromFile("plain"))
        assertEquals("- lead" to "", nameFromFile("- lead"))
    }
}
