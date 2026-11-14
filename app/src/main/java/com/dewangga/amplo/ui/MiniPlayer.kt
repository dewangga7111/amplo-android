package com.dewangga.amplo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dewangga.amplo.R
import com.dewangga.amplo.playback.PlayerConnection
import com.dewangga.amplo.playback.PlayerState
import com.dewangga.amplo.ui.theme.Amplo
import kotlinx.coroutines.delay

/** The current song at the bottom of the screen, with its progress, Play and Next. */
@Composable
fun MiniPlayer(state: PlayerState, positionMs: () -> Long, connection: PlayerConnection) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.trackId, state.playing, state.durationMs) {
        while (true) {
            progress = if (state.durationMs > 0) (positionMs().toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            if (!state.playing) break
            delay(500)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Amplo.Mini)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Amplo.MiniLine),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(Amplo.Accent),
            )
        }
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(state.trackId, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.artist.ifEmpty { "Unknown artist" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RoundIconButton(
                if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (state.playing) "Pause" else "Play",
                size = 44.dp,
                iconSize = 22.dp,
                filled = true,
                onClick = connection::togglePlay,
            )
            RoundIconButton(R.drawable.ic_next, "Next", size = 44.dp, onClick = connection::next)
        }
    }
}
