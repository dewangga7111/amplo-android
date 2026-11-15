package com.dewangga.amplo.spotify

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kept apart from the other settings, so it can stay out of backups. */
private val Context.spotifySettings by preferencesDataStore("spotify")
private val CLIENT_ID = stringPreferencesKey("client_id")
private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
private const val TAG = "amplo"

/** Something that is read from Spotify: still loading, there, or failed with a message. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

sealed interface Login {
    /** The saved login is not read yet. */
    data object Reading : Login
    data object LoggedOut : Login
    /** The login page is open in the browser. */
    data class Waiting(val url: String) : Login
    data object LoggedIn : Login
}

/** The Spotify login, and the playlists and songs read with it. */
class SpotifyModel(private val context: Context, private val scope: CoroutineScope) {
    private val loginFlow = MutableStateFlow<Login>(Login.Reading)
    val login: StateFlow<Login> = loginFlow.asStateFlow()

    private val clientIdFlow = MutableStateFlow("")
    /** The Client ID of the last login, to fill the field with. */
    val clientId: StateFlow<String> = clientIdFlow.asStateFlow()

    private val errorFlow = MutableStateFlow<String?>(null)
    /** Why the last login or request failed. */
    val error: StateFlow<String?> = errorFlow.asStateFlow()

    private val userFlow = MutableStateFlow<SpotifyUser?>(null)
    val user: StateFlow<SpotifyUser?> = userFlow.asStateFlow()

    private val playlistsFlow = MutableStateFlow<Load<List<SpotifyPlaylist>>?>(null)
    /** Null until they are asked for. */
    val playlists: StateFlow<Load<List<SpotifyPlaylist>>?> = playlistsFlow.asStateFlow()

    private val tracksFlow = MutableStateFlow<Map<String, Load<List<SpotifyTrack>>>>(emptyMap())
    /** The songs of each playlist that was opened, by playlist id. */
    val tracks: StateFlow<Map<String, Load<List<SpotifyTrack>>>> = tracksFlow.asStateFlow()

    private var session: SpotifySession? = null
    private var pending: Spotify.PendingLogin? = null
    private var loading: Job? = null

    init {
        scope.launch {
            val settings = context.spotifySettings.data.first()
            val id = settings[CLIENT_ID].orEmpty()
            val token = settings[REFRESH_TOKEN]
            clientIdFlow.value = id
            // The sample can come first.
            if (loginFlow.value != Login.Reading) return@launch
            if (id.isNotEmpty() && token != null) {
                session = newSession(id, token)
                loginFlow.value = Login.LoggedIn
            } else {
                loginFlow.value = Login.LoggedOut
            }
        }
    }

    private fun newSession(clientId: String, refresh: String, access: String = "", lifetimeMs: Long = 0) =
        SpotifySession(clientId, refresh, access, lifetimeMs) { token ->
            scope.launch { context.spotifySettings.edit { it[REFRESH_TOKEN] = token } }
        }

