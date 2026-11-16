package com.dewangga.amplo.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** A playlist's own cover. [modified] changes when the picture does, so it loads again. */
data class CoverRef(val uri: Uri, val modified: Long)

/**
 * Android lets amplo change files beside the songs only in a folder the user picked, here the
 * folder of [path].
 */
class NeedsFolderAccess(val path: String) : Exception()

/** The rules of playlist covers that need no Android, so they can be tested on the computer. */
object CoverNames {
    /** The kinds of picture a cover can be, in the order they are looked for. */
    val extensions = listOf("jpg", "jpeg", "png", "webp")

    /** The file name of a playlist without `.m3u` or `.m3u8`. */
    fun stem(path: String): String = path.substringAfterLast('/').substringBeforeLast('.')

    /** The cover among the [names] of the files in a playlist's folder, as the desktop app finds it. */
    fun pick(names: List<String>, stem: String): String? {
        val byLower = names.associateBy(String::lowercase)
        return extensions.firstNotNullOfOrNull { byLower["$stem.$it".lowercase()] }
    }

    /**
     * The id the storage of the phone gives a file in its document tree, such as
     * `primary:Music/Playlists` for `/storage/emulated/0/Music/Playlists`. Null for a path
     * outside it.
     */
    fun documentId(path: String, primaryRoot: String): String? {
        val root = primaryRoot.trimEnd('/')
        if (path == root || path.startsWith("$root/")) return "primary:" + path.removePrefix(root).trim('/')
        val parts = path.removePrefix("/storage/").split('/', limit = 2)
        if (!path.startsWith("/storage/") || parts[0].isEmpty() || parts[0] == "emulated" || parts[0] == "self") return null
        return parts[0] + ":" + parts.getOrElse(1) { "" }.trim('/')
    }

    /** Whether the document [id] is the folder [tree] or inside it. */
    fun isInside(id: String, tree: String): Boolean {
        val a = id.lowercase()
        val b = tree.lowercase().trimEnd('/')
        return a == b || a.startsWith("$b/") || (b.endsWith(":") && a.startsWith(b))
    }
}

/**
 * The covers of playlists: a picture beside the `.m3u` file with the same name, such as
 * `Road Trip.jpg`, the same as the desktop app uses. Android does not show such pictures to
 * an app that may only read music, so amplo reads them through a folder the user picked.
 */
object PlaylistCovers {
    private const val PROVIDER = "com.android.externalstorage.documents"
    /** New covers are made no larger than this, as on the desktop. */
    private const val SIZE = 1000

    private val primaryRoot: String get() = Environment.getExternalStorageDirectory().path

