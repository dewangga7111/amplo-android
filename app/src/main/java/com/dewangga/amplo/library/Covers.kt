package com.dewangga.amplo.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size

/** Loads the pictures embedded in songs, scaled to a size, and keeps the recent ones. */
object Covers {
    private class Key(val id: Long, val size: Int) {
        override fun equals(other: Any?) = other is Key && other.id == id && other.size == size
        override fun hashCode() = id.hashCode() * 31 + size
    }

    private val cache = object : LruCache<Key, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
    }

    /** Songs known to have no picture, so they are not read again. */
    private val missing = LruCache<Key, Unit>(2048)

    /** The cover of the song at most [size] pixels wide, or null when it has none. Reads the file. */
    fun load(context: Context, id: Long, size: Int): Bitmap? {
        val key = Key(id, size)
        cache.get(key)?.let { return it }
        if (missing.get(key) != null) return null
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(songUri(id), Size(size, size), null)
            } else {
                embedded(context, id, size)
            }
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) missing.put(key, Unit) else cache.put(key, bitmap)
        return bitmap
    }

    private fun embedded(context: Context, id: Long, size: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, songUri(id))
            val data = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            retriever.release()
        }
    }
}
