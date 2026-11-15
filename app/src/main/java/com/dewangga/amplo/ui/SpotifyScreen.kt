package com.dewangga.amplo.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewangga.amplo.AppViewModel
import com.dewangga.amplo.R
import com.dewangga.amplo.library.SongList
import com.dewangga.amplo.library.Track
import com.dewangga.amplo.spotify.Load
import com.dewangga.amplo.spotify.LocalIndex
import com.dewangga.amplo.spotify.Login
import com.dewangga.amplo.spotify.REDIRECT_URI
import com.dewangga.amplo.spotify.SpotifyPlaylist
import com.dewangga.amplo.spotify.SpotifyTrack
import com.dewangga.amplo.ui.theme.Amplo
import com.dewangga.amplo.ui.theme.JetBrainsMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** The key of the Spotify page in the stack of open lists. */
const val SPOTIFY = "spotify"
private const val PLAYLIST_PREFIX = "spotify:"

fun spotifyPlaylistKey(id: String) = PLAYLIST_PREFIX + id

/** Whether [key] opens a Spotify page instead of a list of songs. */
fun isSpotifyKey(key: String) = key == SPOTIFY || key.startsWith(PLAYLIST_PREFIX)

/** The Spotify page with the playlists, or one playlist when [key] names it. */
@Composable
fun SpotifyScreen(model: AppViewModel, key: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val spotify = model.spotify
    val login by spotify.login.collectAsStateWithLifecycle()
    when (val state = login) {
        Login.Reading -> Box(Modifier.fillMaxSize())
        Login.LoggedOut -> LoginForm(model, onBack)
        is Login.Waiting -> Waiting(model, state.url, onBack)
        Login.LoggedIn -> {
            LaunchedEffect(Unit) { spotify.loadPlaylists() }
            if (key == SPOTIFY) Playlists(model, onBack, onOpen) else PlaylistSongs(model, key.removePrefix(PLAYLIST_PREFIX), onBack, onOpen)
        }
    }
}

private fun openPage(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, "No browser can open the Spotify login.", Toast.LENGTH_LONG).show()
    false
}

/** The Spotify sign in the desktop app shows: the cloud on a dark square. */
@Composable
private fun SpotifyBadge() {
    Box(
        Modifier
            .size(44.dp)
            .background(Amplo.Mini, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_cloud), null, Modifier.size(22.dp), tint = Amplo.Accent)
    }
}

/** A rounded button with only a label. */
@Composable
private fun TextPill(label: String, accent: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(40.dp)
            .clip(shape)
            .then(if (accent) Modifier.background(Amplo.Accent) else Modifier.border(1.dp, Amplo.PillLine, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (accent) Amplo.OnAccent else Amplo.Text, maxLines = 1)
    }
}

/** A small choice that shows in the accent while chosen. */
@Composable
private fun Chip(label: String, chosen: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(32.dp)
            .clip(shape)
            .then(if (chosen) Modifier.background(Amplo.Accent) else Modifier.border(1.dp, Amplo.PillLine, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = if (chosen) Amplo.OnAccent else Amplo.Soft)
    }
}

