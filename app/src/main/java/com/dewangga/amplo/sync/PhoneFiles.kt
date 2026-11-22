package com.dewangga.amplo.sync

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Saves the songs that a sync copies to the phone, in the Music folder. */
@Suppress("DEPRECATION")
object PhoneFiles {
    /** A song from a sync that is being written. [finish] gives its full path once it is complete. */
    interface NewSong {
        val output: OutputStream
        fun finish(): String
        fun abandon()
    }

    /**
     * Makes the file for a song at [relative] in the Music folder, such as "Band/a.flac". Other
     * apps see it only after [NewSong.finish]. A file that is there already stays as it is, and
     * Android gives the new one another name.
     */
    fun create(context: Context, relative: String): NewSong {
        val name = relative.substringAfterLast('/')
        val folder = relative.substringBeforeLast('/', "")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return createFile(context, folder, name)
        val values = ContentValues().apply {
            put(Media.DISPLAY_NAME, name)
            put(Media.MIME_TYPE, mimeType(name))
            put(Media.RELATIVE_PATH, listOf(Environment.DIRECTORY_MUSIC, folder).filter(String::isNotEmpty).joinToString("/") + "/")
            put(Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Android did not make the file for $name")
        val output = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException("The file for $name cannot be written")
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return object : NewSong {
            override val output = output

            override fun finish(): String {
                output.close()
                resolver.update(uri, ContentValues().apply { put(Media.IS_PENDING, 0) }, null, null)
                return path(context, uri) ?: "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)}/$relative"
            }

            override fun abandon() {
                try {
                    output.close()
                } catch (_: IOException) {
                }
                resolver.delete(uri, null, null)
            }
        }
    }

    /** Before Android 10, apps write into the Music folder as files. */
    private fun createFile(context: Context, folder: String, name: String): NewSong {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), folder)
        directory.mkdirs()
        var file = File(directory, name)
        var number = 2
        while (file.exists()) {
            file = File(directory, "${name.substringBeforeLast('.')} ($number).${name.substringAfterLast('.')}")
            number++
        }
        val part = File(directory, ".${file.name}.part")
        val output = part.outputStream()
        return object : NewSong {
            override val output = output

            override fun finish(): String {
                output.close()
                if (!part.renameTo(file)) throw IOException("Could not save ${file.name}")
                MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
                return file.path
            }

            override fun abandon() {
                try {
                    output.close()
                } catch (_: IOException) {
                }
                part.delete()
            }
        }
    }

    private fun path(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(Media.DATA), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** The type Android expects for the file name, so it keeps the extension. */
    internal fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        val known = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.takeIf { it.startsWith("audio/") }
        return known ?: when (extension) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/x-wav"
            else -> "audio/ogg"
        }
    }
}
