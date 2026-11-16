package com.dewangga.amplo.lock

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dewangga.amplo.LYRICS_EXTRA
import com.dewangga.amplo.library.Extra
import com.dewangga.amplo.library.Lyrics
import com.dewangga.amplo.library.LyricsFiles
import com.dewangga.amplo.library.MediaStoreScanner
import com.dewangga.amplo.library.Track
import com.dewangga.amplo.likedSongs
import com.dewangga.amplo.playback.PlayerConnection
import com.dewangga.amplo.settings
import com.dewangga.amplo.toggleLike
import com.dewangga.amplo.ui.theme.AmploTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The permission that lets amplo open its lock screen player from the background. Android 10
 * and later need it. The user gives it in the system settings.
 */
object LockAccess {
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    /** Opens the system setting for "Display over other apps". */
    fun ask(context: Context) {
        val page = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(page)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
}

/**
 * The player on the lock screen. [com.dewangga.amplo.playback.PlaybackService] opens it when the
 * screen goes off while music plays. It closes when the user swipes it up or unlocks the phone.
 */
class LockActivity : ComponentActivity() {
    private val model: LockModel by viewModels()

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The manifest shows it on the lock screen from Android 8.1. Android 8.0 needs the flag.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        // Only the system sends this. Android does not give it to a receiver that is not exported.
        ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        setContent {
            AmploTheme { LockScreen(model, onClose = ::close) }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(unlocked)
        super.onDestroy()
    }

    private fun close() {
        if (isFinishing) return
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, android.R.anim.fade_out)
        }
    }
}

/** The song that plays, with its heart and lyrics, read without the whole library. */
class LockModel(application: Application) : AndroidViewModel(application) {
    val player = PlayerConnection(application)

    private val trackFlow = MutableStateFlow<Track?>(null)
    /** The song that plays, as MediaStore has it. Null until it is read. */
    val track: StateFlow<Track?> = trackFlow.asStateFlow()

    private val lyricsFlow = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = lyricsFlow.asStateFlow()

    val liked: StateFlow<Set<String>> = application.likedSongs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** What shows under each lyric line, as chosen in Now Playing. */
    val lyricsExtra: StateFlow<Extra?> = application.settings.data
        .map { settings -> Extra.entries.firstOrNull { it.name == settings[LYRICS_EXTRA] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            player.playerState.map { it.trackId }.distinctUntilChanged().collectLatest { id ->
                trackFlow.value = null
                lyricsFlow.value = null
                if (id == null) return@collectLatest
                withContext(Dispatchers.IO) {
                    val track = MediaStoreScanner.scan(application, id).firstOrNull()
                    trackFlow.value = track
                    lyricsFlow.value = track?.let { LyricsFiles.read(application, it) }
                }
            }
        }
    }

    fun toggleLike(track: Track) {
        viewModelScope.launch { getApplication<Application>().toggleLike(track) }
    }

    override fun onCleared() = player.release()
}
