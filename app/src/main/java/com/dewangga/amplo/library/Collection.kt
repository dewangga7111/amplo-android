package com.dewangga.amplo.library

/** Names with a comma of their own, which [splitNames] keeps whole. */
private val namesWithCommas = listOf("Tyler, The Creator", "Earth, Wind & Fire", "Crosby, Stills, Nash & Young")

/**
 * Where each name is in an artist or genre tag that lists several, such as `Mili, Yamato Kasai`
 * or `Alternative; Indie Rock`. Names are split at ", " and "; " only, as "&" and "/" are part of
 * names such as `MYTH & ROID` and `Indie Rock/Rock pop`. The same as the desktop app.
 */
fun splitNames(text: String): List<IntRange> {
    val names = ArrayList<IntRange>()
    var start = 0
    while (start < text.length) {
        val rest = text.substring(start)
        val whole = namesWithCommas.firstOrNull { name ->
            rest.startsWith(name, ignoreCase = true) &&
                rest.substring(name.length).let { it.isEmpty() || it.startsWith(", ") || it.startsWith("; ") }
        }
        val length = whole?.length
            ?: listOf(", ", "; ").map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull()
            ?: rest.length
        val piece = rest.substring(0, length)
        val lead = piece.length - piece.trimStart().length
        val trimmed = piece.trim()
        if (trimmed.isNotEmpty()) names += (start + lead) until (start + lead + trimmed.length)
        // Both separators are two characters long.
        start += length + 2
    }
    return names
}

/** The names in an artist or genre tag. */
fun names(text: String): List<String> = splitNames(text).map { text.substring(it) }

/** Songs grouped by what their tags share. Opening one shows its songs as a list. */
sealed class Collection {
    abstract val name: String
    abstract val kind: ListKind

    /** An album name, and the album artist when the tags have one. */
    data class Album(override val name: String, val artist: String) : Collection() {
        override val kind get() = ListKind.Album
    }

    /** One of the artists of a song. */
    data class Artist(override val name: String) : Collection() {
        override val kind get() = ListKind.Artist
    }

    /** One of the genres of a song. */
    data class Genre(override val name: String) : Collection() {
        override val kind get() = ListKind.Genre
    }

    /** The key of its [SongList]. Names are kept as they are, so a key reads back to the same collection. */
    val key: String
        get() = when (this) {
            is Album -> "album:$artist\u0000$name"
            is Artist -> "artist:$name"
            is Genre -> "genre:$name"
        }

    /** Songs without an album artist match an album by its name alone, as many files lack one. */
    fun contains(track: Track): Boolean = when (this) {
        is Album -> track.album.equals(name, ignoreCase = true) &&
            (artist.isEmpty() || track.albumArtist.equals(artist, ignoreCase = true))
        is Artist -> names(track.artist).any { it.equals(name, ignoreCase = true) }
        is Genre -> names(track.genre).any { it.equals(name, ignoreCase = true) }
    }

    /**
     * The songs of the collection. An album is in the order of its discs and track numbers, with
     * songs without a number last, by title. An artist or a genre keeps the order of [tracks],
     * which follow the sort.
     */
    fun songs(tracks: List<Track>): List<Track> {
        val songs = tracks.filter(::contains)
        if (this !is Album) return songs
        return songs.sortedWith(
            compareBy<Track>({ if (it.disc == 0) 1 else it.disc }, { it.number == 0 }, { it.number })
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }

    /** The list to open, or null when no song is in it any more. [tracks] are All Songs in the order of the sort. */
    fun list(tracks: List<Track>): SongList? {
        val songs = songs(tracks)
        if (songs.isEmpty()) return null
        return SongList(key, name, kind, songs, collection = this)
    }

    companion object {
        /** The album of a song, or null for a song without an album name. */
        fun albumOf(track: Track): Album? = track.album.takeIf(String::isNotEmpty)?.let { Album(it, track.albumArtist) }

        fun fromKey(key: String): Collection? {
            val kind = key.substringBefore(':', "")
            val rest = key.substringAfter(':')
            return when (kind) {
                "album" -> Album(rest.substringAfter('\u0000'), rest.substringBefore('\u0000'))
                "artist" -> Artist(rest)
                "genre" -> Genre(rest)
                else -> null
            }
        }
    }
}
