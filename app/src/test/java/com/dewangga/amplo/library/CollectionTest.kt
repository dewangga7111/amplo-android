package com.dewangga.amplo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionTest {
    private fun track(
        id: Long,
        title: String,
        artist: String = "",
        album: String = "",
        albumArtist: String = "",
        genre: String = "",
        disc: Int = 0,
        number: Int = 0,
    ) = Track(id, title, artist, album, albumArtist, genre, disc, number, 1000, id)

    @Test
    fun splits_artist_and_genre_tags_like_the_desktop() {
        assertEquals(listOf("Mili", "Yamato Kasai"), names("Mili, Yamato Kasai"))
        assertEquals(listOf("Alternative", "Indie Rock/Rock pop"), names("Alternative; Indie Rock/Rock pop"))
        assertEquals(listOf("MYTH & ROID"), names("MYTH & ROID"))
        assertEquals(listOf("Tyler, The Creator", "Kali Uchis"), names("Tyler, The Creator, Kali Uchis"))
        assertEquals(listOf("A", "B"), names(" A ,  B"))
        assertEquals(emptyList<String>(), names(""))
        // The places of the names, for links in the text.
        assertEquals(listOf(0..3, 6..17), splitNames("Mili, Yamato Kasai"))
    }

    @Test
    fun keys_read_back_to_the_same_collection() {
        for (collection in listOf(Collection.Album("Night", "Band"), Collection.Album("a:b", ""), Collection.Artist("AC:DC"), Collection.Genre("Pop"))) {
            assertEquals(collection, Collection.fromKey(collection.key))
        }
        assertNull(Collection.fromKey(SongList.ALL))
        assertNull(Collection.fromKey("playlist:storage/x.m3u"))
    }

    @Test
    fun an_album_is_in_disc_and_track_order() {
        val tracks = listOf(
            track(1, "b", album = "Night", disc = 2, number = 1),
            track(2, "z", album = "night", number = 2),
            track(3, "a", album = "Night"),
            track(4, "y", album = "Night", disc = 1, number = 1),
            track(5, "other", album = "Day"),
            track(6, "c", album = "Night", number = 0),
        )
        val album = Collection.albumOf(tracks[0])!!
        assertEquals(listOf(4L, 2L, 3L, 6L, 1L), album.songs(tracks).map(Track::id))
    }

    @Test
    fun an_album_artist_tells_albums_of_the_same_name_apart() {
        val tracks = listOf(
            track(1, "a", album = "Greatest Hits", albumArtist = "Queen"),
            track(2, "b", album = "Greatest Hits", albumArtist = "ABBA"),
            track(3, "c", album = "Greatest Hits"),
        )
        assertEquals(listOf(1L), Collection.albumOf(tracks[0])!!.songs(tracks).map(Track::id))
        // Without an album artist, the name alone counts.
        assertEquals(listOf(1L, 2L, 3L), Collection.albumOf(tracks[2])!!.songs(tracks).map(Track::id))
    }

    @Test
    fun an_artist_or_genre_has_each_song_that_names_them_in_the_order_given() {
        val tracks = listOf(
            track(1, "a", artist = "Mili, Yamato Kasai", genre = "Pop; Rock"),
            track(2, "b", artist = "mili", genre = "Indie Rock"),
            track(3, "c", artist = "Milian", genre = "rock"),
        )
        assertEquals(listOf(1L, 2L), Collection.Artist("Mili").songs(tracks).map(Track::id))
        assertEquals(listOf(1L, 3L), Collection.Genre("Rock").songs(tracks).map(Track::id))
        assertNull(Collection.Artist("Nobody").list(tracks))
    }

    @Test
    fun finds_lists_and_collections_by_key() {
        val tracks = listOf(track(1, "a", artist = "Mili", album = "Rose"), track(2, "b", artist = "Other"))
        val lists = listOf(SongList(SongList.ALL, "All Songs", ListKind.All, tracks))
        assertEquals(lists[0], findList(SongList.ALL, lists))
        val artist = findList(Collection.Artist("Mili").key, lists)!!
        assertEquals(ListKind.Artist, artist.kind)
        assertEquals("ARTIST", artist.sourceKind)
        assertEquals(listOf(1L), artist.songs.map(Track::id))
        assertTrue(findList("playlist:gone", lists) == null)
        assertEquals("PLAYLIST", lists[0].sourceKind)
    }
}
