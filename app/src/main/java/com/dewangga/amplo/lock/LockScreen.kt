package com.dewangga.amplo.lock

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewangga.amplo.R
import com.dewangga.amplo.library.Extra
import com.dewangga.amplo.library.Lyrics
import com.dewangga.amplo.library.likeKey
import com.dewangga.amplo.playback.Analyzer
import com.dewangga.amplo.playback.AudioTap
import com.dewangga.amplo.playback.FFT_SIZE
import com.dewangga.amplo.playback.PlayerConnection
import com.dewangga.amplo.playback.PlayerState
import com.dewangga.amplo.ui.panelTransition
import com.dewangga.amplo.ui.Cover
import com.dewangga.amplo.ui.ElapsedTime
import com.dewangga.amplo.ui.PlainLines
import com.dewangga.amplo.ui.RoundIconButton
import com.dewangga.amplo.ui.SeekBar
import com.dewangga.amplo.ui.SyncedLines
import com.dewangga.amplo.ui.Transport
import com.dewangga.amplo.ui.VISUALIZER_BARS
import com.dewangga.amplo.ui.Visualizer
import com.dewangga.amplo.ui.formatDuration
import com.dewangga.amplo.ui.monoStyle
import com.dewangga.amplo.ui.theme.Amplo
import kotlinx.coroutines.launch

/** A drag up that ends within this time closes the screen when it is short. */
private const val FLICK_MS = 250L

/**
 * Now Playing for the lock screen, with less in it: the cover or the lyrics, the song, the
 * visualizer, the seek bar and the controls. A tap on the cover shows the lyrics. Swiping up
 * closes it.
 */
@Composable
fun LockScreen(model: LockModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val player = model.player
    val state by player.playerState.collectAsStateWithLifecycle()

    // The player connects a moment after the screen opens. Once the queue is empty, there is
    // nothing to show.
    var hadSong by remember { mutableStateOf(false) }
    LaunchedEffect(state.trackId) {
        if (state.trackId != null) hadSong = true else if (hadSong) onClose()
    }

    var showLyrics by remember { mutableStateOf(false) }
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

    // Dragging the screen up closes it: a short drag, or a quick flick that is shorter still.
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val closeDistance = with(LocalDensity.current) { 32.dp.toPx() }
    val flickDistance = with(LocalDensity.current) { 12.dp.toPx() }
    var dragStart by remember { mutableLongStateOf(0L) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Amplo.Background)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragStart = SystemClock.uptimeMillis() },
                    onDragEnd = {
                        val quick = SystemClock.uptimeMillis() - dragStart < FLICK_MS
                        val distance = -drag.value
                        if (distance > closeDistance || (quick && distance > flickDistance)) onClose()
                        else scope.launch { drag.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo((drag.value + amount).coerceAtMost(0f)) }
                }
            }
            .graphicsLayer {
                translationY = drag.value
                alpha = (1f + drag.value / (closeDistance * 6f)).coerceIn(0f, 1f)
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Header(state, showLyrics) { showLyrics = !showLyrics }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedContent(showLyrics, transitionSpec = { panelTransition() }, contentAlignment = Alignment.Center, label = "panel") { lyricsShown ->
                if (lyricsShown) {
                    LockLyrics(model, state, position, player) { showLyrics = false }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val side = minOf(maxWidth, maxHeight)
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(onClickLabel = "Show the lyrics") { showLyrics = true },
                        ) {
                            Cover(state.trackId, side, corner = 14.dp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        SongInfo(model, state, position)
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
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_chevron_up), null, Modifier.size(14.dp), tint = Amplo.Muted)
            Spacer(Modifier.width(6.dp))
            Text("SWIPE UP TO CLOSE", style = monoStyle(10), color = Amplo.Muted, letterSpacing = 1.sp)
        }
    }
}

/** The logo, where the queue comes from, and the button for the lyrics. */
@Composable
private fun Header(state: PlayerState, showLyrics: Boolean, onLyrics: () -> Unit) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(Amplo.Accent, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_logo), "amplo", Modifier.size(18.dp), tint = Amplo.OnAccent)
            }
        }
        Column(
            Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PLAYING FROM ${state.sourceKind}".trim(), style = monoStyle(10), color = Amplo.Muted, letterSpacing = 1.sp)
            Text(
                state.source.ifEmpty { "Queue" },
                style = MaterialTheme.typography.labelLarge,
                color = Amplo.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundIconButton(
            R.drawable.ic_lyrics,
            if (showLyrics) "Show the cover" else "Show the lyrics",
            tint = if (showLyrics) Amplo.Accent else Amplo.Text,
            onClick = onLyrics,
        )
    }
}

/** The title and artist, the heart, and the time, as in Now Playing. */
@Composable
private fun SongInfo(model: LockModel, state: PlayerState, position: MutableLongState) {
    val track by model.track.collectAsStateWithLifecycle()
    val liked by model.liked.collectAsStateWithLifecycle()
    val song = track?.takeIf { it.id == state.trackId }
    val isLiked = song != null && song.likeKey in liked
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
                style = MaterialTheme.typography.bodyLarge.copy(color = Amplo.Soft),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (song != null) {
            RoundIconButton(
                if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                if (isLiked) "Unlike" else "Like",
                modifier = Modifier.padding(horizontal = 4.dp),
                size = 44.dp,
                iconSize = 22.dp,
                tint = if (isLiked) Amplo.Accent else Amplo.Soft,
            ) { model.toggleLike(song) }
        }
        Spacer(Modifier.width(8.dp))
        ElapsedTime(position)
    }
}

/** The lyrics on the whole card, as in Now Playing but without editing. A tap on the top shows the cover again. */
@Composable
private fun LockLyrics(
    model: LockModel,
    state: PlayerState,
    position: MutableLongState,
    player: PlayerConnection,
    onCover: () -> Unit,
) {
    val track by model.track.collectAsStateWithLifecycle()
    val lyrics by model.lyrics.collectAsStateWithLifecycle()
    val chosenExtra by model.lyricsExtra.collectAsStateWithLifecycle()
    // The lyrics of the song before stay out while the new ones are read.
    val read = track?.id == state.trackId
    val shown: Lyrics? = lyrics.takeIf { read }
    val extra: Extra? = chosenExtra?.takeIf { shown?.has(it) == true }
    Column(
        Modifier
            .fillMaxSize()
            .background(Amplo.Panel, RoundedCornerShape(14.dp))
            .border(1.dp, Amplo.Line, RoundedCornerShape(14.dp))
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier
                .height(36.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Show the cover", onClick = onCover),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("LYRICS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Amplo.Muted)
            if (shown?.synced == true) {
                Text("SYNCED", Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelSmall, color = Amplo.Accent)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(end = 10.dp)) {
            when {
                !read -> {}
                shown == null -> NoLyrics()
                shown.synced -> SyncedLines(shown, extra, position) { line ->
                    position.longValue = line.timeMs
                    player.seekTo(line.timeMs)
                }
                else -> PlainLines(shown, extra)
            }
        }
    }
}

@Composable
private fun NoLyrics() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_note), null, Modifier.size(28.dp), tint = Amplo.Soft)
        Spacer(Modifier.height(10.dp))
        Text(
            "Lyrics not available",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Amplo.Text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Add them in amplo after you unlock the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = Amplo.Soft,
            textAlign = TextAlign.Center,
        )
    }
}
