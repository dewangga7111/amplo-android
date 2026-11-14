package com.dewangga.amplo.library

/**
 * Reads and changes `.m3u` playlists as text, the same way as the desktop app. Entries are paths,
 * relative to the folder of the playlist or full, with either slash.
 */
object M3u {
    /** Characters Windows does not allow in a file name, so the desktop app can read the playlist. */
    private val notInFileNames = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    /** A path in one form for comparing: forward slashes, no "." or "..", and lowercase. */
    fun pathKey(path: String): String {
        val parts = ArrayList<String>()
        for (part in path.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += part.lowercase()
            }
        }
        return parts.joinToString("/")
    }

    /** True for "/storage/…", "C:\…" and "\\server\…". */
    fun isAbsolute(entry: String): Boolean =
        entry.startsWith('/') || entry.startsWith('\\') || (entry.length > 2 && entry[1] == ':' && entry[0].isLetter())

    /** The entries of a playlist as they are written, without comments and empty lines. */
    fun entries(text: String): List<String> = text.removePrefix("\uFEFF")
        .lines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }

    /** The key of the song an entry points to. [folder] is the folder of the playlist. */
    fun resolve(folder: String, entry: String): String = pathKey(if (isAbsolute(entry)) entry else "$folder/$entry")

    /**
     * How a song is written in a playlist: relative to the folder of the playlist when both are in
     * the same folder of the storage, such as Music, so the playlist also works on a computer that
     * has a copy of that folder. A full path otherwise.
     */
    fun entryFor(folder: String, track: String): String {
        val from = folder.split('/').filter(String::isNotEmpty)
        val to = track.split('/').filter(String::isNotEmpty)
        var shared = 0
        while (shared < from.size && shared < to.size - 1 && from[shared].equals(to[shared], ignoreCase = true)) shared++
        if (shared <= storageRootLength(to)) return track
        return (List(from.size - shared) { ".." } + to.drop(shared)).joinToString("/")
    }

    /** The parts of "/storage/emulated/0" or "/storage/1234-ABCD" that name the storage itself. */
    private fun storageRootLength(parts: List<String>): Int = when {
        parts.firstOrNull() != "storage" -> 0
        parts.getOrNull(1) == "emulated" -> 3
        else -> 2
    }

    /** The text of a new playlist with [tracks] in it. */
    fun create(folder: String, tracks: List<String>): String = buildString {
        append("#EXTM3U\r\n")
        for (track in tracks) append(entryFor(folder, track)).append("\r\n")
    }

    /**
     * Adds [tracks] to the end of a playlist. Songs it has already are left out. Returns the new
     * text and how many songs it gained. [identify] turns a path key into what tells songs apart,
     * so an entry with the path of another computer can count as the song it points to.
     */
    fun add(text: String, folder: String, tracks: List<String>, identify: (String) -> String = { it }): Pair<String, Int> {
        val known = entries(text).mapTo(HashSet()) { identify(resolve(folder, it)) }
        val newline = if (text.contains("\r\n") || text.isEmpty()) "\r\n" else "\n"
        val added = StringBuilder()
        var count = 0
        for (track in tracks) {
            if (known.add(identify(pathKey(track)))) {
                added.append(entryFor(folder, track)).append(newline)
                count++
            }
        }
        if (count == 0) return text to 0
        val start = when {
            text.isEmpty() -> "#EXTM3U$newline"
            !text.endsWith('\n') -> newline
            else -> ""
        }
        return text + start + added to count
    }

    /**
     * Takes [tracks] out of a playlist, each `#EXTINF` line about them too. Everything else stays
     * as it was. Returns the new text and how many entries went. [identify] works as for [add].
     */
    fun remove(text: String, folder: String, tracks: List<String>, identify: (String) -> String = { it }): Pair<String, Int> {
        val unwanted = tracks.mapTo(HashSet()) { identify(pathKey(it)) }
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val kept = ArrayList<String>()
        var removed = 0
        for (line in text.lines()) {
            val entry = line.trim().removePrefix("\uFEFF")
            if (entry.isNotEmpty() && !entry.startsWith('#') && identify(resolve(folder, entry)) in unwanted) {
                removed++
                // An #EXTINF line describes the entry after it.
                if (kept.lastOrNull()?.trim()?.uppercase()?.startsWith("#EXTINF") == true) kept.removeAt(kept.size - 1)
                continue
            }
            kept += line
        }
        if (removed == 0) return text to 0
        // `lines` gives an empty last line after a final newline, so joining puts it back.
        return kept.joinToString(newline) { it.removeSuffix("\r") } to removed
    }

    /** The name a playlist shows, without the symbols around names such as `!#SamsungMusic_favorites#!`. */
    fun displayName(fileName: String): String {
        val stem = fileName.substringBeforeLast('.')
        val name = stem.trim { !it.isLetterOrDigit() }
        return name.ifEmpty { stem }
    }

    /** The name as Windows allows it in a file name, or null when nothing is left. */
    fun fileNameFor(name: String): String? {
        val allowed = name.filter { !it.isISOControl() && it !in notInFileNames }
        // Windows drops dots and spaces at the end of a file name.
        val fileName = allowed.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ").trimEnd('.').trimEnd()
        return fileName.ifEmpty { null }
    }
}

/**
 * Finds the songs that playlist entries point to. A playlist made on a computer has the paths of
 * that computer, so an entry that is not found by its path matches the song whose path ends the
 * same way, such as "Artist/Album/song.flac".
 */
class TrackIndex(tracks: List<Track>) {
    private val byKey = HashMap<String, Long>()
    private val byName = HashMap<String, MutableList<Pair<List<String>, Long>>>()

    init {
        for (track in tracks) {
            if (track.path.isEmpty()) continue
            val key = M3u.pathKey(track.path)
            byKey[key] = track.id
            val parts = key.split('/')
            byName.getOrPut(parts.last()) { ArrayList() } += parts to track.id
        }
    }

    /** What tells the song at [key] apart, for [M3u.add] and [M3u.remove]: its id when found, or the key. */
    fun identify(key: String): String = find(key)?.let { "#$it" } ?: key

    /** The id of the song at [key], a key from [M3u.resolve], or null. */
    fun find(key: String): Long? {
        byKey[key]?.let { return it }
        val parts = key.split('/')
        val candidates = byName[parts.last()] ?: return null
        var best: Long? = null
        var bestLength = 0
        var tie = false
        for ((path, id) in candidates) {
            var length = 0
            while (length < parts.size && length < path.size && parts[parts.size - 1 - length] == path[path.size - 1 - length]) length++
            when {
                length > bestLength -> {
                    best = id
                    bestLength = length
                    tie = false
                }
                length == bestLength -> tie = true
            }
        }
        // Two songs that end the same way are too unsure to pick from.
        return if (tie) null else best
    }
}
