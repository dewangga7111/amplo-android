package com.dewangga.amplo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dewangga.amplo.R
import com.dewangga.amplo.library.CoverRef
import com.dewangga.amplo.ui.theme.Amplo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Playlist covers already read, by their address, change time and size. */
private val playlistCovers = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Reads a cover at about [pixels] wide, cropped to a square at its center. */
private fun readCover(context: Context, cover: CoverRef, pixels: Int): ImageBitmap? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(cover.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= pixels) sample *= 2
    val bitmap = resolver.openInputStream(cover.uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    bitmap?.let {
        val side = minOf(it.width, it.height)
        Bitmap.createBitmap(it, (it.width - side) / 2, (it.height - side) / 2, side, side).asImageBitmap()
    }
} catch (_: Exception) {
    null
}

/** The picture a playlist has as its own cover. */
@Composable
fun PlaylistCoverImage(cover: CoverRef, size: Dp, corner: Dp) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val key = "${cover.uri}@${cover.modified}@$pixels"
    val image by produceState(playlistCovers.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { readCover(context, cover, pixels) }?.also { playlistCovers.put(key, it) }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Amplo.MiniLine),
        contentAlignment = Alignment.Center,
    ) {
        val picture = image
        if (picture != null) {
            Image(picture, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Icon(painterResource(R.drawable.ic_note), null, Modifier.size(minOf(size * 0.45f, 64.dp)), tint = Amplo.Muted)
        }
    }
}
