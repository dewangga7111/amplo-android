package com.dewangga.amplo

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dewangga.amplo.library.Extra
import com.dewangga.amplo.library.ListKind
import com.dewangga.amplo.library.Lyrics
import com.dewangga.amplo.library.LyricsFiles
import com.dewangga.amplo.library.M3u
import com.dewangga.amplo.library.MediaStoreScanner
import com.dewangga.amplo.library.NeedsConsent
import com.dewangga.amplo.library.Playlist
import com.dewangga.amplo.library.PlaylistFiles
import com.dewangga.amplo.library.Sort
import com.dewangga.amplo.library.SongList
import com.dewangga.amplo.library.Track
import com.dewangga.amplo.library.TrackIndex
import com.dewangga.amplo.library.likeKey
import com.dewangga.amplo.library.sortedAsPlaylist
import com.dewangga.amplo.library.sortedBy
import com.dewangga.amplo.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val Context.settings by preferencesDataStore("settings")
private val SORT = stringPreferencesKey("sort")
private val LIKED = stringSetPreferencesKey("liked")
private val LYRICS_EXTRA = stringPreferencesKey("lyrics_extra")
private const val TAG = "amplo"

/** The songs and playlists of the device, as read together. */
private data class Library(val tracks: List<Track>, val playlists: List<Playlist>, val missing: Int)

/** A change to a playlist or lyrics file that waits for the user to allow it. */
class Consent(val request: IntentSender, internal val retry: () -> Unit, internal val denied: () -> Unit)

/** The lyrics of [track], or null when it has none. */
data class SongLyrics(val track: Track, val lyrics: Lyrics?)

