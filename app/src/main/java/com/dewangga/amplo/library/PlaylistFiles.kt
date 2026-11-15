package com.dewangga.amplo.library

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import java.io.File
import java.io.IOException

/** A playlist file on the device. [tracks] are the songs found, in the order of the file. */
data class Playlist(val id: Long, val name: String, val path: String, val tracks: List<Long>) {
    val folder: String get() = path.substringBeforeLast('/')
}

/** Android asks the user before amplo may change a file that another app or a computer put there. */
class NeedsConsent(val request: IntentSender) : Exception()

/**
 * The `.m3u` playlists of the device, as the desktop app keeps them in the music folder. New ones
 * go into Music/Playlists.
 */
@Suppress("DEPRECATION")
object PlaylistFiles {
    private const val MIME_TYPE = "audio/x-mpegurl"
    private val collection: Uri = MediaStore.Files.getContentUri("external")

    /** Where new playlists go. */
    val newFolder: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Playlists")

    private fun uri(id: Long): Uri = ContentUris.withAppendedId(collection, id)

    /**
     * Reads every playlist, sorted by name. A new playlist has no songs yet. One whose songs are
     * all missing stays hidden. Also returns how many entries point to songs that are not found.
     */
    fun scan(context: Context, tracks: List<Track>): Pair<List<Playlist>, Int> {
        val index = TrackIndex(tracks)
        val playlists = ArrayList<Playlist>()
        var missing = 0
        val selection = "${FileColumns.MEDIA_TYPE} = ${FileColumns.MEDIA_TYPE_PLAYLIST}" +
            " OR lower(${FileColumns.DISPLAY_NAME}) LIKE '%.m3u' OR lower(${FileColumns.DISPLAY_NAME}) LIKE '%.m3u8'"
        context.contentResolver.query(
            collection,
            arrayOf(FileColumns._ID, FileColumns.DATA, FileColumns.DISPLAY_NAME),
            selection,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val path = cursor.getString(1) ?: continue
                val fileName = cursor.getString(2) ?: path.substringAfterLast('/')
                if (!fileName.endsWith(".m3u", true) && !fileName.endsWith(".m3u8", true)) continue
                val text = try {
                    read(context, id)
                } catch (_: Exception) {
                    continue
                }
                val folder = path.substringBeforeLast('/')
                val entries = M3u.entries(text)
                val found = entries.mapNotNull { index.find(M3u.resolve(folder, it)) }
                missing += entries.size - found.size
                if (entries.isEmpty() || found.isNotEmpty()) {
                    playlists += Playlist(id, M3u.displayName(fileName), path, found)
                }
            }
        }
        playlists.sortBy { it.name.lowercase() }
        return playlists to missing
    }

    fun read(context: Context, id: Long): String {
        val stream = context.contentResolver.openInputStream(uri(id)) ?: throw IOException("The playlist cannot be read")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /** Replaces the text of a playlist. Throws [NeedsConsent] when Android wants the user to allow it first. */
    fun write(context: Context, playlist: Playlist, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File(playlist.path).writeText(text)
            scanFile(context, playlist.path)
            return
        }
        // Android grants changes to a playlist by its address among the playlists, not among all files.
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, playlist.id)
        try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("The playlist cannot be changed")
            stream.use { it.write(text.toByteArray()) }
        } catch (error: SecurityException) {
            val request = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
                error is RecoverableSecurityException -> error.userAction.actionIntent.intentSender
                else -> throw error
            }
            throw NeedsConsent(request)
        }
    }

    /**
     * Writes a new playlist named [name] into Music/Playlists with [tracks] in it. Returns its
     * path, or throws [IllegalArgumentException] with a message for the user.
     */
    fun create(context: Context, name: String, tracks: List<Track>): String {
        val fileName = M3u.fileNameFor(name) ?: throw IllegalArgumentException("Type a name for the playlist.")
        val folder = newFolder
        val file = File(folder, "$fileName.m3u")
        if (exists(context, file)) throw IllegalArgumentException("A playlist named “$fileName” already exists.")
        val text = M3u.create(folder.path, tracks.map(Track::path))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            folder.mkdirs()
            file.writeText(text)
            scanFile(context, file.path)
            return file.path
        }
        val values = ContentValues().apply {
            put(FileColumns.DISPLAY_NAME, file.name)
            put(FileColumns.MIME_TYPE, MIME_TYPE)
            put(FileColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Playlists/")
            put(FileColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Android did not make the playlist file")
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("The playlist cannot be written")
            resolver.update(uri, ContentValues().apply { put(FileColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return file.path
    }

    /**
     * Like [create], but a name that is taken gets a number: `Mix`, then `Mix 2`, `Mix 3`, and so
     * on. [taken] holds the names of the playlists the library shows, which can live in other
     * folders.
     */
    fun createNumbered(context: Context, name: String, tracks: List<Track>, taken: List<String>): String {
        val fileName = M3u.fileNameFor(name) ?: throw IllegalArgumentException("Type a name for the playlist.")
        val shown = taken.map(String::lowercase).toSet()
        for (number in 1 until 1000) {
            val candidate = if (number == 1) fileName else "$fileName $number"
            if (M3u.displayName("$candidate.m3u").lowercase() in shown) continue
            if (exists(context, File(newFolder, "$candidate.m3u")) || exists(context, File(newFolder, "$candidate.m3u8"))) continue
            return create(context, candidate, tracks)
        }
        throw IllegalArgumentException("Too many playlists are named “$fileName”.")
    }

    /** MediaStore knows the files that amplo cannot see directly on newer Android. */
    private fun exists(context: Context, file: File): Boolean {
        if (file.exists()) return true
        context.contentResolver.query(
            collection,
            arrayOf(FileColumns._ID),
            "lower(${FileColumns.DATA}) = ?",
            arrayOf(file.path.lowercase()),
            null,
        )?.use { return it.count > 0 }
        return false
    }

    private fun scanFile(context: Context, path: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(MIME_TYPE), null)
    }
}
