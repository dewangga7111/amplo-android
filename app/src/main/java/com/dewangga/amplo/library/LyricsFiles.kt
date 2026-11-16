package com.dewangga.amplo.library

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import java.io.File
import java.io.IOException

/**
 * The `.lrc` file beside each song, as the desktop app keeps lyrics. Android counts these files as
 * subtitles, so amplo may read them with the permission to read music.
 */
object LyricsFiles {
    private const val MIME_TYPE = "application/lrc"
    private val files: Uri = MediaStore.Files.getContentUri("external")

    /** "/…/song.flac" gives "/…/song.lrc". */
    fun pathFor(track: Track): String? {
        if (track.path.isEmpty()) return null
        val name = track.path.substringAfterLast('/')
        val stem = if (name.contains('.')) name.substringBeforeLast('.') else name
        return track.path.substringBeforeLast('/') + "/" + stem + ".lrc"
    }

    /** The text of the lyrics file of [track], or null when it has none. */
    fun readText(context: Context, track: Track): String? {
        val path = pathFor(track) ?: return null
        val bytes = try {
            File(path).takeIf(File::isFile)?.readBytes()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: id(context, path)?.let { id ->
            // Some versions of Android only let amplo read the file through MediaStore.
            try {
                context.contentResolver.openInputStream(ContentUris.withAppendedId(files, id))?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        } ?: return null
        // Text that is not UTF-8 still shows, with the odd characters replaced.
        return String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }

    /**
     * The lyrics of [track]: its lyrics file, or else the lyrics in its tags. The file comes
     * first, as amplo saves the lyrics the user writes there.
     */
    fun read(context: Context, track: Track): Lyrics? =
        readText(context, track)?.let(Lyrics::parse) ?: EmbeddedLyrics.read(context, track.id)?.let(Lyrics::parse)

    /** The text to edit for the lyrics of [track]: its lyrics file, or else the lyrics in its tags. */
    fun editableText(context: Context, track: Track): String? =
        readText(context, track) ?: EmbeddedLyrics.read(context, track.id)

    /**
     * Saves [text] as the lyrics of [track], with Windows line ends as the desktop app writes them.
     * Throws [NeedsConsent] when the file came from another app or a computer and Android wants
     * the user to allow the change, and [IllegalArgumentException] with a message for the user.
     */
    fun write(context: Context, track: Track, text: String) {
        val path = pathFor(track) ?: throw IllegalArgumentException("This song has no file to keep lyrics beside.")
        Lyrics.parse(text) ?: throw IllegalArgumentException("These lyrics have no lines.")
        var body = text.removePrefix("﻿").replace("\r\n", "\n").replace("\n", "\r\n")
        if (!body.endsWith("\r\n")) body += "\r\n"
        val bytes = body.toByteArray()

        val file = File(path)
        // amplo may write a file it made, or any file before Android 11.
        try {
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(MIME_TYPE), null)
            return
        } catch (error: IOException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw error
        } catch (error: SecurityException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw error
        }
        val resolver = context.contentResolver
        val id = id(context, path)
        if (id == null) {
            // A new file, where Android only lets amplo make it through MediaStore.
            val values = ContentValues().apply {
                put(FileColumns.DISPLAY_NAME, file.name)
                put(FileColumns.MIME_TYPE, MIME_TYPE)
                put(FileColumns.RELATIVE_PATH, relativePath(file.parent.orEmpty()))
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("Android did not make the lyrics file")
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException("The lyrics cannot be written")
            return
        }
        val uri = ContentUris.withAppendedId(files, id)
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException("The lyrics cannot be written")
        } catch (_: SecurityException) {
            throw NeedsConsent(MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender)
        }
    }

    /** The MediaStore id of the file at [path]. */
    private fun id(context: Context, path: String): Long? =
        context.contentResolver.query(files, arrayOf(FileColumns._ID), "${FileColumns.DATA} = ?", arrayOf(path), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null }

    /** "/storage/emulated/0/Music/Band" gives "Music/Band/". */
    @Suppress("DEPRECATION")
    private fun relativePath(folder: String): String {
        val root = Environment.getExternalStorageDirectory().path
        return folder.removePrefix(root).trim('/') + "/"
    }
}