/** The library, the sort, the likes and the player, shared by the screens. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Application get() = getApplication()
    private val library = MutableStateFlow<Library?>(null)

    /** Null until the saved sort is read. */
    val sort: StateFlow<Sort?> = application.settings.data
        .map { Sort.fromKey(it[SORT]) ?: Sort.RecentlyAdded }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The songs with a heart, by [likeKey]. */
    val liked: StateFlow<Set<String>> = application.settings.data
        .map { it[LIKED].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * All Songs, Liked Songs when it has songs, and the playlists, each in the order of [sort].
     * Null until the library is read.
     */
    val lists: StateFlow<List<SongList>?> = combine(library, sort, liked) { library, sort, liked ->
        if (library == null || sort == null) return@combine null
        val byId = library.tracks.associateBy(Track::id)
        buildList<SongList> {
            add(SongList(SongList.ALL, "All Songs", ListKind.All, library.tracks.sortedBy(sort)))
            val likedSongs = library.tracks.filter { it.likeKey in liked }
            if (likedSongs.isNotEmpty()) add(SongList(SongList.LIKED, "Liked Songs", ListKind.Liked, likedSongs.sortedBy(sort)))
            for (playlist in library.playlists) {
                val songs = playlist.tracks.mapNotNull(byId::get).sortedAsPlaylist(sort)
                add(SongList(SongList.playlistKey(playlist.path), playlist.name, ListKind.Playlist, songs, playlist))
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** How many playlist entries point to songs that are not on the device. */
    val missingEntries: StateFlow<Int> = library
        .map { it?.missing ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short confirmations, such as "Added 1 song to Mix." */
    val messages: SharedFlow<String> = notices

    private val consentFlow = MutableStateFlow<Consent?>(null)
    /** A change that waits for the user to allow amplo to change a playlist file. */
    val consent: StateFlow<Consent?> = consentFlow.asStateFlow()

    val player = PlayerConnection(application)

    private val lyricsFlow = MutableStateFlow<SongLyrics?>(null)
    /** The lyrics of the song that plays. Null until they are read, and while no song plays. */
    val lyrics: StateFlow<SongLyrics?> = lyricsFlow.asStateFlow()

    /** What shows under each lyric line when the lyrics have it: nothing, romaji or a translation. */
    val lyricsExtra: StateFlow<Extra?> = application.settings.data
        .map { settings -> Extra.entries.firstOrNull { it.name == settings[LYRICS_EXTRA] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var scan: Job? = null

    init {
        // The lyrics follow the song, and are read again with the library, as a computer may
        // have copied them since.
        viewModelScope.launch {
            combine(player.playerState.map { it.trackId }.distinctUntilChanged(), library) { id, library ->
                id?.let { library?.tracks?.firstOrNull { track -> track.id == it } }
            }.collectLatest { track ->
                if (track == null) {
                    lyricsFlow.value = null
                    return@collectLatest
                }
                if (lyricsFlow.value?.track?.id != track.id) lyricsFlow.value = null
                lyricsFlow.value = SongLyrics(track, withContext(Dispatchers.IO) { LyricsFiles.read(context, track) })
            }
        }
    }

    /** Reads the library again a moment after MediaStore changes, such as when songs are copied. */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scan(delayMs = 1000)
    }
    private var watching = false

    /** Reads the library and follows its changes. Call once the app may read music. */
    fun open() {
        if (watching) return
        watching = true
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        scan()
    }

    private fun scan(delayMs: Long = 0) {
        scan?.cancel()
        scan = viewModelScope.launch {
            delay(delayMs)
            library.value = read()
        }
    }

    private suspend fun read(): Library = withContext(Dispatchers.IO) {
        val tracks = MediaStoreScanner.scan(context)
        val (playlists, missing) = PlaylistFiles.scan(context, tracks)
        Library(tracks, playlists, missing)
    }

    /** The song with this MediaStore id, if the library has it. */
    fun track(id: Long): Track? = library.value?.tracks?.firstOrNull { it.id == id }

    fun setSort(sort: Sort) {
        viewModelScope.launch { context.settings.edit { it[SORT] = sort.key } }
    }

    fun toggleLike(track: Track) {
        viewModelScope.launch {
            context.settings.edit {
                val liked = it[LIKED].orEmpty()
                it[LIKED] = if (track.likeKey in liked) liked - track.likeKey else liked + track.likeKey
            }
        }
    }

    /** Adds songs to a playlist. Songs it has already are left out. */
    fun addToPlaylist(playlist: Playlist, songs: List<Track>) = change(playlist, { text, index ->
        M3u.add(text, playlist.folder, songs.map(Track::path), index::identify)
    }) { added ->
        val skipped = songs.size - added
        when {
            added == 0 && songs.size == 1 -> "Already in ${playlist.name}."
            added == 0 -> "These songs are already in ${playlist.name}."
            skipped == 0 -> "Added ${plural(added)} to ${playlist.name}."
            else -> "Added ${plural(added)} to ${playlist.name}. Skipped ${plural(skipped)} already in it."
        }
    }

    /** Takes songs out of a list. Liked Songs loses their hearts. The song files stay. */
    fun removeFromList(list: SongList, songs: List<Track>) {
        val playlist = list.playlist
        if (list.kind == ListKind.Liked) {
            viewModelScope.launch {
                context.settings.edit { it[LIKED] = it[LIKED].orEmpty() - songs.map { song -> song.likeKey }.toSet() }
                notify("Removed ${plural(songs.size)} from Liked Songs.")
            }
        } else if (playlist != null) {
            change(playlist, { text, index -> M3u.remove(text, playlist.folder, songs.map(Track::path), index::identify) }) { removed ->
                if (removed == 0) "Not in ${playlist.name}." else "Removed ${plural(removed)} from ${playlist.name}."
            }
        }
    }

    /**
     * Writes a new playlist into Music/Playlists with [songs] in it. [done] gets the key of the new
     * list once the library shows it, or a message why it was not made.
     */
    fun createPlaylist(name: String, songs: List<Track>, done: (key: String?, error: String?) -> Unit) {
        viewModelScope.launch {
            val path = try {
                withContext(Dispatchers.IO) { PlaylistFiles.create(context, name, songs) }
            } catch (error: IllegalArgumentException) {
                return@launch done(null, error.message)
            } catch (error: Exception) {
                return@launch done(null, "Could not create the playlist: ${error.message}")
            }
            scan?.cancel()
            library.value = read()
            if (songs.isNotEmpty()) notify("Added ${plural(songs.size)} to ${M3u.displayName(path.substringAfterLast('/'))}.")
            val key = SongList.playlistKey(path)
            // The lists follow the library a moment later, and the screen opens the new one by its key.
            withTimeoutOrNull(2000) { lists.first { all -> all?.any { it.key == key } == true } }
            done(key, null)
        }
    }

    /** Answers the question Android asked the user about changing a file. */
    fun answerConsent(allowed: Boolean) {
        val waiting = consentFlow.value ?: return
        consentFlow.value = null
        if (allowed) waiting.retry() else waiting.denied()
    }

    /** Shows the next of nothing, romaji and translation under each line of [lyrics]. */
    fun cycleLyricsExtra(lyrics: Lyrics) {
        val choices = lyrics.extraChoices
        val current = choices.indexOf(lyricsExtra.value?.takeIf(lyrics::has)).coerceAtLeast(0)
        val next = choices[(current + 1) % choices.size]
        viewModelScope.launch {
            context.settings.edit { if (next == null) it.remove(LYRICS_EXTRA) else it[LYRICS_EXTRA] = next.name }
        }
    }

    /**
     * Saves [text] as the `.lrc` file beside [track]. [done] gets null once it is saved, or a
     * message why it was not.
     */
    fun saveLyrics(track: Track, text: String, done: (error: String?) -> Unit) = saveLyrics(track, text, false, done)

    private fun saveLyrics(track: Track, text: String, allowed: Boolean, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { LyricsFiles.write(context, track, text) }
            } catch (need: NeedsConsent) {
                // Asking again after the user allowed it would not end.
                if (allowed) return@launch done("Android did not let amplo change the lyrics of ${track.title}.")
                consentFlow.value = Consent(
                    need.request,
                    retry = { saveLyrics(track, text, true, done) },
                    denied = { done("The lyrics were not changed.") },
                )
                return@launch
            } catch (error: IllegalArgumentException) {
                return@launch done(error.message)
            } catch (error: Exception) {
                Log.w(TAG, "Could not save the lyrics of ${track.path}", error)
                return@launch done("Could not save the lyrics: ${error.message}")
            }
            if (lyricsFlow.value?.track?.id == track.id || player.playerState.value.trackId == track.id) {
                lyricsFlow.value = SongLyrics(track, Lyrics.parse(text))
            }
            notify("Saved the lyrics of ${track.title.ifEmpty { "this song" }}.")
            done(null)
        }
    }

    /** Changes the text of a playlist file, then reads the playlists again. */
    private fun change(
        playlist: Playlist,
        edit: (String, TrackIndex) -> Pair<String, Int>,
        allowed: Boolean = false,
        message: (Int) -> String,
    ) {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val index = TrackIndex(library.value?.tracks.orEmpty())
                    val (text, count) = edit(PlaylistFiles.read(context, playlist.id), index)
                    if (count > 0) PlaylistFiles.write(context, playlist, text)
                    count
                }
                notify(message(count))
                if (count > 0) {
                    scan?.cancel()
                    library.value = read()
                }
            } catch (need: NeedsConsent) {
                // Asking again after the user allowed it would not end.
                if (allowed) {
                    notify("Android did not let amplo change ${playlist.name}.")
                } else {
                    consentFlow.value = Consent(
                        need.request,
                        retry = { change(playlist, edit, allowed = true, message) },
                        denied = { notify("The playlist was not changed.") },
                    )
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not change ${playlist.path}", error)
                notify("Could not change the playlist: ${error.message}")
            }
        }
    }

    private fun notify(text: String) {
        Log.i(TAG, text)
        notices.tryEmit(text)
    }

    override fun onCleared() {
        if (watching) context.contentResolver.unregisterContentObserver(observer)
        player.release()
    }
}

/** "1 song" or "3 songs". */
fun plural(count: Int) = if (count == 1) "1 song" else "$count songs"
