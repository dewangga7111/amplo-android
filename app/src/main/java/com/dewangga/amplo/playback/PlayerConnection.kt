package com.dewangga.amplo.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dewangga.amplo.library.Track
import com.dewangga.amplo.library.songUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the player plays, for the screens. */
data class PlayerState(
    /** The MediaStore id of the current song, or null when the queue is empty. */
    val trackId: Long? = null,
    val title: String = "",
    val artist: String = "",
    /** True while playing or about to play, so the button shows Pause. */
    val playing: Boolean = false,
    val durationMs: Long = 0,
)

/** Controls [PlaybackService] from the screens. Calls made before it connects run once it does. */
class PlayerConnection(context: Context) {
    private val state = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = state.asStateFlow()

    private var controller: MediaController? = null
    private val waiting = mutableListOf<(MediaController) -> Unit>()
    private val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update(player)
    }

    init {
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            update(connected)
            waiting.forEach { it(connected) }
            waiting.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Where the current song is, in milliseconds. */
    val positionMs: Long get() = controller?.currentPosition ?: 0

    /** Plays [tracks] from the one at [index], or all of them in random order with [shuffle]. */
    fun play(tracks: List<Track>, index: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val items = tracks.map(::mediaItem)
        val start = if (shuffle) tracks.indices.random() else index
        withController {
            it.shuffleModeEnabled = shuffle
            it.setMediaItems(items, start, 0)
            it.prepare()
            it.play()
        }
    }

    fun togglePlay() = withController {
        when {
            it.playWhenReady && it.playbackState != Player.STATE_ENDED -> it.pause()
            else -> {
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
                it.play()
            }
        }
    }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    fun release() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        controller = null
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: waiting.add(action)
    }

    private fun update(player: Player) {
        val item = player.currentMediaItem
        val metadata = player.mediaMetadata
        state.value = PlayerState(
            trackId = item?.mediaId?.toLongOrNull(),
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && item != null,
            durationMs = player.duration.coerceAtLeast(0),
        )
    }

    private fun mediaItem(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri(songUri(track.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist.ifEmpty { null })
                .setAlbumTitle(track.album.ifEmpty { null })
                .setAlbumArtist(track.albumArtist.ifEmpty { null })
                .setGenre(track.genre.ifEmpty { null })
                .setArtworkUri(songUri(track.id))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()
}
