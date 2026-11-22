package com.dewangga.amplo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dewangga.amplo.R
import com.dewangga.amplo.sync.Computer
import com.dewangga.amplo.sync.SyncModel
import com.dewangga.amplo.sync.SyncPlan
import com.dewangga.amplo.sync.SyncSong
import com.dewangga.amplo.sync.SyncState
import com.dewangga.amplo.ui.theme.Amplo
import java.util.Locale

/** Finds the computer, pairs with it, and shows what a sync copies while it runs. */
@Composable
fun SyncDialog(sync: SyncModel, state: SyncState) {
    if (state == SyncState.Idle) return
    val running = state is SyncState.Running
    // Wi-Fi can sleep with the screen, which would stop a long copy.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    AlertDialog(
        onDismissRequest = { if (!running) sync.close() },
        properties = DialogProperties(dismissOnClickOutside = !running),
        containerColor = Amplo.Mini,
        icon = { Icon(painterResource(R.drawable.ic_phone), null, Modifier.size(24.dp), tint = Amplo.Accent) },
        title = { Text("Sync with computer", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (state) {
                    SyncState.Idle -> {}
                    SyncState.Searching -> Waiting("Looking for amplo on this Wi-Fi…")
                    is SyncState.Planning -> Waiting("Comparing the songs and playlists with ${state.computer.name}…")
                    is SyncState.Choose -> ChooseComputer(state.computers, sync::choose)
                    is SyncState.NotFound -> TypeAddress(state.error, sync::connectTo)
                    is SyncState.NeedCode -> TypeCode(state) { sync.pair(state.computer, it) }
                    is SyncState.Ready -> Summary(state.computer, state.plan)
                    is SyncState.Running -> Progress(state)
                    is SyncState.Done -> Finished(state.message, state.problems)
                    is SyncState.Failed -> Body(state.message, Amplo.Warning)
                }
            }
        },
        confirmButton = {
            when (state) {
                is SyncState.Ready -> TextButton(onClick = sync::run) { Text("Sync", color = Amplo.Accent) }
                is SyncState.Failed -> TextButton(onClick = sync::start) { Text("Try again", color = Amplo.Accent) }
                is SyncState.Done -> TextButton(onClick = sync::close) { Text("Done", color = Amplo.Accent) }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is SyncState.Done) {
                TextButton(onClick = sync::close) { Text(if (running) "Stop" else "Cancel", color = Amplo.Soft) }
            }
        },
    )
}

@Composable
private fun Body(text: String, color: androidx.compose.ui.graphics.Color = Amplo.Soft) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Amplo.Accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Body(text)
    }
}

@Composable
private fun ChooseComputer(computers: List<Computer>, onPick: (Computer) -> Unit) {
    Body("Pick the computer to sync with.")
    Spacer(Modifier.height(8.dp))
    for (computer in computers) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onPick(computer) }
                .padding(vertical = 10.dp, horizontal = 4.dp),
        ) {
            Text(computer.name, style = MaterialTheme.typography.bodyLarge, color = Amplo.Text)
            Text(computer.host, style = MaterialTheme.typography.bodySmall, color = Amplo.Muted)
        }
    }
}

@Composable
private fun SyncField(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType, onDone: () -> Unit) =
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amplo.Accent,
            focusedLabelColor = Amplo.Accent,
            cursorColor = Amplo.Accent,
        ),
    )

@Composable
private fun TypeAddress(error: String?, onConnect: (String) -> Unit) {
    var address by remember { mutableStateOf("") }
    Body(
        "No computer answered. On the computer, open amplo, click the phone button above the playlists and turn sync on. " +
            "Then try again, or type the address it shows.",
    )
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Body(error, Amplo.Warning)
    }
    Spacer(Modifier.height(12.dp))
    SyncField(address, { address = it }, "Computer address", KeyboardType.Uri) { onConnect(address) }
    Spacer(Modifier.height(4.dp))
    Row {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onConnect(address) }, enabled = address.isNotBlank()) { Text("Connect", color = Amplo.Accent) }
    }
}

@Composable
private fun TypeCode(state: SyncState.NeedCode, onPair: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Body("Type the pairing code that amplo shows on ${state.computer.name}. The phone asks for it only once.")
    if (state.error != null) {
        Spacer(Modifier.height(8.dp))
        Body(state.error, Amplo.Warning)
    }
    Spacer(Modifier.height(12.dp))
    SyncField(code, { typed -> code = typed.filter(Char::isDigit).take(6) }, "Pairing code", KeyboardType.NumberPassword) {
        if (code.length == 6) onPair(code)
    }
    Spacer(Modifier.height(4.dp))
    Row {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onPair(code) }, enabled = code.length == 6) { Text("Pair", color = Amplo.Accent) }
    }
}

@Composable
private fun Summary(computer: Computer, plan: SyncPlan) {
    Body("Nothing is deleted. Each side gets what it does not have.")
    Spacer(Modifier.height(12.dp))
    Line("To the phone", songsAndSize(plan.toPhone))
    Line("To ${computer.name}", songsAndSize(plan.toComputer))
    val playlists = plan.playlistNames.size
    Line("Playlists that change", if (playlists == 0) "None" else playlists.toString())
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Amplo.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Amplo.Text)
    }
}

@Composable
private fun Progress(state: SyncState.Running) {
    val fraction = if (state.totalBytes > 0) (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
    Body(if (state.done < state.total) "Copying ${state.done + 1} of ${state.total}" else "Changing the playlists")
    Spacer(Modifier.height(6.dp))
    Text(state.current, style = MaterialTheme.typography.bodyLarge, color = Amplo.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth(),
        color = Amplo.Accent,
        trackColor = Amplo.Line,
    )
    Spacer(Modifier.height(6.dp))
    Text("${size(state.bytes)} of ${size(state.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = Amplo.Muted)
    Spacer(Modifier.height(10.dp))
    Body("Keep amplo open on both devices until the sync ends.", Amplo.Muted)
}

@Composable
private fun Finished(message: String, problems: List<String>) {
    Body(message, Amplo.Text)
    if (problems.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            if (problems.size == 1) "1 problem" else "${problems.size} problems",
            style = MaterialTheme.typography.labelSmall,
            color = Amplo.Warning,
        )
        for (problem in problems) {
            Spacer(Modifier.height(4.dp))
            Text(problem, style = MaterialTheme.typography.bodySmall, color = Amplo.Soft)
        }
    }
}

private fun songsAndSize(songs: List<SyncSong>): String = when (songs.size) {
    0 -> "Nothing"
    1 -> "1 song · ${size(songs.sumOf(SyncSong::size))}"
    else -> "${songs.size} songs · ${size(songs.sumOf(SyncSong::size))}"
}

/** "820 KB", "12.4 MB" or "3.1 GB". */
internal fun size(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0 || value >= 100) "${value.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", value, units[unit])
}
