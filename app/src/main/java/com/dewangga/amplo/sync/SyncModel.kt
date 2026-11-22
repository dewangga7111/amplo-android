package com.dewangga.amplo.sync

import android.app.Application
import android.content.IntentSender
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dewangga.amplo.library.LyricsFiles
import com.dewangga.amplo.library.M3u
import com.dewangga.amplo.library.NeedsConsent
import com.dewangga.amplo.library.Playlist
import com.dewangga.amplo.library.PlaylistFiles
import com.dewangga.amplo.library.Track
import com.dewangga.amplo.library.TrackIndex
import com.dewangga.amplo.library.songUri
import com.dewangga.amplo.settings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where a sync with a computer is. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Searching : SyncState

    /** More than one computer answered. */
    data class Choose(val computers: List<Computer>) : SyncState

    /** No computer answered. The user can type its address. [error] tells why the last try failed. */
    data class NotFound(val error: String? = null) : SyncState

    /** The computer waits for the code it shows. */
    data class NeedCode(val computer: Computer, val error: String? = null) : SyncState

    data class Planning(val computer: Computer) : SyncState

    /** What the sync copies, before it starts. */
    data class Ready(val computer: Computer, val plan: SyncPlan) : SyncState

    data class Running(
        val computer: Computer,
        val done: Int,
        val total: Int,
        val current: String,
        val bytes: Long,
        val totalBytes: Long,
    ) : SyncState

    data class Done(val message: String, val problems: List<String>) : SyncState

    data class Failed(val message: String) : SyncState
}

private val SYNC_COMPUTER = stringPreferencesKey("sync_computer")
private val SYNC_NAME = stringPreferencesKey("sync_name")
private val SYNC_KEY = stringPreferencesKey("sync_key")
private const val TAG = "amplo-sync"

/**
 * Syncs the songs and playlists of the phone with amplo on a computer, over the same Wi-Fi.
 * Each side gets what it does not have. Nothing is deleted.
 */