    /** Older Android lets amplo change any file in the storage directly. */
    @Suppress("DEPRECATION")
    private val legacy: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && Environment.isExternalStorageLegacy())

    /** The folders the user let amplo change, as document trees. */
    fun trees(context: Context): List<Uri> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && it.isWritePermission && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    /** Keeps the access to a folder the user picked, also after a restart. */
    fun keep(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** Whether the user let amplo change the folder of [path]. */
    fun mayChange(context: Context, path: String): Boolean =
        legacy || treeFor(trees(context), path.substringBeforeLast('/')) != null

    /** Whether amplo may not read the pictures in the folder of any of these playlists. */
    fun anyHidden(context: Context, paths: List<String>): Boolean {
        if (legacy) return false
        val trees = trees(context)
        return paths.any { treeFor(trees, it.substringBeforeLast('/')) == null }
    }

    /** The Music folder, where the folder picker starts to show covers. */
    fun musicUri(): Uri = DocumentsContract.buildDocumentUri(PROVIDER, "primary:${Environment.DIRECTORY_MUSIC}")

    /** The place to start the folder picker for the folder of [path]. */
    fun folderUri(path: String): Uri? =
        CoverNames.documentId(path.substringBeforeLast('/'), primaryRoot)?.let { DocumentsContract.buildDocumentUri(PROVIDER, it) }

    private fun treeFor(trees: List<Uri>, folder: String): Pair<Uri, String>? {
        val id = CoverNames.documentId(folder, primaryRoot) ?: return null
        val tree = trees.firstOrNull { tree ->
            tree.authority == PROVIDER && CoverNames.isInside(id, DocumentsContract.getTreeDocumentId(tree))
        } ?: return null
        return tree to id
    }

    /** A file in a folder the user picked. */
    private class Child(val uri: Uri, val name: String, val modified: Long)

    /** Finds the covers of playlists. Each folder is listed once. */
    class Finder(private val context: Context) {
        private val trees = trees(context)
        private val folders = HashMap<String, List<Child>?>()

        fun find(path: String): CoverRef? {
            val folder = path.substringBeforeLast('/')
            val stem = CoverNames.stem(path)
            // Older Android shows every file to an app that may read the storage.
            for (extension in CoverNames.extensions) {
                val file = File(folder, "$stem.$extension")
                val readable = try {
                    FileInputStream(file).close()
                    true
                } catch (_: Exception) {
                    false
                }
                if (readable) return CoverRef(Uri.fromFile(file), file.lastModified())
            }
            val children = folders.getOrPut(folder.lowercase()) { list(context, trees, folder) } ?: return null
            val name = CoverNames.pick(children.map(Child::name), stem) ?: return null
            val child = children.first { it.name.equals(name, ignoreCase = true) }
            return CoverRef(child.uri, child.modified)
        }
    }

    private fun list(context: Context, trees: List<Uri>, folder: String): List<Child>? {
        val (tree, id) = treeFor(trees, folder) ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        return try {
            context.contentResolver.query(
                children,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        add(Child(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), name, cursor.getLong(2)))
                    }
                }
            }
        } catch (_: Exception) {
            // The folder was moved or the access was taken back.
            null
        }
    }

    /**
     * Makes the picture at [source] into a cover: cropped to a square at its center, no
     * larger than 1000 pixels, as a JPEG.
     */
    fun makeCover(context: Context, source: Uri): ByteArray {
        val picture = try {
            decode(context, source)
        } catch (error: Exception) {
            throw IllegalArgumentException("This picture cannot be read.", error)
        } ?: throw IllegalArgumentException("This picture cannot be read.")
        val side = minOf(picture.width, picture.height)
        val square = Bitmap.createBitmap(picture, (picture.width - side) / 2, (picture.height - side) / 2, side, side)
        val size = minOf(side, SIZE)
        val scaled = if (size == side) square else Bitmap.createScaledBitmap(square, size, size, true)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    /** Reads a picture at about the size a cover needs, turned the way the camera held it. */
    private fun decode(context: Context, source: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                val side = minOf(info.size.width, info.size.height)
                var sample = 1
                while (side / (sample * 2) >= SIZE) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val side = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (side / (sample * 2) >= SIZE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /**
     * Saves [jpeg] as the cover of the playlist at [path] and deletes its other covers. Throws
     * [NeedsFolderAccess] until the user picked the folder.
     */
    fun write(context: Context, path: String, jpeg: ByteArray) {
        val folder = path.substringBeforeLast('/')
        val stem = CoverNames.stem(path)
        if (legacy) {
            val target = File(folder, "$stem.jpg")
            target.writeBytes(jpeg)
            CoverNames.extensions.filter { it != "jpg" }.forEach { File(folder, "$stem.$it").delete() }
            return
        }
        val trees = trees(context)
        val (tree, id) = treeFor(trees, folder) ?: throw NeedsFolderAccess(path)
        val children = list(context, trees, folder) ?: throw NeedsFolderAccess(path)
        val resolver = context.contentResolver
        val existing = children.firstOrNull { it.name.equals("$stem.jpg", ignoreCase = true) }
        val target = existing?.uri
            ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), "image/jpeg", "$stem.jpg")
            ?: throw IOException("Android did not make the cover file")
        resolver.openOutputStream(target, "wt")?.use { it.write(jpeg) } ?: throw IOException("The cover cannot be written")
        for (child in children) {
            val other = CoverNames.extensions.filter { it != "jpg" }.any { child.name.equals("$stem.$it", ignoreCase = true) }
            if (other) DocumentsContract.deleteDocument(resolver, child.uri)
        }
    }

    /** Deletes every cover of the playlist at [path]. Throws [NeedsFolderAccess] until the user picked the folder. */
    fun remove(context: Context, path: String) {
        val folder = path.substringBeforeLast('/')
        val stem = CoverNames.stem(path)
        if (legacy) {
            CoverNames.extensions.forEach { File(folder, "$stem.$it").delete() }
            return
        }
        val children = list(context, trees(context), folder) ?: throw NeedsFolderAccess(path)
        for (child in children) {
            if (CoverNames.extensions.any { child.name.equals("$stem.$it", ignoreCase = true) }) {
                DocumentsContract.deleteDocument(context.contentResolver, child.uri)
            }
        }
    }
}
