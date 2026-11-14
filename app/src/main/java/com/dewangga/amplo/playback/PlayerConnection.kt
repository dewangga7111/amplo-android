package com.dewangga.amplo.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dewangga.amplo.library.SongList
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
    val shuffle: Boolean = false,
    /** The current song plays again when it ends. Otherwise the queue goes on, and wraps around. */
    val repeatOne: Boolean = false,
    /** The list the queue came from, such as "All Songs". */
    val source: String = "",
    /** What [source] is, such as "PLAYLIST" or "ALBUM". */
    val sourceKind: String = "",
    /** The key of the list the queue came from, to open it again. Empty when not known. */
    val sourceKey: String = "",
)

/** A song of the queue. [index] is its place in the queue as it was given, which shuffle does not change. */
data class QueueEntry(val index: Int, val trackId: Long, val title: String, val artist: String)

private const val SOURCE_KEY = "amplo.source"

/** Controls [PlaybackService] from the screens. Calls made before it connects run once it does. */
class PlayerConnection(context: Context) {
    private val state = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = state.asStateFlow()

    private val upcoming = MutableStateFlow<List<QueueEntry>>(emptyList())
    /** The songs after the current one, in the order they play. */
    val queue: StateFlow<List<QueueEntry>> = upcoming.asStateFlow()

    private var controller: MediaController? = null
    private val waiting = mutableListOf<(MediaController) -> Unit>()
    private val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            update(player)
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                )
            ) {
                updateQueue(player)
            }
        }
    }

    init {
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            update(connected)
            updateQueue(connected)
            waiting.forEach { it(connected) }
            waiting.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Where the current song is, in milliseconds. */
    val positionMs: Long get() = controller?.currentPosition ?: 0

    /**
     * Plays [tracks] from the one at [index]. True [shuffle] plays them in random order from a
     * random song, false in order, and null keeps the shuffle as it is. Now Playing shows the
     * name of [list] and opens it.
     */
    fun play(list: SongList, index: Int, shuffle: Boolean?) {
        val tracks = list.songs
        if (tracks.isEmpty()) return
        val items = tracks.map(::mediaItem)
        val start = if (shuffle == true) tracks.indices.random() else index
        withController {
            if (shuffle != null) it.shuffleModeEnabled = shuffle
            if (it.repeatMode == Player.REPEAT_MODE_OFF) it.repeatMode = Player.REPEAT_MODE_ALL
            it.playlistMetadata = MediaMetadata.Builder()
                .setTitle(list.name)
                .setSubtitle(list.sourceKind)
                .setExtras(Bundle().apply { putString(SOURCE_KEY, list.key) })
                .build()
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

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    /** Plays the song at [index] of the queue from its start. */
    fun playQueueEntry(index: Int) = withController {
        it.seekTo(index, 0)
        it.play()
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun toggleRepeat() = withController {
        it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE
    }

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
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            shuffle = player.shuffleModeEnabled,
            repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
            source = player.playlistMetadata.title?.toString().orEmpty(),
            sourceKind = player.playlistMetadata.subtitle?.toString().orEmpty(),
            sourceKey = player.playlistMetadata.extras?.getString(SOURCE_KEY).orEmpty(),
        )
    }

    /** The queue wraps around, so the songs before the current one follow at its end. */
    private fun updateQueue(player: Player) {
        val timeline = player.currentTimeline
        val current = player.currentMediaItemIndex
        if (timeline.isEmpty || current == C.INDEX_UNSET) {
            upcoming.value = emptyList()
            return
        }
        val window = Timeline.Window()
        val entries = ArrayList<QueueEntry>(timeline.windowCount)
        var index = timeline.getNextWindowIndex(current, Player.REPEAT_MODE_ALL, player.shuffleModeEnabled)
        while (index != C.INDEX_UNSET && index != current) {
            val item = timeline.getWindow(index, window).mediaItem
            val id = item.mediaId.toLongOrNull()
            if (id != null) {
                entries += QueueEntry(
                    index,
                    id,
                    item.mediaMetadata.title?.toString().orEmpty(),
                    item.mediaMetadata.artist?.toString().orEmpty(),
                )
            }
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_ALL, player.shuffleModeEnabled)
        }
        upcoming.value = entries
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
