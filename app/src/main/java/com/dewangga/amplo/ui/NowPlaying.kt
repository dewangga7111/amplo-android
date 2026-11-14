package com.dewangga.amplo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewangga.amplo.AppViewModel
import com.dewangga.amplo.R
import com.dewangga.amplo.library.likeKey
import com.dewangga.amplo.playback.AudioShape
import com.dewangga.amplo.playback.AudioTap
import com.dewangga.amplo.playback.Analyzer
import com.dewangga.amplo.playback.FFT_SIZE
import com.dewangga.amplo.playback.PlayerConnection
import com.dewangga.amplo.playback.PlayerState
import com.dewangga.amplo.playback.QueueEntry
import com.dewangga.amplo.ui.theme.Amplo
import com.dewangga.amplo.ui.theme.JetBrainsMono
import com.dewangga.amplo.ui.theme.SpaceGrotesk
import kotlinx.coroutines.launch

private const val VISUALIZER_BARS = 28

/** The playing song on the whole screen, with its cover or the queue, and every control. */
@Composable
fun NowPlaying(model: AppViewModel, state: PlayerState, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val player = model.player
    var showQueue by remember { mutableStateOf(false) }
    val position = remember { mutableLongStateOf(player.positionMs) }
    val frame = remember { mutableLongStateOf(0L) }
    val analyzer = remember { Analyzer(VISUALIZER_BARS) }
    val samples = remember { FloatArray(FFT_SIZE) }
    LaunchedEffect(state.playing, state.trackId) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 1 / 60f else ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            position.longValue = player.positionMs
            val rate = if (state.playing) AudioTap.heardNow(samples) else 0
            if (rate > 0) analyzer.update(samples, rate, dt) else analyzer.decay(dt)
            frame.longValue = now
            // A paused song needs no frames once the bars are down.
            if (!state.playing && analyzer.silent) break
        }
    }

    // Dragging the screen down closes it.
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val closeDistance = with(LocalDensity.current) { 140.dp.toPx() }
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = drag.value }
            .background(Amplo.Background)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag.value > closeDistance) onClose() else scope.launch { drag.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo((drag.value + amount).coerceAtLeast(0f)) }
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Header(state.source, showQueue, onClose, onQueue = { showQueue = !showQueue })
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (showQueue) {
                QueueCard(state, player)
            } else {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Cover(state.trackId, minOf(maxWidth, maxHeight), corner = 14.dp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        SongInfo(state, model, position)
        Spacer(Modifier.height(14.dp))
        Visualizer(analyzer, frame)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeekBar(position, state.durationMs, player::seekTo, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(formatDuration(state.durationMs), style = monoStyle(12), color = Amplo.Muted)
        }
        Spacer(Modifier.height(6.dp))
        Transport(state, player)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun monoStyle(size: Int, weight: FontWeight = FontWeight.Medium) =
    MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, fontWeight = weight, fontSize = size.sp)

@Composable
private fun Header(source: String, showQueue: Boolean, onClose: () -> Unit, onQueue: () -> Unit) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(R.drawable.ic_chevron_down, "Back to song list", modifier = Modifier.padding(end = 8.dp), onClick = onClose)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PLAYING FROM", style = monoStyle(10), color = Amplo.Muted, letterSpacing = 1.sp)
            Text(
                source.ifEmpty { "Queue" },
                style = MaterialTheme.typography.labelLarge,
                color = Amplo.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundIconButton(
            R.drawable.ic_queue,
            if (showQueue) "Show the cover" else "Show the queue",
            modifier = Modifier.padding(start = 8.dp),
            tint = if (showQueue) Amplo.Accent else Amplo.Text,
            onClick = onQueue,
        )
    }
}

@Composable
private fun SongInfo(state: PlayerState, model: AppViewModel, position: MutableLongState) {
    val shape by AudioTap.shape.collectAsStateWithLifecycle()
    val liked by model.liked.collectAsStateWithLifecycle()
    val lists by model.lists.collectAsStateWithLifecycle()
    // The lists change when the library is read again, which can find the song.
    val track = remember(state.trackId, lists) { state.trackId?.let(model::track) }
    val isLiked = track != null && track.likeKey in liked
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(
                state.title,
                Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp),
                color = Amplo.Text,
                maxLines = 1,
            )
            Text(
                state.artist.ifEmpty { "Unknown artist" },
                Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000),
                style = MaterialTheme.typography.bodyLarge,
                color = Amplo.Soft,
                maxLines = 1,
            )
        }
        if (track != null) {
            RoundIconButton(
                if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                if (isLiked) "Unlike" else "Like",
                modifier = Modifier.padding(horizontal = 4.dp),
                size = 44.dp,
                iconSize = 22.dp,
                tint = if (isLiked) Amplo.Accent else Amplo.Soft,
            ) { model.toggleLike(track) }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            // Only this text changes every second, so only it reads the position.
            ElapsedTime(position)
            techLines(track?.bitrate ?: 0, shape).forEach { line ->
                Text(line, style = monoStyle(10), color = Amplo.Muted)
            }
        }
    }
}

@Composable
private fun ElapsedTime(position: MutableLongState) {
    val seconds by remember { derivedStateOf { position.longValue / 1000 } }
    Text(
        formatDuration(seconds * 1000),
        style = monoStyle(28, FontWeight.SemiBold).copy(letterSpacing = (-0.5).sp),
        color = Amplo.Accent,
    )
}

