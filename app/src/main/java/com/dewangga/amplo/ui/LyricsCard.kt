package com.dewangga.amplo.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewangga.amplo.AppViewModel
import com.dewangga.amplo.R
import com.dewangga.amplo.library.Extra
import com.dewangga.amplo.library.LyricLine
import com.dewangga.amplo.library.Lyrics
import com.dewangga.amplo.library.Track
import com.dewangga.amplo.playback.PlayerState
import com.dewangga.amplo.ui.theme.Amplo
import com.dewangga.amplo.ui.theme.Inter
import com.dewangga.amplo.ui.theme.SpaceGrotesk
import kotlinx.coroutines.delay

/** After the user scrolls the lyrics, they stay where the user left them this long. */
private const val FOLLOW_AGAIN_MS = 3000L

private val lineStyle = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp)
private val plainStyle = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
/** Romaji and translations are smaller than the line. */
private val extraStyle = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 17.sp)

/**
 * The lyrics of the song that plays, in place of the cover. Synced lyrics follow the song with
 * the line sung now in the middle, and a tap on a line plays from there.
 */
@Composable
fun LyricsCard(model: AppViewModel, state: PlayerState, position: MutableLongState, onEdit: (Track) -> Unit) {
    val song by model.lyrics.collectAsStateWithLifecycle()
    val chosenExtra by model.lyricsExtra.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The lyrics of the song before stay out while the new ones are read.
    val current = song?.takeIf { it.track.id == state.trackId }
    val lyrics = current?.lyrics
    val extra = chosenExtra?.takeIf { lyrics?.has(it) == true }
    Column(
        Modifier
            .fillMaxSize()
            .background(Amplo.Panel, RoundedCornerShape(14.dp))
            .border(1.dp, Amplo.Line, RoundedCornerShape(14.dp))
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LYRICS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Amplo.Muted)
            if (lyrics != null) {
                if (lyrics.extraChoices.size > 1) {
                    HeaderLink(extra?.label ?: "Original", "Change what shows under each line", on = extra != null) {
                        model.cycleLyricsExtra(lyrics)
                    }
                } else if (lyrics.synced) {
                    Text("SYNCED", Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelSmall, color = Amplo.Accent)
                }
                HeaderLink("Edit", "Edit lyrics", on = false) { onEdit(current.track) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(end = 10.dp)) {
            when {
                current == null -> {}
                lyrics == null -> NoLyrics(
                    onPaste = {
                        val text = clipboardText(context)
                        if (text.isNullOrBlank()) {
                            Toast.makeText(context, "Copy the lyrics first, then paste them here.", Toast.LENGTH_SHORT).show()
                        } else {
                            model.saveLyrics(current.track, text) { error ->
                                if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onWrite = { onEdit(current.track) },
                )
                lyrics.synced -> SyncedLines(lyrics, extra, position) { line ->
                    position.longValue = line.timeMs
                    model.player.seekTo(line.timeMs)
                }
                else -> PlainLines(lyrics, extra)
            }
        }
    }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/** A small text button in the header of the card. [on] shows it in the accent. */
@Composable
private fun HeaderLink(text: String, description: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        color = if (on) Amplo.Accent else Amplo.Soft,
    )
}

/** What shows for a song without lyrics, with the ways to add them. */
@Composable
private fun NoLyrics(onPaste: () -> Unit, onWrite: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_note), null, Modifier.size(28.dp), tint = Amplo.Soft)
        Spacer(Modifier.height(10.dp))
        Text(
            "Lyrics not available",
            style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
            color = Amplo.Text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Lyrics for this song haven’t been added yet.",
            style = MaterialTheme.typography.bodySmall,
            color = Amplo.Soft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Paste lyrics", R.drawable.ic_paste, accent = true, onClick = onPaste)
            PillButton("Write lyrics", R.drawable.ic_edit, accent = false, onClick = onWrite)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "They are saved as an .lrc file beside the song.",
            style = MaterialTheme.typography.bodySmall,
            color = Amplo.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

/** The top and bottom of the lines fade out, as on the desktop. */
private fun Modifier.edgeFades(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun SyncedLines(lyrics: Lyrics, extra: Extra?, position: MutableLongState, onSeek: (LyricLine) -> Unit) {
    val active by remember(lyrics) { derivedStateOf { lyrics.activeLine(position.longValue) } }
    val list = rememberLazyListState()
    // When the user last touched the lines.
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            if (it is DragInteraction.Start || it is DragInteraction.Stop || it is DragInteraction.Cancel) {
                touched = SystemClock.uptimeMillis()
            }
        }
    }
    // The line sung now stays in the middle. Before the first line, the first line is there.
    LaunchedEffect(lyrics, active, touched) {
        val wait = touched + FOLLOW_AGAIN_MS - SystemClock.uptimeMillis()
        if (wait > 0) delay(wait)
        list.center(active ?: 0, animate = true)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Room above the first line and below the last, so each can reach the middle.
        val room = maxHeight / 2
        LazyColumn(
            Modifier.fillMaxSize().edgeFades(),
            state = list,
            contentPadding = PaddingValues(top = room, bottom = room),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val current = active
                val highlight = index == current
                // Lines already sung are dimmer than the ones to come.
                val rest = if (current != null && index < current) 0.45f else 0.8f
                val color by animateColorAsState(
                    if (highlight) Amplo.Text else Amplo.Soft.copy(alpha = rest),
                    tween(250),
                    label = "line",
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Play from this line") { onSeek(line) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(line.text, style = lineStyle, color = color)
                    val under = extra?.let(line::extra)
                    if (under != null) {
                        val underColor by animateColorAsState(
                            if (highlight) lerp(Amplo.Soft, Amplo.Accent, 0.8f) else Amplo.Soft.copy(alpha = rest * 0.85f),
                            tween(250),
                            label = "under",
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(under, style = extraStyle, color = underColor)
                    }
                }
            }
        }
    }
    // The first frame starts at the right line, without a glide from the top.
    LaunchedEffect(lyrics) { list.center(active ?: 0, animate = false) }
}

/** Scrolls so the middle of item [index] is in the middle of the list. */
private suspend fun LazyListState.center(index: Int, animate: Boolean) {
    fun offset(): Float? {
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
        return (item.offset + item.size / 2 - middle).toFloat()
    }
    if (offset() == null) scrollToItem(index)
    val distance = offset() ?: return
    if (animate) animateScrollBy(distance, tween(450)) else scrollBy(distance)
}

@Composable
private fun PlainLines(lyrics: Lyrics, extra: Extra?) {
    LazyColumn(
        Modifier.fillMaxSize().edgeFades(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(lyrics.lines) { _, line ->
            Column {
                Text(line.text, style = plainStyle, color = Amplo.Soft)
                extra?.let(line::extra)?.let { under ->
                    Spacer(Modifier.height(3.dp))
                    Text(under, style = extraStyle, color = Amplo.Soft.copy(alpha = 0.7f))
                }
            }
        }
    }
}
