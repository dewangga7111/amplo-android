package com.dewangga.amplo.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dewangga.amplo.R
import com.dewangga.amplo.library.Collection
import com.dewangga.amplo.library.Covers
import com.dewangga.amplo.library.splitNames
import com.dewangga.amplo.ui.theme.Amplo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads covers a few at a time, so a fast scroll does not queue up hundreds of files. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val coverReader = Dispatchers.IO.limitedParallelism(4)

/** The cover of a song, or a note on a plain square while it loads or when it has none. */
@Composable
fun Cover(trackId: Long?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val cover by produceState<ImageBitmap?>(null, trackId, pixels) {
        value = trackId?.let { id ->
            withContext(coverReader) { Covers.load(context, id, pixels)?.asImageBitmap() }
        }
    }
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Amplo.MiniLine),
        contentAlignment = Alignment.Center,
    ) {
        val picture = cover
        if (picture != null) {
            Image(picture, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            // A big cover gets a smaller note, so it does not look like a picture.
            Icon(painterResource(R.drawable.ic_note), null, Modifier.size(minOf(size * 0.45f, 64.dp)), tint = Amplo.Muted)
        }
    }
}

/** A rounded button with an icon and a label, filled with the accent or outlined. */
@Composable
fun PillButton(label: String, @DrawableRes icon: Int, accent: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val content = if (accent) Amplo.OnAccent else Amplo.Text
    Row(
        Modifier
            .height(40.dp)
            .clip(shape)
            .then(if (accent) Modifier.background(Amplo.Accent) else Modifier.border(1.dp, Amplo.PillLine, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = content)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** A round button with only an icon. [description] is read out by TalkBack. */
@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    filled: Boolean = false,
    tint: Color = Amplo.Text,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(Amplo.Accent) else Modifier)
            .clickable(interactionSource = null, indication = ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, Modifier.size(iconSize), tint = if (filled) Amplo.OnAccent else tint)
    }
}

/** "3:07", or "1:02:03" for an hour or longer. */
fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** "12 songs, 48 min", "1 song, 3 min", "1 song, 29 sec", or "0 songs". */
fun describeSongs(count: Int, totalMs: Long): String {
    val songs = if (count == 1) "1 song" else "$count songs"
    if (count == 0) return songs
    val minutes = totalMs / 60_000
    val length = when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes > 0 -> "$minutes min"
        else -> "${totalMs / 1000} sec"
    }
    return "$songs, $length"
}

/** Names that open a list look like the text around them, and take the accent while pressed. */
private val linkStyles = TextLinkStyles(pressedStyle = SpanStyle(color = Amplo.Accent))

/** [text] with each name in it, as an artist or genre tag lists them, as a link to [collection] of that name. */
fun nameLinks(text: String, collection: (String) -> Collection, onOpen: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (range in splitNames(text)) {
            val name = text.substring(range)
            addLink(LinkAnnotation.Clickable(name, linkStyles) { onOpen(collection(name).key) }, range.first, range.last + 1)
        }
    }

/** An artist tag in which each artist opens their songs. */
fun artistLinks(text: String, onOpen: (String) -> Unit): AnnotatedString = nameLinks(text, Collection::Artist, onOpen)