/** How to make a Spotify app, and the field for its Client ID. */
@Composable
private fun LoginForm(model: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val spotify = model.spotify
    val saved by spotify.clientId.collectAsStateWithLifecycle()
    val error by spotify.error.collectAsStateWithLifecycle()
    var clientId by rememberSaveable(saved) { mutableStateOf(saved) }
    fun logIn() = spotify.logIn(clientId) { openPage(context, it) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
    ) {
        RoundIconButton(R.drawable.ic_back, "Back", onClick = onBack)
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp)) {
            SpotifyBadge()
            Spacer(Modifier.height(16.dp))
            Text("Connect Spotify", style = MaterialTheme.typography.headlineMedium, color = Amplo.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                "See your Spotify playlists, and which of their songs are already in your music folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = Amplo.Soft,
            )
            Spacer(Modifier.height(20.dp))
            Step("1. Create an app on developer.spotify.com/dashboard, with Web API selected.")
            Step("2. Add this redirect URI to the app:")
            Row(
                Modifier
                    .padding(start = 16.dp, top = 2.dp, bottom = 10.dp)
                    .fillMaxWidth()
                    .border(1.dp, Amplo.PillLine, RoundedCornerShape(8.dp))
                    .background(Amplo.Panel, RoundedCornerShape(8.dp))
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    REDIRECT_URI,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                    color = Amplo.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Copy",
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Copy the redirect URI") {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Redirect URI", REDIRECT_URI))
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = Amplo.Soft,
                )
            }
            Step("3. Paste the app's Client ID here:")
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Client ID", color = Amplo.Muted, fontFamily = JetBrainsMono) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono, color = Amplo.Text),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { logIn() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Amplo.Accent,
                    unfocusedBorderColor = Amplo.PillLine,
                    cursorColor = Amplo.Accent,
                ),
            )
            Spacer(Modifier.height(16.dp))
            TextPill("Log in with Spotify", accent = true) { logIn() }
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Amplo.Warning)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Spotify allows this only when the app's owner has Premium. An app in development mode " +
                    "works for up to 5 people, which you add in its User Management.",
                style = MaterialTheme.typography.bodySmall,
                color = Amplo.Muted,
            )
        }
    }
}

@Composable
private fun Step(text: String) {
    Text(text, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, color = Amplo.Text)
}

/** Shows while the login page is open in the browser. */
@Composable
private fun Waiting(model: AppViewModel, url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val error by model.spotify.error.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp),
    ) {
        RoundIconButton(R.drawable.ic_back, "Back", onClick = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SpotifyBadge()
            Spacer(Modifier.height(16.dp))
            Text("Waiting for Spotify…", style = MaterialTheme.typography.titleLarge, color = Amplo.Text)
            Spacer(Modifier.height(6.dp))
            Text("Finish the login in your browser.", style = MaterialTheme.typography.bodyMedium, color = Amplo.Soft)
            Spacer(Modifier.height(20.dp))
            TextPill("Open the login page again", accent = false) { openPage(context, url) }
            Spacer(Modifier.height(10.dp))
            TextPill("Cancel", accent = false) { model.spotify.cancelLogin() }
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Amplo.Warning, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(64.dp))
        }
    }
}

private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

