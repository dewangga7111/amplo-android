package com.dewangga.amplo.library

/** What kind of list a [SongList] is, with the label above its name. */
enum class ListKind(val label: String) {
    All("SONGS"),
    Liked("LIKED"),
    Playlist("PLAYLIST"),
    Album("ALBUM"),
    Artist("ARTIST"),
    Genre("GENRE"),
}

/**
 * A list of songs to open and play: All Songs, Liked Songs, a playlist, or an album, an artist or
 * a genre opened from a song.
 */
data class SongList(
    /** Stays the same while the app runs, to open the list again. */
    val key: String,
    val name: String,
    val kind: ListKind,
    val songs: List<Track>,
    val playlist: Playlist? = null,
    val collection: Collection? = null,
) {
    /** The word after "PLAYING FROM" in Now Playing, as on the desktop. */
    val sourceKind: String get() = collection?.kind?.label ?: ListKind.Playlist.label

    companion object {
        const val ALL = "all"
        const val LIKED = "liked"

        fun playlistKey(path: String) = "playlist:" + M3u.pathKey(path)
    }
}

/**
 * The list with [key]: one of [lists], or an album, an artist or a genre made from All Songs.
 * Null when it has gone, such as a playlist that was deleted.
 */
fun findList(key: String, lists: List<SongList>): SongList? =
    lists.firstOrNull { it.key == key }
        ?: Collection.fromKey(key)?.list(lists.firstOrNull { it.key == SongList.ALL }?.songs.orEmpty())

/** How Liked Songs remembers a song, so a song keeps its heart after MediaStore reads it again. */
val Track.likeKey: String get() = if (path.isEmpty()) "id:$id" else M3u.pathKey(path)
