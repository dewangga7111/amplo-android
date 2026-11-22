package com.dewangga.amplo.sync

import com.dewangga.amplo.library.M3u

/**
 * A song file on one side of a sync. [path] is relative to the music folder of that side, with
 * forward slashes, such as "Band/Album/song.flac".
 */
data class SyncSong(val path: String, val size: Long, val lyrics: Boolean = false)

/** A playlist on one side of a sync. [songs] are paths like [SyncSong.path], in the order of the file. */
data class SyncPlaylist(val name: String, val songs: List<String>)

/**
 * Songs to add to a playlist on one side, with paths of that side. [create] is true when that
 * side has no playlist of this name yet.
 */
data class PlaylistChange(val name: String, val songs: List<String>, val create: Boolean)

/**
 * What a sync copies. Nothing is deleted: each side gets the songs it does not have, and each
 * playlist gets the songs that the playlist of the same name has on the other side.
 */
data class SyncPlan(
    val toPhone: List<SyncSong>,
    val toComputer: List<SyncSong>,
    val phonePlaylists: List<PlaylistChange>,
    val computerPlaylists: List<PlaylistChange>,
) {
    val isEmpty: Boolean get() = toPhone.isEmpty() && toComputer.isEmpty() && phonePlaylists.isEmpty() && computerPlaylists.isEmpty()

    /** The names of the playlists that change on either side. */
    val playlistNames: Set<String>
        get() = (phonePlaylists + computerPlaylists).mapTo(LinkedHashSet()) { it.name.lowercase() }

    companion object {
        /**
         * Compares both sides. A song counts as on both sides when its path is the same. A song
         * in another folder also counts when no other song has its file name, or when a song of
         * that name has the same size, so a different folder layout does not copy songs twice.
         */
        fun make(
            computerSongs: List<SyncSong>,
            computerPlaylists: List<SyncPlaylist>,
            phoneSongs: List<SyncSong>,
            phonePlaylists: List<SyncPlaylist>,
        ): SyncPlan {
            val computer = computerSongs.distinctBy { key(it.path) }
            val phone = phoneSongs.distinctBy { key(it.path) }
            // Computer path to phone path, for the songs on both sides.
            val same = HashMap<String, String>()
            val phoneTaken = HashSet<String>()
            val phoneByKey = phone.associateBy { key(it.path) }
            for (song in computer) {
                val match = phoneByKey[key(song.path)] ?: continue
                same[song.path] = match.path
                phoneTaken += match.path
            }
            val computerLeft = computer.filter { it.path !in same }
            val computerByName = computerLeft.groupBy { name(it.path) }
            val phoneByName = phone.filter { it.path !in phoneTaken }.groupBy { name(it.path) }
            for (song in computerLeft) {
                val candidates = phoneByName[name(song.path)].orEmpty().filter { it.path !in phoneTaken }
                val alone = candidates.size == 1 && computerByName[name(song.path)]?.size == 1
                val match = if (alone) candidates.first() else candidates.firstOrNull { it.size == song.size }
                if (match != null) {
                    same[song.path] = match.path
                    phoneTaken += match.path
                }
            }
            val back = same.entries.associate { (computerPath, phonePath) -> phonePath to computerPath }
            fun onPhone(computerPath: String) = same[computerPath] ?: computerPath
            fun onComputer(phonePath: String) = back[phonePath] ?: phonePath

            val computerNames = computerPlaylists.associateBy { it.name.lowercase() }
            val phoneNames = phonePlaylists.associateBy { it.name.lowercase() }
            val phoneChanges = ArrayList<PlaylistChange>()
            val computerChanges = ArrayList<PlaylistChange>()
            for (name in LinkedHashSet(computerNames.keys + phoneNames.keys)) {
                val onComputerSide = computerNames[name]
                val onPhoneSide = phoneNames[name]
                additions(onComputerSide, onPhoneSide, ::onPhone)?.let(phoneChanges::add)
                additions(onPhoneSide, onComputerSide, ::onComputer)?.let(computerChanges::add)
            }
            return SyncPlan(
                toPhone = computer.filter { it.path !in same },
                toComputer = phone.filter { it.path !in phoneTaken },
                phonePlaylists = phoneChanges,
                computerPlaylists = computerChanges,
            )
        }

        /** What [target] needs from [source], with [translate] turning paths into those of the target side. */
        private fun additions(source: SyncPlaylist?, target: SyncPlaylist?, translate: (String) -> String): PlaylistChange? {
            source ?: return null
            val have = target?.songs.orEmpty().mapTo(HashSet(), ::key)
            val songs = source.songs.map(translate).filter { have.add(key(it)) }
            return when {
                target == null -> PlaylistChange(source.name, songs, create = true)
                songs.isNotEmpty() -> PlaylistChange(target.name, songs, create = false)
                else -> null
            }
        }

        private fun key(path: String) = M3u.pathKey(path)

        private fun name(path: String) = path.substringAfterLast('/').lowercase()

        /**
         * The path of a song of the phone as the computer keeps it: relative to the Music folder,
         * or to the storage for songs outside it. "/storage/emulated/0/Music/Band/a.flac" gives
         * "Band/a.flac", and "/storage/1234-ABCD/Download/a.mp3" gives "Download/a.mp3".
         */
        fun relativePath(path: String): String? {
            val parts = path.split('/').filter(String::isNotEmpty)
            if (parts.firstOrNull() != "storage" || parts.size < 3) return null
            var rest = parts.drop(if (parts[1] == "emulated") 3 else 2)
            if (rest.size > 1 && rest[0].equals("Music", ignoreCase = true)) rest = rest.drop(1)
            return rest.joinToString("/").ifEmpty { null }
        }
    }
}