    /**
     * Opens the login server and gives [openPage] the login page to show in the browser. The
     * login goes on when the browser comes back.
     */
    fun logIn(clientId: String, openPage: (String) -> Unit) {
        if (loginFlow.value !is Login.LoggedOut) return
        errorFlow.value = null
        val login = try {
            Spotify.startLogin(clientId)
        } catch (failure: SpotifyFailure) {
            errorFlow.value = failure.message
            return
        }
        pending = login
        clientIdFlow.value = login.clientId
        loginFlow.value = Login.Waiting(login.url)
        openPage(login.url)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val code = Spotify.waitForCode(login.server, login.state)
                    Spotify.exchangeCode(login.clientId, code, login.verifier)
                }
            }
            login.server.close()
            // A canceled login is already over.
            if (pending !== login) return@launch
            pending = null
            result.onSuccess { tokens ->
                val refresh = tokens.refresh
                if (refresh == null) {
                    loginFlow.value = Login.LoggedOut
                    errorFlow.value = "Spotify sent no refresh token."
                    return@onSuccess
                }
                context.spotifySettings.edit {
                    it[CLIENT_ID] = login.clientId
                    it[REFRESH_TOKEN] = refresh
                }
                session = newSession(login.clientId, refresh, tokens.access, tokens.lifetimeMs)
                forget()
                loginFlow.value = Login.LoggedIn
            }.onFailure { error ->
                loginFlow.value = Login.LoggedOut
                errorFlow.value = error.message ?: "The Spotify login failed."
            }
        }
    }

    /** Stops waiting for the browser. */
    fun cancelLogin() {
        val login = pending ?: return
        pending = null
        // Closing the server ends the wait on the background thread, and frees the port for the
        // next login.
        login.server.close()
        loginFlow.value = Login.LoggedOut
    }

    fun logOut() {
        cancelLogin()
        session = null
        loading?.cancel()
        forget()
        errorFlow.value = null
        loginFlow.value = Login.LoggedOut
        scope.launch { context.spotifySettings.edit { it.remove(REFRESH_TOKEN) } }
    }

    private fun forget() {
        userFlow.value = null
        playlistsFlow.value = null
        tracksFlow.value = emptyMap()
    }

    /** The saved login stopped working, so the user has to log in again. */
    private fun loggedOut() {
        logOut()
        errorFlow.value = LOGIN_EXPIRED
    }

    /**
     * Shows made-up playlists without a login, so a debug build can show the logged-in pages
     * without a Spotify account.
     */
    fun showSample() {
        cancelLogin()
        session = null
        val road = SpotifyPlaylist("sample-road", "Road Trip Mix", "amplo", 4, null)
        val calm = SpotifyPlaylist("sample-calm", "Calm", "Someone else", 2, null)
        val empty = SpotifyPlaylist("sample-empty", "Nothing Yet", "amplo", 0, null)
        fun track(name: String, vararg artists: String) = SpotifyTrack(name, artists.toList(), "Sample Album", null, 200_000)
        userFlow.value = SpotifyUser("sample", "Sample User")
        playlistsFlow.value = Load.Done(listOf(road, calm, empty))
        tracksFlow.value = mapOf(
            road.id to Load.Done(
                listOf(
                    track("Neon Avenue", "Kaito Rivers"),
                    track("Tail Lights (feat. Mira Sol)", "Kaito Rivers"),
                    track("Not On This Phone", "Nobody"),
                    track("Neon Avenue - Radio Edit", "Kaito Rivers"),
                ),
            ),
            calm.id to Load.Failed(NOT_SHARED),
            empty.id to Load.Done(emptyList()),
        )
        errorFlow.value = null
        loginFlow.value = Login.LoggedIn
    }

    /** Reads the playlists once they are needed. */
    fun loadPlaylists() {
        if (playlistsFlow.value == null) refresh()
    }

    /** Reads the playlists again, and forgets the songs read before. */
    fun refresh() {
        val session = session ?: return
        loading?.cancel()
        playlistsFlow.value = Load.Loading
        tracksFlow.value = emptyMap()
        loading = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { session.playlists() } }
            if (this@SpotifyModel.session !== session) return@launch
            result.onSuccess { (user, playlists) ->
                userFlow.value = user
                playlistsFlow.value = Load.Done(playlists)
            }.onFailure { error ->
                if (error is SpotifyFailure.LoggedOut) return@launch loggedOut()
                Log.w(TAG, "Could not read the Spotify playlists", error)
                playlistsFlow.value = Load.Failed(error.message ?: "Could not read your Spotify playlists.")
            }
        }
    }

    /** Reads the songs of a playlist, unless they are read already. */
    fun loadTracks(id: String) {
        val session = session ?: return
        val known = tracksFlow.value[id]
        if (known != null && known !is Load.Failed) return
        tracksFlow.update { it + (id to Load.Loading) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { session.tracks(id) } }
            if (this@SpotifyModel.session !== session) return@launch
            result.onSuccess { tracks ->
                tracksFlow.update { it + (id to Load.Done(tracks)) }
            }.onFailure { error ->
                if (error is SpotifyFailure.LoggedOut) return@launch loggedOut()
                Log.w(TAG, "Could not read the songs of a Spotify playlist", error)
                tracksFlow.update { it + (id to Load.Failed(error.message ?: "Could not read the songs.")) }
            }
        }
    }
}