class SyncModel(
    private val context: Application,
    private val scope: CoroutineScope,
    /** The songs of the phone, and its playlists with the hidden ones. */
    private val readLibrary: suspend () -> Pair<List<Track>, List<Playlist>>,
    /** Asks the user to allow changes to playlist files, then calls one of the two functions. */
    private val askConsent: (request: IntentSender, retry: () -> Unit, denied: () -> Unit) -> Unit,
) {
    private val stateFlow = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = stateFlow.asStateFlow()

    private var connection: SyncConnection? = null
    private var job: Job? = null

    /** The phone side of the plan: songs by the key of their relative path, and playlists by name. */
    private var phoneSongs: Map<String, Track> = emptyMap()
    private var phonePlaylists: Map<String, Playlist> = emptyMap()
    private var phoneTracks: List<Track> = emptyList()

    /** Finds the computer, the saved one first, and shows what a sync would copy. */
    fun start() = launch {
        stateFlow.value = SyncState.Searching
        val settings = context.settings.data.first()
        val saved = settings[SYNC_COMPUTER]?.let { Computer.parse(it, settings[SYNC_NAME] ?: it) }
        if (saved != null && connect(saved, timeoutMs = 2000)) return@launch
        val found = io { SyncConnection.discover() }
        when (found.size) {
            0 -> stateFlow.value = SyncState.NotFound()
            1 -> if (!connect(found[0])) stateFlow.value = SyncState.NotFound(unreachable(found[0]))
            else -> stateFlow.value = SyncState.Choose(found)
        }
    }

    fun choose(computer: Computer) = launch {
        stateFlow.value = SyncState.Searching
        if (!connect(computer)) stateFlow.value = SyncState.NotFound(unreachable(computer))
    }

    /** Connects to the address the user typed, such as "192.168.1.20". */
    fun connectTo(address: String) {
        val computer = Computer.parse(address) ?: run {
            stateFlow.value = SyncState.NotFound("“$address” is not an address. Type the one amplo shows on the computer.")
            return
        }
        choose(computer)
    }

    fun pair(computer: Computer, code: String) = launch {
        val connection = connection ?: return@launch
        stateFlow.value = SyncState.Planning(computer)
        val answer = try {
            io { connection.ask(JSONObject().put("op", "pair").put("code", code)) }
        } catch (refused: SyncRefused) {
            stateFlow.value = SyncState.NeedCode(computer, refused.message)
            return@launch
        }
        val key = answer.getString("key")
        connection.key = key
        context.settings.edit { it[SYNC_KEY] = key }
        remember(computer)
        plan(computer)
    }

    /** Copies what the plan says. */
    fun run() {
        val ready = stateFlow.value as? SyncState.Ready ?: return
        launch { copy(ready.computer, ready.plan) }
    }

    /** Stops the sync and closes the connection. A file being copied is left out. */
    fun close() {
        job?.cancel()
        job = null
        connection?.close()
        connection = null
        stateFlow.value = SyncState.Idle
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        job?.cancel()
        return scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: SyncRefused) {
                if (!isActive) return@launch
                stateFlow.value = SyncState.Failed(error.message ?: "The computer refused the sync.")
            } catch (error: Exception) {
                // Closing the connection stops a copy with an error. That is not a failure.
                if (!isActive) return@launch
                Log.w(TAG, "Sync failed", error)
                stateFlow.value = SyncState.Failed("Lost the connection to the computer: ${error.message ?: error.javaClass.simpleName}")
            }
        }.also { job = it }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun unreachable(computer: Computer) =
        "Could not reach ${computer.name}. Check that amplo runs on it with sync on, and that both are on the same Wi-Fi."

    /** Says hello, then pairs or plans. False when the computer does not answer. */
    private suspend fun connect(computer: Computer, timeoutMs: Int = 5000): Boolean {
        connection?.close()
        connection = null
        val opened = try {
            io { SyncConnection(computer, timeoutMs) }
        } catch (_: IOException) {
            return false
        }
        val hello = try {
            opened.key = context.settings.data.first()[SYNC_KEY]
            io { opened.ask(JSONObject().put("op", "hello")) }
        } catch (error: Exception) {
            opened.close()
            if (error is CancellationException) throw error
            return false
        }
        connection = opened
        if (hello.optInt("version") != SyncConnection.VERSION) {
            stateFlow.value = SyncState.Failed("amplo on the computer and on the phone do not match. Update both.")
            return true
        }
        val named = computer.copy(name = hello.optString("name").ifEmpty { computer.name })
        if (hello.optBoolean("paired")) {
            remember(named)
            plan(named)
        } else {
            stateFlow.value = SyncState.NeedCode(named)
        }
        return true
    }

    private suspend fun remember(computer: Computer) {
        context.settings.edit {
            it[SYNC_COMPUTER] = computer.address
            it[SYNC_NAME] = computer.name
        }
    }

    private suspend fun plan(computer: Computer) {
        val connection = connection ?: return
        stateFlow.value = SyncState.Planning(computer)
        val (computerSongs, computerPlaylists) = SyncConnection.parseLibrary(io { connection.ask(JSONObject().put("op", "library")) })
        val (tracks, playlists) = readLibrary()
        val songs = LinkedHashMap<String, Track>()
        for (track in tracks) {
            val relative = SyncPlan.relativePath(track.path) ?: continue
            songs.putIfAbsent(M3u.pathKey(relative), track)
        }
        val byId = tracks.associateBy(Track::id)
        val phoneSide = playlists.map { playlist ->
            SyncPlaylist(playlist.name, playlist.tracks.mapNotNull { id -> byId[id]?.path?.let(SyncPlan::relativePath) })
        }
        phoneTracks = tracks
        phoneSongs = songs
        phonePlaylists = playlists.associateBy { it.name.lowercase() }
        val phone = songs.values.map { SyncSong(SyncPlan.relativePath(it.path)!!, it.size) }
        val plan = SyncPlan.make(computerSongs, computerPlaylists, phone, phoneSide)
        stateFlow.value = if (plan.isEmpty) {
            SyncState.Done("The phone and ${computer.name} have the same songs and playlists.", emptyList())
        } else {
            SyncState.Ready(computer, plan)
        }
    }

    private suspend fun CoroutineScope.copy(computer: Computer, plan: SyncPlan) {
        val connection = connection ?: return
        val total = plan.toPhone.size + plan.toComputer.size
        val totalBytes = plan.toPhone.sumOf(SyncSong::size) + plan.toComputer.sumOf(SyncSong::size)
        var done = 0
        var bytes = 0L
        val problems = ArrayList<String>()
        fun show(name: String, sent: Long) {
            stateFlow.value = SyncState.Running(computer, done, total, name, bytes + sent, totalBytes)
        }
        // The full path of each song on the phone, by the key of its relative path.
        val paths = phoneSongs.mapValuesTo(HashMap()) { it.value.path }

        var toPhone = 0
        for (song in plan.toPhone) {
            ensureActive()
            val name = song.path.substringAfterLast('/')
            show(name, 0)
            try {
                val path = io {
                    val file = PhoneFiles.create(context, song.path)
                    try {
                        connection.receive(JSONObject().put("op", "get").put("path", song.path), file.output) { show(name, it) }
                        file.finish()
                    } catch (error: Exception) {
                        file.abandon()
                        throw error
                    }
                }
                paths[M3u.pathKey(song.path)] = path
                toPhone++
                if (song.lyrics) {
                    try {
                        io { receiveLyrics(connection, song.path, path) }
                    } catch (refused: SyncRefused) {
                        problems += "The lyrics of $name: ${refused.message}"
                    }
                }
            } catch (refused: SyncRefused) {
                problems += "$name: ${refused.message}"
            }
            done++
            bytes += song.size
        }

        var toComputer = 0
        for (song in plan.toComputer) {
            ensureActive()
            val track = phoneSongs[M3u.pathKey(song.path)] ?: continue
            val name = song.path.substringAfterLast('/')
            show(name, 0)
            try {
                io {
                    context.contentResolver.openFileDescriptor(songUri(track.id), "r")?.use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { input ->
                            val request = JSONObject().put("op", "put").put("path", song.path)
                            connection.send(request, input, descriptor.statSize) { show(name, it) }
                        }
                    } ?: throw SyncRefused("Android did not open the song.")
                }
                toComputer++
                io { LyricsFiles.readText(context, track) }?.let { text ->
                    val lyrics = text.toByteArray()
                    val request = JSONObject().put("op", "put").put("path", song.path.substringBeforeLast('.') + ".lrc")
                    try {
                        io { connection.send(request, ByteArrayInputStream(lyrics), lyrics.size.toLong()) {} }
                    } catch (_: SyncRefused) {
                        // The computer has lyrics for this song already.
                    }
                }
            } catch (refused: SyncRefused) {
                problems += "$name: ${refused.message}"
            }
            done++
            bytes += song.size
        }

        show("Playlists", 0)
        if (plan.computerPlaylists.isNotEmpty()) {
            val list = JSONArray()
            for (change in plan.computerPlaylists) {
                list.put(JSONObject().put("name", change.name).put("songs", JSONArray(change.songs)))
            }
            val answer = io { connection.ask(JSONObject().put("op", "playlists").put("playlists", list)) }
            val results = answer.optJSONArray("playlists") ?: JSONArray()
            for (index in 0 until results.length()) {
                val result = results.getJSONObject(index)
                if (!result.optBoolean("ok")) problems += "${result.optString("name")} on the computer: ${result.optString("error")}"
            }
        }
        val waiting = io { changePhonePlaylists(plan.phonePlaylists, paths, problems) }

        try {
            io { connection.ask(JSONObject().put("op", "done").put("toPhone", toPhone).put("toComputer", toComputer)) }
        } catch (_: Exception) {
            // Only a message on the computer.
        }
        val changed = plan.playlistNames.size
        val message = buildString {
            append("Synced with ${computer.name}. ")
            append("${songs(toPhone)} new on the phone, ${songs(toComputer)} new on the computer")
            if (changed > 0) append(", ${if (changed == 1) "1 playlist" else "$changed playlists"} changed")
            append('.')
        }
        stateFlow.value = SyncState.Done(message, problems)
        if (waiting.isNotEmpty()) askToChange(waiting, message, problems)
    }

    /** Writes a lyrics file the computer has for a song, beside the song on the phone. */
    private fun receiveLyrics(connection: SyncConnection, song: String, phonePath: String) {
        val bytes = ByteArrayOutputStream()
        connection.receive(JSONObject().put("op", "get").put("path", song.substringBeforeLast('.') + ".lrc"), bytes) {}
        val text = String(bytes.toByteArray(), Charsets.UTF_8)
        try {
            LyricsFiles.write(context, track(phonePath), text)
        } catch (error: Exception) {
            // The song is there, so the sync goes on without its lyrics.
            Log.w(TAG, "Could not save the lyrics of $phonePath", error)
        }
    }

    private fun track(path: String) = Track(
        id = 0, title = "", artist = "", album = "", albumArtist = "", genre = "",
        disc = 0, number = 0, durationMs = 0, added = 0, path = path,
    )

    /**
     * Adds songs to the playlists of the phone, and makes the missing ones. Returns the new text
     * of the playlists that Android lets amplo change only after the user allows it.
     */
    private fun changePhonePlaylists(
        changes: List<PlaylistChange>,
        paths: Map<String, String>,
        problems: MutableList<String>,
    ): List<Pair<Playlist, String>> {
        val index = TrackIndex(phoneTracks)
        val waiting = ArrayList<Pair<Playlist, String>>()
        for (change in changes) {
            val songs = change.songs.mapNotNull { paths[M3u.pathKey(it)] }
            val playlist = phonePlaylists[change.name.lowercase()]
            try {
                if (change.create || playlist == null) {
                    PlaylistFiles.createFromPaths(context, change.name, songs)
                    continue
                }
                val (text, added) = M3u.add(PlaylistFiles.read(context, playlist.id), playlist.folder, songs, index::identify)
                if (added == 0) continue
                try {
                    PlaylistFiles.write(context, playlist, text)
                } catch (_: NeedsConsent) {
                    waiting += playlist to text
                }
            } catch (error: Exception) {
                problems += "${change.name} on the phone: ${error.message}"
            }
        }
        return waiting
    }

    /** Asks once for all playlists that need the user's permission, then writes them. */
    private fun askToChange(waiting: List<Pair<Playlist, String>>, message: String, problems: List<String>) {
        val names = waiting.joinToString { it.first.name }
        val request = PlaylistFiles.writeRequest(context, waiting.map { it.first })
        if (request == null) {
            stateFlow.value = SyncState.Done(message, problems + "Android did not let amplo change $names.")
            return
        }
        askConsent(
            request,
            {
                scope.launch {
                    val failed = io {
                        waiting.filter { (playlist, text) ->
                            try {
                                PlaylistFiles.write(context, playlist, text)
                                false
                            } catch (error: Exception) {
                                Log.w(TAG, "Could not change ${playlist.path}", error)
                                true
                            }
                        }
                    }
                    if (failed.isNotEmpty()) {
                        val left = failed.joinToString { it.first.name }
                        stateFlow.value = SyncState.Done(message, problems + "Android did not let amplo change $left.")
                    }
                }
            },
            { stateFlow.value = SyncState.Done(message, problems + "Not changed, as Android did not allow it: $names.") },
        )
    }

    private fun songs(count: Int) = if (count == 1) "1 song" else "$count songs"
}
