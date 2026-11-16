package com.dewangga.amplo.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewangga.amplo.AppViewModel
import com.dewangga.amplo.R
import com.dewangga.amplo.library.Collection
import com.dewangga.amplo.library.ListKind
import com.dewangga.amplo.library.SongList
import com.dewangga.amplo.library.Sort
import com.dewangga.amplo.library.Track
import com.dewangga.amplo.library.findList
import com.dewangga.amplo.library.likeKey
import com.dewangga.amplo.library.names
import com.dewangga.amplo.plural
import com.dewangga.amplo.ui.theme.Amplo
import com.dewangga.amplo.ui.theme.JetBrainsMono

/** The library and the open list, with the mini player under them and Now Playing over them. */
@Composable
fun LibraryScreen(model: AppViewModel) {
    val lists by model.lists.collectAsStateWithLifecycle()
    val player by model.player.playerState.collectAsStateWithLifecycle()
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    val panel = rememberSaveable { mutableStateOf(Panel.Cover) }
    // The lists opened one from another, such as a playlist, then an album from one of its songs.
    // Back goes to the one before.
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Opening a list that is open further back returns to it.
    fun open(key: String) {
        stack = stack.takeWhile { it != key } + key
    }
    fun back() {
        stack = stack.dropLast(1)
    }
    // The songs waiting for a playlist to go into, and for the name of a new one.
    var adding by remember { mutableStateOf<List<Track>?>(null) }
    // The search button of Home opens All Songs with the search field open.
    var searchOnOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<List<Track>?>(null) }

    // Nothing to show once the queue is empty.
    LaunchedEffect(player.trackId) {
        if (player.trackId == null) nowPlaying = false
    }
    val openKey = stack.lastOrNull()
    // The open list, or the key of the Spotify page that is open.
    val open: Any? = remember(lists, openKey) {
        when {
            openKey == null -> null
            isSpotifyKey(openKey) -> openKey
            else -> lists?.let { findList(openKey, it) }
        }
    }
    // A list can go away, such as Liked Songs when its last heart goes.
    LaunchedEffect(lists, stack) {
        val all = lists ?: return@LaunchedEffect
        val left = stack.filter { isSpotifyKey(it) || findList(it, all) != null }
        if (left != stack) stack = left
    }
    BackHandler(enabled = stack.isNotEmpty() && !nowPlaying) { back() }
    Messages(model)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Amplo.Background)
                // The keyboard of a search pushes the list and the mini player up.
                .imePadding(),
        ) {
            Box(Modifier.weight(1f)) {
                val all = lists
                if (all == null) {
                    Message("Reading your library…", null)
                } else {
                    AnimatedContent(
                        targetState = stack.size to open,
                        contentKey = { (it.second as? SongList)?.key ?: it.second },
                        transitionSpec = {
                            if (targetState.first > initialState.first) {
                                slideInHorizontally { it / 3 } + fadeIn() togetherWith fadeOut()
                            } else {
                                fadeIn() togetherWith slideOutHorizontally { it / 3 } + fadeOut()
                            }
                        },
                        label = "list",
                    ) { (_, list) ->
                        when (list) {
                            null -> Home(
                                model,
                                all,
                                onOpen = ::open,
                                onNewPlaylist = { naming = emptyList() },
                                onSearch = {
                                    searchOnOpen = true
                                    open(SongList.ALL)
                                },
                            )
                            is String -> SpotifyScreen(model, list, onBack = ::back, onOpen = ::open)
                            is SongList -> ListScreen(
                                model,
                                list,
                                currentId = player.trackId,
                                onBack = ::back,
                                onOpen = ::open,
                                onAdd = { adding = it },
                                searchOnOpen = searchOnOpen && list.key == SongList.ALL,
                                onSearchOpened = { searchOnOpen = false },
                            )
                        }
                    }
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
            NowPlaying(
                model,
                player,
                panel,
                onClose = { nowPlaying = false },
                onOpen = { key ->
                    nowPlaying = false
                    open(key)
                },
            )
        }
    }

    adding?.let { songs ->
        AddToPlaylistDialog(
            playlists = lists.orEmpty().filter { it.kind == ListKind.Playlist },
            count = songs.size,
            onDismiss = { adding = null },
            onNew = {
                adding = null
                naming = songs
            },
            onPick = { list ->
                adding = null
                list.playlist?.let { model.addToPlaylist(it, songs) }
            },
        )
    }
    naming?.let { songs ->
        NewPlaylistDialog(
            count = songs.size,
            onDismiss = { naming = null },
            onCreate = { name, done ->
                model.createPlaylist(name, songs) { key, error ->
                    done(error)
                    if (key != null) {
                        naming = null
                        // A new empty playlist opens, so songs can go into it.
                        if (songs.isEmpty()) {
                            open(key)
                            nowPlaying = false
                        }
                    }
                }
            },
        )
    }
}

