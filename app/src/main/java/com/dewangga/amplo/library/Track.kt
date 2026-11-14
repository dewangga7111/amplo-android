package com.dewangga.amplo.library

/** One song of the device's music library. Names are empty when the file has none. */
data class Track(
    /** The id of the song in MediaStore. */
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    /** The disc number, or 0 when the file has none. */
    val disc: Int,
    /** The track number on its disc, or 0 when the file has none. */
    val number: Int,
    val durationMs: Long,
    /** When the file was added to the device, in seconds since 1970. */
    val added: Long,
    /** The average bitrate in kbit/s, or 0 when unknown. */
    val bitrate: Int = 0,
    /** The full path of the file, such as "/storage/emulated/0/Music/song.flac". Playlists point to songs by it. */
    val path: String = "",
)

/** The order of every song list except albums and the queue. */
enum class Sort(val label: String, val key: String) {
    RecentlyAdded("Recently added", "added"),
    Title("Title", "title"),
    Artist("Artist", "artist"),
    Album("Album", "album");

    companion object {
        fun fromKey(key: String?): Sort? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The songs in the order of [sort]. Recently added shows the newest files first. Equal songs
 * keep the order they had.
 */
fun List<Track>.sortedBy(sort: Sort): List<Track> {
    // Empty names go last.
    val name = compareBy<String> { it.isEmpty() }.then(String.CASE_INSENSITIVE_ORDER)
    val inAlbum = compareBy<Track>({ if (it.disc == 0) 1 else it.disc }, { it.number == 0 }, { it.number })
    val title = Comparator<Track> { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
    val comparator: Comparator<Track> = when (sort) {
        Sort.RecentlyAdded -> compareByDescending { it.added }
        Sort.Title -> title.then(compareBy(name) { it.artist })
        Sort.Artist -> compareBy(name, Track::artist).then(compareBy(name, Track::album)).then(inAlbum).then(title)
        Sort.Album -> compareBy(name, Track::album).then(inAlbum).then(title)
    }
    // `sortedWith` is stable, so equal songs keep their order.
    return sortedWith(comparator)
}

/**
 * Splits the name of a file without tags into its title and artist at the last " - ", as in
 * "Title - Artist". A name without the separator is all title.
 */
fun nameFromFile(name: String): Pair<String, String> {
    val at = name.lastIndexOf(" - ")
    if (at <= 0) return name.trim() to ""
    val artist = name.substring(at + 3).trim()
    return if (artist.isEmpty()) name.trim() to "" else name.substring(0, at).trim() to artist
}

/**
 * The songs of a playlist file in the order of [sort]. The songs added to a playlist last are at
 * its end, so they come first as the recently added ones.
 */
fun List<Track>.sortedAsPlaylist(sort: Sort): List<Track> =
    if (sort == Sort.RecentlyAdded) asReversed().toList() else sortedBy(sort)
