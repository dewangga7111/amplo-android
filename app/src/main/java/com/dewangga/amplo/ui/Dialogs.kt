package com.dewangga.amplo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dewangga.amplo.R
import com.dewangga.amplo.library.SongList
import com.dewangga.amplo.plural
import com.dewangga.amplo.ui.theme.Amplo
import com.dewangga.amplo.ui.theme.JetBrainsMono

/** Picks the playlist that songs go into, or starts a new one. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<SongList>,
    count: Int,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onPick: (SongList) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Amplo.Mini,
        title = { Text(if (count == 1) "Add to playlist" else "Add ${plural(count)} to playlist", style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item(key = "new") {
                    PickRow("New playlist…", accent = true, onClick = onNew) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(22.dp), tint = Amplo.Accent)
                        }
                    }
                }
                items(playlists, key = { it.key }) { list ->
                    PickRow(list.name, accent = false, onClick = { onPick(list) }) {
                        Cover(list.songs.firstOrNull()?.id, 40.dp, corner = 7.dp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Amplo.Soft) }
        },
    )
}

@Composable
private fun PickRow(label: String, accent: Boolean, onClick: () -> Unit, picture: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        picture()
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (accent) Amplo.Accent else Amplo.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Asks for the name of a new playlist. [onCreate] gets the name and a function to call with an
 * error message, or with null once the playlist is made.
 */
@Composable
fun NewPlaylistDialog(count: Int, onDismiss: () -> Unit, onCreate: (String, (String?) -> Unit) -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun create() {
        if (busy) return
        busy = true
        onCreate(name) {
            busy = false
            error = it
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Amplo.Mini,
        title = { Text("New playlist", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (count > 0) {
                    Text("With ${plural(count)}. It is saved in Music/Playlists.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("It is saved in Music/Playlists.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    placeholder = { Text("Name", color = Amplo.Muted) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = Amplo.Warning) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amplo.Accent,
                        unfocusedBorderColor = Amplo.PillLine,
                        errorBorderColor = Amplo.Warning,
                        cursorColor = Amplo.Accent,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::create, enabled = !busy) { Text("Create", color = Amplo.Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Amplo.Soft) }
        },
    )
}

/**
 * Writes or changes the lyrics of a song, on the whole screen. [onSave] gets the text and a
 * function to call with null once it is saved, or with a message why it was not.
 */
@Composable
fun LyricsEditor(title: String, initial: String, onDismiss: () -> Unit, onSave: (String, (String?) -> Unit) -> Unit) {
    // The cursor starts at the end, where new lines go.
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // The window of the dialog takes the focus a frame after it shows.
    LaunchedEffect(Unit) {
        withFrameNanos {}
        focus.requestFocus()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Amplo.Background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(R.drawable.ic_close, "Close without saving", onClick = onDismiss)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("LYRICS", style = MaterialTheme.typography.labelSmall, color = Amplo.Muted)
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Amplo.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PillButton("Save", R.drawable.ic_check, accent = true) {
                    if (!busy) {
                        busy = true
                        onSave(text.text) {
                            busy = false
                            error = it
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focus),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Amplo.Text),
                placeholder = {
                    Text(
                        "[00:12.00] A line with its time\n[00:15.50] The next line\n\nor the lines without times.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono, fontSize = 13.sp),
                        color = Amplo.Muted,
                    )
                },
                isError = error != null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Amplo.Accent,
                    unfocusedBorderColor = Amplo.PillLine,
                    errorBorderColor = Amplo.Warning,
                    cursorColor = Amplo.Accent,
                ),
            )
            Text(
                error ?: "Saved as an .lrc file beside the song, which the desktop app reads too.",
                Modifier.padding(top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) Amplo.Warning else Amplo.Muted,
            )
        }
    }
}
