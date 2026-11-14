package com.dewangga.amplo.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media

/** The address of a song for the player and for its cover. */
fun songUri(id: Long): Uri = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id)

/** Reads every song of the device from MediaStore. Ringtones, alarms and notifications are left out. */
object MediaStoreScanner {
    fun scan(context: Context): List<Track> {
        val newer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val columns = buildList {
            add(Media._ID)
            add(Media.TITLE)
            add(Media.ARTIST)
            add(Media.ALBUM)
            add(Media.DISPLAY_NAME)
            add(Media.TRACK)
            add(Media.DURATION)
            add(Media.DATE_ADDED)
            add(Media.SIZE)
            if (newer) {
                add(Media.ALBUM_ARTIST)
                add(Media.GENRE)
                add(Media.CD_TRACK_NUMBER)
                add(Media.DISC_NUMBER)
                add(Media.BITRATE)
            }
        }
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(
            Media.EXTERNAL_CONTENT_URI,
            columns.toTypedArray(),
            "${Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { cursor ->
            fun column(name: String) = cursor.getColumnIndexOrThrow(name)
            fun text(index: Int) = known(cursor.getString(index))
            val id = column(Media._ID)
            val title = column(Media.TITLE)
            val artist = column(Media.ARTIST)
            val album = column(Media.ALBUM)
            val file = column(Media.DISPLAY_NAME)
            val track = column(Media.TRACK)
            val duration = column(Media.DURATION)
            val added = column(Media.DATE_ADDED)
            val albumArtist = if (newer) column(Media.ALBUM_ARTIST) else -1
            val genre = if (newer) column(Media.GENRE) else -1
            val cdTrack = if (newer) column(Media.CD_TRACK_NUMBER) else -1
            val disc = if (newer) column(Media.DISC_NUMBER) else -1
            val size = column(Media.SIZE)
            val bitrate = if (newer) column(Media.BITRATE) else -1
            while (cursor.moveToNext()) {
                val fileName = cursor.getString(file).orEmpty().substringBeforeLast('.')
                var songTitle = text(title)
                var songArtist = text(artist)
                // MediaStore names a song without tags after its file.
                if (songArtist.isEmpty() && (songTitle.isEmpty() || songTitle == fileName)) {
                    val (fromTitle, fromArtist) = nameFromFile(fileName)
                    songTitle = fromTitle
                    songArtist = fromArtist
                }
                // Before Android 11, TRACK holds the disc and the track as disc * 1000 + track.
                val legacy = cursor.getInt(track)
                val (discNumber, trackNumber) = if (newer) {
                    leadingNumber(cursor.getString(disc)) to leadingNumber(cursor.getString(cdTrack))
                } else {
                    legacy / 1000 to legacy % 1000
                }
                tracks += Track(
                    id = cursor.getLong(id),
                    title = songTitle,
                    artist = songArtist,
                    album = text(album),
                    albumArtist = if (albumArtist >= 0) text(albumArtist) else "",
                    genre = if (genre >= 0) text(genre) else "",
                    disc = discNumber,
                    number = trackNumber,
                    durationMs = cursor.getLong(duration),
                    added = cursor.getLong(added),
                    bitrate = averageBitrate(
                        if (bitrate >= 0) cursor.getLong(bitrate) else 0,
                        cursor.getLong(size),
                        cursor.getLong(duration),
                    ),
                )
            }
        }
        return tracks
    }

    /** The bitrate from the tags in kbit/s. Without it, the file size and length give an average. */
    private fun averageBitrate(tagged: Long, bytes: Long, durationMs: Long): Int = when {
        tagged > 0 -> (tagged / 1000).toInt()
        bytes > 0 && durationMs > 0 -> (bytes * 8 / durationMs).toInt()
        else -> 0
    }

    /** MediaStore fills missing names with "<unknown>". */
    private fun known(text: String?): String = text?.trim()?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty()

    /** The number at the start of a text such as "3" or "3/12", or 0. */
    private fun leadingNumber(text: String?): Int = text?.trim()?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
}