/** The playlists of the account, with Refresh and Log out. */
@Composable
private fun Playlists(model: AppViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val spotify = model.spotify
    val user by spotify.user.collectAsStateWithLifecycle()
    val playlists by spotify.playlists.collectAsStateWithLifecycle()
    val loaded = (playlists as? Load.Done)?.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                RoundIconButton(R.drawable.ic_back, "Back", onClick = onBack)
                Column(Modifier.padding(start = 12.dp, top = 8.dp)) {
                    Text("SPOTIFY", style = MaterialTheme.typography.labelSmall, color = Amplo.Muted)
                    Spacer(Modifier.height(4.dp))
                    Text("Your playlists", style = MaterialTheme.typography.headlineMedium, color = Amplo.Text)
                    val name = user?.name
                    Text(
                        when {
                            name == null -> "Logged in"
                            loaded == null -> "Logged in as $name"
                            else -> "Logged in as $name · ${plural(loaded.size, "playlist")}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextPill("Refresh", accent = false) { spotify.refresh() }
                        TextPill("Log out", accent = false) { spotify.logOut() }
                    }
                }
            }
        }
        when (val state = playlists) {
            null, Load.Loading -> item(key = "loading") { Note("Loading your playlists…", Amplo.Soft) }
            is Load.Failed -> item(key = "error") { Note(state.message, Amplo.Warning) }
            is Load.Done -> {
                if (state.value.isEmpty()) item(key = "empty") { Note("Your Spotify account has no playlists.", Amplo.Soft) }
                items(state.value, key = { it.id }) { playlist ->
                    PlaylistRow(playlist) { onOpen(spotifyPlaylistKey(playlist.id)) }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun PlaylistRow(playlist: SpotifyPlaylist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WebImage(playlist.image, 52.dp, corner = 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = Amplo.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(plural(playlist.tracks, "track"), playlist.owner).filter(String::isNotEmpty).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The songs of one Spotify playlist, with which of them are in the music folder. */
@Composable
private fun PlaylistSongs(model: AppViewModel, id: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val spotify = model.spotify
    val playlists by spotify.playlists.collectAsStateWithLifecycle()
    val allTracks by spotify.tracks.collectAsStateWithLifecycle()
    val lists by model.lists.collectAsStateWithLifecycle()
    val playlist = (playlists as? Load.Done)?.value?.firstOrNull { it.id == id }
    LaunchedEffect(id, playlists is Load.Done) { if (playlists is Load.Done) spotify.loadTracks(id) }
    val load = allTracks[id]
    val tracks = (load as? Load.Done)?.value
    val library = remember(lists) { lists?.firstOrNull { it.key == SongList.ALL }?.songs.orEmpty() }
    // The local song of each Spotify song, or null when it is not in the music folder.
    val matches by produceState<List<Track?>?>(null, tracks, library) {
        value = tracks?.let {
            withContext(Dispatchers.Default) {
                val index = LocalIndex(library.map { song -> song.title to song.artist })
                it.map { track -> index.find(track)?.let(library::get) }
            }
        }
    }
    var missingOnly by rememberSaveable(id) { mutableStateOf(false) }
    val found = matches
    val rows = remember(tracks, found, missingOnly) {
        if (tracks == null || found == null) emptyList()
        else tracks.indices.filter { !missingOnly || found[it] == null }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            ) {
                RoundIconButton(R.drawable.ic_back, "Back", onClick = onBack)
                Column(Modifier.padding(start = 12.dp, top = 8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        WebImage(playlist?.image, 112.dp, corner = 10.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("SPOTIFY PLAYLIST", style = MaterialTheme.typography.labelSmall, color = Amplo.Muted)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                playlist?.name.orEmpty(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = Amplo.Text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            playlist?.owner?.takeIf(String::isNotEmpty)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = Amplo.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (tracks != null && found != null) {
                                Spacer(Modifier.height(2.dp))
                                Text("${found.count { it != null }} of ${tracks.size} available locally", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (tracks != null && found != null && tracks.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        PillButton("Import as playlist", R.drawable.ic_plus, accent = true) {
                            val songs = found.filterNotNull().distinctBy(Track::id)
                            model.importPlaylist(playlist?.name ?: "Spotify", songs, found.count { it == null }, onOpen)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("All", chosen = !missingOnly) { missingOnly = false }
                            Chip("Missing ${found.count { it == null }}", chosen = missingOnly) { missingOnly = true }
                        }
                    }
                }
            }
        }
        when {
            load is Load.Failed -> item(key = "error") { Note(load.message, Amplo.Warning) }
            tracks == null || found == null -> item(key = "loading") { Note("Loading songs…", Amplo.Soft) }
            rows.isEmpty() -> item(key = "empty") {
                Note(if (tracks.isEmpty()) "This playlist has no songs." else "Every song is in your music folder.", Amplo.Soft)
            }
            else -> itemsIndexed(rows, key = { _, row -> row }) { _, row ->
                TrackRow(tracks[row], available = found[row] != null)
            }
        }
    }
}

@Composable
private fun TrackRow(track: SpotifyTrack, available: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WebImage(track.image, 44.dp, corner = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyLarge, color = Amplo.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artists.joinToString(", "), track.album).filter(String::isNotEmpty).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        val color = if (available) Amplo.Available else Amplo.Missing
        Text(
            if (available) "Available" else "Missing",
            Modifier
                .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** The pictures read from Spotify, so a scroll back does not read them again. */
private val webImages = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val webReader = Dispatchers.IO.limitedParallelism(4)

/** A picture from the web, or a note on a plain square while it loads or when there is none. */
@Composable
private fun WebImage(url: String?, size: Dp, corner: Dp) {
    val image by produceState(url?.let(webImages::get), url) {
        if (url == null || value != null) return@produceState
        value = withContext(webReader) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                try {
                    connection.inputStream.use(BitmapFactory::decodeStream)?.asImageBitmap()
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                null
            }
        }?.also { webImages.put(url, it) }
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
            Icon(painterResource(R.drawable.ic_note), null, Modifier.size(size * 0.45f), tint = Amplo.Muted)
        }
    }
}
