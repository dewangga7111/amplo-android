package com.dewangga.amplo.spotify

/** Lowercase letters and digits, with single spaces between words. */
fun normalize(text: String): String {
    val normalized = StringBuilder(text.length)
    for (character in text.lowercase()) {
        when {
            character.isLetterOrDigit() -> normalized.append(character)
            character == '&' -> normalized.append(" and ")
            else -> normalized.append(' ')
        }
    }
    return normalized.split(' ').filter(String::isNotEmpty).joinToString(" ")
}

private val VERSION_MARKERS = listOf(" - ", " feat. ", " feat ", " ft. ", " featuring ")

/**
 * The title without what versions add to it: `Song (Live)`, `Song [2011 Remaster]`,
 * `Song - Radio Edit`, and `Song feat. Someone` all give `song`.
 */
fun baseTitle(title: String): String {
    val plain = StringBuilder(title.length)
    var depth = 0
    for (character in title) {
        when {
            character == '(' || character == '[' -> depth++
            character == ')' || character == ']' -> depth = (depth - 1).coerceAtLeast(0)
            depth == 0 -> plain.append(character)
        }
    }
    val lower = plain.toString().lowercase()
    val cut = VERSION_MARKERS.map(lower::indexOf).filter { it >= 0 }.minOrNull() ?: lower.length
    val base = normalize(lower.substring(0, cut))
    return base.ifEmpty { normalize(title) }
}

private fun containsWords(text: String, words: String) = " $text ".contains(" $words ")

/** Finds songs of the local library by title and artist, the way they would be written on Spotify. */
class LocalIndex(songs: List<Pair<String, String>>) {
    /** Base titles, with the normalized artist and the position of each local song. */
    private val titles = HashMap<String, MutableList<Pair<String, Int>>>()

    init {
        songs.forEachIndexed { index, (title, artist) ->
            titles.getOrPut(baseTitle(title)) { ArrayList() } += normalize(artist) to index
        }
    }

    /**
     * The position of the local song that is this Spotify song. A local song without an artist
     * matches by its title alone. Otherwise one of the Spotify artists has to be among its artists.
     */
    fun find(track: SpotifyTrack): Int? {
        val candidates = titles[baseTitle(track.name)] ?: return null
        val artists = track.artists.map(::normalize).filter(String::isNotEmpty)
        return candidates.firstOrNull { (local, _) ->
            local.isEmpty() || artists.any { containsWords(local, it) || containsWords(it, local) }
        }?.second
    }
}