/** "953 KBPS", "44.1 KHZ" and "STEREO", as far as they are known. */
fun techLines(bitrate: Int, shape: AudioShape?): List<String> = buildList {
    if (bitrate > 0) add("$bitrate KBPS")
    if (shape != null) {
        val rate = shape.sampleRate
        add(if (rate % 1000 == 0) "${rate / 1000} KHZ" else "%.1f KHZ".format(java.util.Locale.ROOT, rate / 1000.0))
        add(
            when (shape.channels) {
                1 -> "MONO"
                2 -> "STEREO"
                else -> "${shape.channels} CH"
            },
        )
    }
}

@Composable
private fun Visualizer(analyzer: Analyzer, frame: MutableLongState) {
    val color = Amplo.Accent.copy(alpha = 0.85f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(34.dp),
    ) {
        // Reading the frame here redraws the bars on every frame without composing again.
        frame.longValue
        val gap = 3.dp.toPx()
        val count = analyzer.bars.size
        val width = (size.width - gap * (count - 1)) / count
        val floor = 2.dp.toPx()
        analyzer.bars.forEachIndexed { index, level ->
            val height = floor + level * (size.height - floor)
            drawRoundRect(
                color,
                topLeft = Offset(index * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/** A thin bar to see and change where the song is. Tap or drag to seek. */
@Composable
private fun SeekBar(position: MutableLongState, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    fun at(x: Float) = (x / width).coerceIn(0f, 1f)
    Canvas(
        modifier
            .height(32.dp)
            .pointerInput(durationMs) {
                width = size.width.toFloat()
                detectTapGestures { onSeek((at(it.x) * durationMs).toLong()) }
            }
            .pointerInput(durationMs) {
                width = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragStart = { dragged = at(it.x) },
                    onDragEnd = {
                        dragged?.let { onSeek((it * durationMs).toLong()) }
                        dragged = null
                    },
                    onDragCancel = { dragged = null },
                ) { change, _ ->
                    change.consume()
                    dragged = at(change.position.x)
                }
            },
    ) {
        val fraction = dragged
            ?: if (durationMs > 0) (position.longValue.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val track = 4.dp.toPx()
        val y = size.height / 2
        val radius = CornerRadius(track / 2)
        drawRoundRect(Amplo.MiniLine, Offset(0f, y - track / 2), Size(size.width, track), radius)
        drawRoundRect(Amplo.Accent, Offset(0f, y - track / 2), Size(size.width * fraction, track), radius)
        drawCircle(Amplo.Accent, radius = if (dragged != null) 9.dp.toPx() else 7.dp.toPx(), center = Offset(size.width * fraction, y))
    }
}

@Composable
private fun Transport(state: PlayerState, player: PlayerConnection) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(
            R.drawable.ic_shuffle,
            if (state.shuffle) "Shuffle on" else "Shuffle off",
            size = 48.dp,
            tint = if (state.shuffle) Amplo.Accent else Amplo.Soft,
            onClick = player::toggleShuffle,
        )
        RoundIconButton(R.drawable.ic_prev, "Previous", size = 52.dp, iconSize = 26.dp, onClick = player::previous)
        RoundIconButton(
            if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
            if (state.playing) "Pause" else "Play",
            size = 72.dp,
            iconSize = 30.dp,
            filled = true,
            onClick = player::togglePlay,
        )
        RoundIconButton(R.drawable.ic_next, "Next", size = 52.dp, iconSize = 26.dp, onClick = player::next)
        RoundIconButton(
            R.drawable.ic_repeat,
            if (state.repeatOne) "Repeat this song on" else "Repeat this song off",
            size = 48.dp,
            tint = if (state.repeatOne) Amplo.Accent else Amplo.Soft,
            onClick = player::toggleRepeat,
        )
    }
}

@Composable
private fun QueueCard(state: PlayerState, player: PlayerConnection) {
    val queue by player.queue.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    // A new song starts at the top, where the song that plays now is.
    LaunchedEffect(state.trackId) { list.scrollToItem(0) }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Amplo.Panel, RoundedCornerShape(14.dp))
            .border(1.dp, Amplo.Line, RoundedCornerShape(14.dp)),
        state = list,
        contentPadding = PaddingValues(8.dp),
    ) {
        item(key = "now") {
            QueueHeading("Now playing")
            QueueRow(state.trackId, state.title, state.artist, Amplo.Accent, onClick = null)
        }
        if (queue.isNotEmpty()) {
            item(key = "next") {
                Spacer(Modifier.height(8.dp))
                QueueHeading(if (state.source.isEmpty()) "Next in queue" else "Next from: ${state.source}")
            }
        }
        items(queue, key = { it.index }) { entry: QueueEntry ->
            QueueRow(entry.trackId, entry.title, entry.artist, Amplo.Text) { player.playQueueEntry(entry.index) }
        }
    }
}

@Composable
private fun QueueHeading(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleMedium.copy(fontFamily = SpaceGrotesk, fontSize = 15.sp),
        color = Amplo.Text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun QueueRow(trackId: Long?, title: String, artist: String, titleColor: Color, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(trackId, 40.dp, corner = 7.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artist.ifEmpty { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                color = Amplo.Soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
