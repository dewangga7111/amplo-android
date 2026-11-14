package com.dewangga.amplo.library

/** What kind of list a [SongList] is, with the label above its name. */
enum class ListKind(val label: String) {
    All("SONGS"),
    Liked("LIKED"),
    Playlist("PLAYLIST"),
}

/** A list of songs to open and play: All Songs, Liked Songs or a playlist. */
data class SongList(
    /** Stays the same while the app runs, to open the list again. */
    val key: String,
    val name: String,
    val kind: ListKind,
    val songs: List<Track>,
    val playlist: Playlist? = null,
) {
    companion object {
        const val ALL = "all"
        const val LIKED = "liked"

        fun playlistKey(path: String) = "playlist:" + M3u.pathKey(path)
    }
}

/** How Liked Songs remembers a song, so a song keeps its heart after MediaStore reads it again. */
val Track.likeKey: String get() = if (path.isEmpty()) "id:$id" else M3u.pathKey(path)
