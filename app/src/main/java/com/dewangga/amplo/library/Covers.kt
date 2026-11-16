package com.dewangga.amplo.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache

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
        // Not loadThumbnail: for a song without a picture, Android gives the largest picture in
        // its folder, such as the cover of a playlist. The desktop app shows no picture then.
        val bitmap = try {
            embedded(context, id, size)
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
