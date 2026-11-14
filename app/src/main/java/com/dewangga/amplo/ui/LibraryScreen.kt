package com.dewangga.amplo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewangga.amplo.AppViewModel
import com.dewangga.amplo.R
import com.dewangga.amplo.library.Sort
import com.dewangga.amplo.library.Track
import com.dewangga.amplo.ui.theme.Amplo
import com.dewangga.amplo.ui.theme.JetBrainsMono

/** All songs of the device with the mini player under them. */
@Composable
fun LibraryScreen(model: AppViewModel) {
    val songs by model.songs.collectAsStateWithLifecycle()
    val sort by model.sort.collectAsStateWithLifecycle()
    val player by model.player.playerState.collectAsStateWithLifecycle()
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    // Nothing to show once the queue is empty.
    LaunchedEffect(player.trackId) {
        if (player.trackId == null) nowPlaying = false
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Amplo.Background),
        ) {
            Box(Modifier.weight(1f)) {
                val list = songs
                when {
                    list == null -> Message("Reading your library…", null)
                    list.isEmpty() -> Message("No songs yet", "Copy music to this device and it shows here.")
                    else -> SongList(
                        songs = list,
                        sort = sort ?: Sort.RecentlyAdded,
                        currentId = player.trackId,
                        onPlay = { index, shuffle -> model.player.play(list, index, shuffle, source = "All Songs") },
                        onSort = model::setSort,
                    )
                }
            }
            if (player.trackId != null) {
                MiniPlayer(
                    player,
                    positionMs = { model.player.positionMs },
                    connection = model.player,
                    onOpen = { nowPlaying = true },
                )
            } else {
                Spacer(Modifier.navigationBarsPadding())
            }
        }
        AnimatedVisibility(
            visible = nowPlaying,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            NowPlaying(model, player, onClose = { nowPlaying = false })
        }
    }
}

@Composable
private fun Message(title: String, detail: String?) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Amplo.Text)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Amplo.Muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SongList(
    songs: List<Track>,
    sort: Sort,
    currentId: Long?,
    onPlay: (index: Int, shuffle: Boolean?) -> Unit,
    onSort: (Sort) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Header(songs, sort, onPlay, onSort)
        }
        itemsIndexed(songs, key = { _, track -> track.id }) { index, track ->
            SongRow(track, current = track.id == currentId) { onPlay(index, null) }
        }
    }
}

@Composable
private fun Header(songs: List<Track>, sort: Sort, onPlay: (Int, Boolean?) -> Unit, onSort: (Sort) -> Unit) {
    Column(
        Modifier
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
    ) {
        Text("SONGS", style = MaterialTheme.typography.labelSmall, color = Amplo.Muted)
        Spacer(Modifier.height(4.dp))
        Text("All Songs", style = MaterialTheme.typography.headlineMedium, color = Amplo.Text)
        Spacer(Modifier.height(2.dp))
        Text(describeSongs(songs.size, songs.sumOf { it.durationMs }), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Play", R.drawable.ic_play, accent = true) { onPlay(0, false) }
            PillButton("Shuffle", R.drawable.ic_shuffle, accent = false) { onPlay(0, true) }
            Spacer(Modifier.weight(1f))
            SortButton(sort, onSort)
        }
    }
}

@Composable
private fun SortButton(sort: Sort, onSort: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(R.drawable.ic_sort, "Sort: ${sort.label}") { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = Amplo.Mini,
        ) {
            Text(
                "SORT BY",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Amplo.Muted,
            )
            Sort.entries.forEach { choice ->
                val chosen = choice == sort
                DropdownMenuItem(
                    text = {
                        Text(
                            choice.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (chosen) Amplo.Accent else Amplo.Text,
                        )
                    },
                    trailingIcon = {
                        if (chosen) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = Amplo.Accent)
                    },
                    onClick = {
                        open = false
                        onSort(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun SongRow(track: Track, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) Amplo.RowCurrent else Amplo.Background)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(track.id, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) Amplo.Accent else Amplo.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifEmpty { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatDuration(track.durationMs),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
            color = Amplo.Muted,
        )
    }
}
