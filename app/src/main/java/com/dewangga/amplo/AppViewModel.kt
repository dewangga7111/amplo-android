package com.dewangga.amplo

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dewangga.amplo.library.MediaStoreScanner
import com.dewangga.amplo.library.Sort
import com.dewangga.amplo.library.Track
import com.dewangga.amplo.library.sortedBy
import com.dewangga.amplo.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.settings by preferencesDataStore("settings")
private val SORT = stringPreferencesKey("sort")

/** The library, the sort and the player, shared by the screens. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val tracks = MutableStateFlow<List<Track>?>(null)

    /** Null until the saved sort is read. */
    val sort: StateFlow<Sort?> = application.settings.data
        .map { Sort.fromKey(it[SORT]) ?: Sort.RecentlyAdded }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Every song in the order of [sort], or null until the library is read. */
    val songs: StateFlow<List<Track>?> = combine(tracks, sort) { tracks, sort ->
        if (tracks == null || sort == null) null else tracks.sortedBy(sort)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val player = PlayerConnection(application)

    private var scan: Job? = null

    /** Reads the library again a moment after MediaStore changes, such as when songs are copied. */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scan(delayMs = 1000)
    }
    private var watching = false

    /** Reads the library and follows its changes. Call once the app may read music. */
    fun open() {
        if (watching) return
        watching = true
        getApplication<Application>().contentResolver
            .registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        scan()
    }

    private fun scan(delayMs: Long = 0) {
        scan?.cancel()
        scan = viewModelScope.launch {
            delay(delayMs)
            tracks.value = withContext(Dispatchers.IO) { MediaStoreScanner.scan(getApplication()) }
        }
    }

    fun setSort(sort: Sort) {
        viewModelScope.launch { getApplication<Application>().settings.edit { it[SORT] = sort.key } }
    }

    override fun onCleared() {
        if (watching) getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        player.release()
    }
}