/** Shows the confirmations of the model, and asks the user when Android wants it. */
@Composable
private fun Messages(model: AppViewModel) {
    val context = LocalContext.current
    LaunchedEffect(model) {
        model.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val consent by model.consent.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.answerConsent(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consent) {
        consent?.let { ask.launch(IntentSenderRequest.Builder(it.request).build()) }
    }
    val folder by model.folderRequest.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), model::answerFolder)
    LaunchedEffect(folder) {
        folder?.let { request ->
            try {
                pickFolder.launch(request.start)
            } catch (_: ActivityNotFoundException) {
                model.answerFolder(null)
                Toast.makeText(context, "This phone has no folder picker.", Toast.LENGTH_LONG).show()
            }
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

/** The lists to open, as the sidebar of the desktop app shows them. */
@Composable
private fun Home(
    model: AppViewModel,
    lists: List<SongList>,
    onOpen: (String) -> Unit,
    onNewPlaylist: () -> Unit,
    onSearch: () -> Unit,
) {
    val missing by model.missingEntries.collectAsStateWithLifecycle()
    val coverHint by model.coverHint.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Amplo.Accent, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(20.dp), tint = Amplo.OnAccent)
                }
                Spacer(Modifier.width(10.dp))
                Text("amplo", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = Amplo.Text)
                RoundIconButton(R.drawable.ic_search, "Search songs", onClick = onSearch)
            }
        }
        item(key = "playlists") {
            Row(
                Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("PLAYLISTS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Amplo.Muted)
                RoundIconButton(R.drawable.ic_plus, "New playlist", tint = Amplo.Soft, onClick = onNewPlaylist)
            }
        }
        items(lists, key = { it.key }) { list ->
            ListRow(list) { onOpen(list.key) }
        }
        if (coverHint) {
            item(key = "cover-hint") { CoverHint(onAllow = model::allowCovers, onClose = model::closeCoverHint) }
        }
        if (missing > 0) {
            item(key = "missing") {
                Text(
                    "${if (missing == 1) "1 playlist entry was" else "$missing playlist entries were"} not found.",
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Amplo.Warning,
                )
            }
        }
        item(key = "spotify") {
            Text(
                "SPOTIFY",
                Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Amplo.Muted,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(SPOTIFY) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(Amplo.Mini, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_cloud), null, Modifier.size(24.dp), tint = Amplo.Accent)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Spotify", style = MaterialTheme.typography.titleMedium, color = Amplo.Text)
                    Text("Your playlists, and which songs you have", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Offers to read the Music folder, where the covers that playlists got on a computer are. */
@Composable
private fun CoverHint(onAllow: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(Amplo.Mini, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Show playlist covers", style = MaterialTheme.typography.titleSmall, color = Amplo.Text)
            Spacer(Modifier.height(2.dp))
            Text(
                "Covers made on a computer are pictures beside the playlists. Pick the Music folder to show them.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            PillButton("Pick the Music folder", R.drawable.ic_folder, accent = false, onClick = onAllow)
        }
        RoundIconButton(R.drawable.ic_close, "Close", iconSize = 16.dp, tint = Amplo.Soft, onClick = onClose)
    }
}

@Composable
private fun ListRow(list: SongList, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListArt(list, 52.dp, corner = 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(list.name, style = MaterialTheme.typography.titleMedium, color = Amplo.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(plural(list.songs.size), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Liked Songs shows a heart and an album its cover. Other lists show the covers of their first
 * four albums, or the cover of their first song when they have fewer.
 */
@Composable
private fun ListArt(list: SongList, size: Dp, corner: Dp) {
    if (list.kind == ListKind.Liked) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(Amplo.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_heart_filled), null, Modifier.size(size * 0.45f), tint = Amplo.OnAccent)
        }
        return
    }
    list.playlist?.cover?.let {
        PlaylistCoverImage(it, size, corner)
        return
    }
    val covers = remember(list) {
        if (list.kind == ListKind.Album) emptyList()
        else list.songs.distinctBy { it.album.lowercase().ifEmpty { "#${it.id}" } }.take(4).map(Track::id)
    }
    if (covers.size < 4) {
        Cover(list.songs.firstOrNull()?.id, size, corner = corner)
        return
    }
    Column(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner)),
    ) {
        for (row in 0..1) {
            Row {
                for (column in 0..1) Cover(covers[row * 2 + column], size / 2, corner = 0.dp)
            }
        }
    }
}

/** The songs of one list, with Play, Shuffle and the sort above them. */
@Composable
private fun ListScreen(
    model: AppViewModel,
    list: SongList,
    currentId: Long?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: (List<Track>) -> Unit,
    searchOnOpen: Boolean,
    onSearchOpened: () -> Unit,
) {
    val sort by model.sort.collectAsStateWithLifecycle()
    val liked by model.liked.collectAsStateWithLifecycle()
    var searching by rememberSaveable(list.key) { mutableStateOf(searchOnOpen) }
    var query by rememberSaveable(list.key) { mutableStateOf("") }
    // The field takes the focus when the search opens, not each time it scrolls back into view.
    var focusSearch by remember(list.key) { mutableStateOf(searchOnOpen) }
    LaunchedEffect(searchOnOpen) { if (searchOnOpen) onSearchOpened() }
    // Back closes the search before it closes the list.
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    // A search plays the songs it shows, in their order, as on the desktop.
    val shown = remember(list, query) { list.copy(songs = list.songs.matching(query)) }
    fun play(index: Int, shuffle: Boolean?) = model.player.play(shown, index, shuffle)
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { image ->
        val playlist = list.playlist
        if (image != null && playlist != null) model.setCover(playlist, image)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            ListHeader(
                list,
                sort ?: Sort.RecentlyAdded,
                onBack,
                onOpen,
                ::play,
                model::setSort,
                searching = searching,
                onChangeCover = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveCover = { list.playlist?.let(model::removeCover) },
                onSearch = {
                    searching = !searching
                    focusSearch = searching
                    if (!searching) query = ""
                },
            )
        }
        if (searching) {
            item(key = "search") {
                SearchField(query, onChange = { query = it }, focus = focusSearch, onFocused = { focusSearch = false })
            }
        }
        if (shown.songs.isEmpty()) {
            item(key = "empty") {
                Text(
                    when {
                        query.isNotBlank() && list.songs.isNotEmpty() -> "No songs match “${query.trim()}”."
                        list.kind == ListKind.All -> "No music found. Copy songs into the Music folder of this phone."
                        else -> "No songs yet. Add songs from their menu."
                    },
                    Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Amplo.Muted,
                )
            }
        }
        // A playlist can have a song twice, so the place in the list tells rows apart.
        itemsIndexed(shown.songs, key = { index, track -> "$index:${track.id}" }) { index, track ->
            SongRow(
                track,
                current = track.id == currentId,
                liked = track.likeKey in liked,
                removeFrom = list.name.takeIf { list.kind == ListKind.Liked || list.kind == ListKind.Playlist },
                links = remember(track, list.collection) { linksOf(track, list.collection) },
                onClick = { play(index, null) },
                onOpen = onOpen,
                onLike = { model.toggleLike(track) },
                onAdd = { onAdd(listOf(track)) },
                onRemove = { model.removeFromList(list, listOf(track)) },
            )
        }
    }
}

@Composable
private fun ListHeader(
    list: SongList,
    sort: Sort,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (Int, Boolean?) -> Unit,
    onSort: (Sort) -> Unit,
    searching: Boolean,
    onChangeCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onSearch: () -> Unit,
) {
    Column(
        Modifier
            .statusBarsPadding()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(R.drawable.ic_back, "Back", onClick = onBack)
            Spacer(Modifier.weight(1f))
            list.playlist?.let { PlaylistMenu(it.name, hasCover = it.cover != null, onChangeCover, onRemoveCover) }
        }
        Column(Modifier.padding(start = 12.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                ListArt(list, 112.dp, corner = 10.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(list.kind.label, style = MaterialTheme.typography.labelSmall, color = Amplo.Muted)
                    Spacer(Modifier.height(4.dp))
                    Text(list.name, style = MaterialTheme.typography.headlineMedium, color = Amplo.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // An album shows its artist, which opens their songs. Without an album artist,
                    // the artists that every song names count.
                    val artist = remember(list) {
                        (list.collection as? Collection.Album)?.let { album ->
                            album.artist.ifEmpty {
                                names(list.songs.firstOrNull()?.artist.orEmpty())
                                    .filter { name -> list.songs.all { song -> names(song.artist).any { it.equals(name, ignoreCase = true) } } }
                                    .joinToString(", ")
                            }
                        }.orEmpty()
                    }
                    if (artist.isNotEmpty()) {
                        Text(
                            artistLinks(artist, onOpen),
                            style = MaterialTheme.typography.bodyMedium.copy(color = Amplo.Soft),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(describeSongs(list.songs.size, list.songs.sumOf { it.durationMs }), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (list.songs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Play", R.drawable.ic_play, accent = true) { onPlay(0, false) }
                    PillButton("Shuffle", R.drawable.ic_shuffle, accent = false) { onPlay(0, true) }
                    Spacer(Modifier.weight(1f))
                    RoundIconButton(
                        R.drawable.ic_search,
                        if (searching) "Close the search" else "Search in ${list.name}",
                        tint = if (searching) Amplo.Accent else Amplo.Text,
                        onClick = onSearch,
                    )
                    // An album keeps the order of its discs and tracks.
                    if (list.kind != ListKind.Album) SortButton(sort, onSort)
                }
            }
        }
    }
}

/** Change cover and Remove cover for a playlist file. */
@Composable
private fun PlaylistMenu(name: String, hasCover: Boolean, onChangeCover: () -> Unit, onRemoveCover: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(R.drawable.ic_more, "More for $name") { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = Amplo.Mini,
        ) {
            MenuItem("Change cover", R.drawable.ic_edit) {
                open = false
                onChangeCover()
            }
            if (hasCover) {
                MenuItem("Remove cover", R.drawable.ic_close) {
                    open = false
                    onRemoveCover()
                }
            }
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
private fun SongRow(
    track: Track,
    current: Boolean,
    liked: Boolean,
    removeFrom: String?,
    links: List<Link>,
    onClick: () -> Unit,
    onOpen: (String) -> Unit,
    onLike: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) Amplo.RowCurrent else Amplo.Background)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
        SongMenu(track.title, liked, removeFrom, links, onOpen, onLike, onAdd, onRemove)
    }
}

/** The songs whose title, artist or album has [query] in it, as the desktop search finds them. */
private fun List<Track>.matching(query: String): List<Track> {
    val words = query.trim().lowercase()
    if (words.isEmpty()) return this
    return filter { track -> listOf(track.title, track.artist, track.album).any { it.lowercase().contains(words) } }
}

/** The search field above the songs of a list. It takes the focus while [focus] is true. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, focus: Boolean, onFocused: () -> Unit) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focus) {
        if (!focus) return@LaunchedEffect
        // The field can take the focus only once it is laid out.
        withFrameNanos {}
        requester.requestFocus()
        onFocused()
    }
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            .focusRequester(requester),
        placeholder = { Text("Search songs, artists, albums", color = Amplo.Muted) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp), tint = Amplo.Muted) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                RoundIconButton(R.drawable.ic_close, "Clear the search", iconSize = 16.dp, tint = Amplo.Soft) { onChange("") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amplo.Accent,
            unfocusedBorderColor = Amplo.PillLine,
            cursorColor = Amplo.Accent,
        ),
    )
}

/** A list a song links to, such as its album. */
private data class Link(val label: String, val icon: Int, val key: String)

/** The album and each artist of a song, except the list that is open. */
private fun linksOf(track: Track, open: Collection?): List<Link> = buildList {
    Collection.albumOf(track)?.takeIf { it.key != open?.key }?.let { add(Link("Go to album", R.drawable.ic_disc, it.key)) }
    for (artist in names(track.artist).take(3)) {
        if (open is Collection.Artist && open.name.equals(artist, ignoreCase = true)) continue
        add(Link("Go to $artist", R.drawable.ic_person, Collection.Artist(artist).key))
    }
}

/** Like, Add to playlist, the album and artists, and Remove for one song. */
@Composable
private fun SongMenu(
    title: String,
    liked: Boolean,
    removeFrom: String?,
    links: List<Link>,
    onOpen: (String) -> Unit,
    onLike: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(R.drawable.ic_more, "More for $title", tint = Amplo.Soft) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = Amplo.Mini,
        ) {
            MenuItem(if (liked) "Unlike" else "Like", if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart) {
                open = false
                onLike()
            }
            MenuItem("Add to playlist", R.drawable.ic_plus) {
                open = false
                onAdd()
            }
            for (link in links) {
                MenuItem(link.label, link.icon) {
                    open = false
                    onOpen(link.key)
                }
            }
            if (removeFrom != null) {
                MenuItem("Remove from $removeFrom", R.drawable.ic_close) {
                    open = false
                    onRemove()
                }
            }
        }
    }
}

@Composable
fun MenuItem(label: String, icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = Amplo.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(18.dp), tint = Amplo.Soft) },
        onClick = onClick,
    )
}
