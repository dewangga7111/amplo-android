package com.dewangga.amplo.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.datastore.preferences.core.edit
import androidx.media3.common.Player
import com.dewangga.amplo.LOCK_PLAYER
import com.dewangga.amplo.MainActivity
import com.dewangga.amplo.REPEAT_ONE
import com.dewangga.amplo.SHUFFLE
import com.dewangga.amplo.lock.LockActivity
import com.dewangga.amplo.settings
import com.dewangga.amplo.library.Covers
import com.dewangga.amplo.library.songUri
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.MainScope
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.dewangga.amplo.library.MediaStoreScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often the queue is saved while music plays, so a closed app loses little. */
private const val SAVE_QUEUE_EVERY_MS = 15_000L

/** Plays the queue in the background and shows it in the notification and on the lock screen. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()
    /** Whether the user turned on the player on the lock screen. */
    @Volatile private var lockPlayer = false

    /** Shows the player on the lock screen when the screen goes off while music plays. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val player = session?.player ?: return
            if (!lockPlayer || !player.playWhenReady || player.mediaItemCount == 0) return
            // Android 10 and later let an app open a screen from the background only with this.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)) return
            try {
                startActivity(Intent(context, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: RuntimeException) {
                Log.w("amplo", "Could not show the player on the lock screen", error)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val music = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // The audio passes through AudioTap on its way out, for the visualizer.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioTap)))
                .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(music, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(Callback())
            .setBitmapLoader(CacheBitmapLoader(CoverBitmapLoader(this)))
            .setSessionActivity(open)
            .build()
        scope.launch { settings.data.collect { lockPlayer = it[LOCK_PLAYER] == true } }
        rememberModes(player)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Shuffle, repeat and the queue start as the user left them. Shuffle and repeat are saved
     * when they change, from the app, the notification or the lock screen. The queue is saved
     * when it changes, when the music pauses, every little while as it plays, and when the
     * service ends.
     */
    @OptIn(UnstableApi::class)
    private fun rememberModes(player: ExoPlayer) {
        scope.launch {
            val saved = settings.data.first()
            player.shuffleModeEnabled = saved[SHUFFLE] == true
            if (saved[REPEAT_ONE] == true) player.repeatMode = Player.REPEAT_MODE_ONE
            restoreQueue(player)
            player.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.containsAny(
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED,
                            Player.EVENT_PLAYLIST_METADATA_CHANGED,
                        )
                    ) {
                        saveQueue(player)
                    }
                }
            })
            while (true) {
                delay(SAVE_QUEUE_EVERY_MS)
                if (player.isPlaying) saveQueue(player)
            }
        }
        scope.launch {
            player.addListener(object : Player.Listener {
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    scope.launch { settings.edit { it[SHUFFLE] = shuffleModeEnabled } }
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    scope.launch { settings.edit { it[REPEAT_ONE] = repeatMode == Player.REPEAT_MODE_ONE } }
                }
            })
        }
    }

    /** Loads the saved queue paused, unless the app gave the player songs first. */
    @OptIn(UnstableApi::class)
    private suspend fun restoreQueue(player: ExoPlayer) {
        val restored = withContext(Dispatchers.IO) {
            val saved = SavedQueue.read(this@PlaybackService) ?: return@withContext null
            val tracks = MediaStoreScanner.scan(this@PlaybackService).associateBy { it.id }
            val queue = saved.keeping(tracks::containsKey) ?: return@withContext null
            queue to queue.ids.map { mediaItem(tracks.getValue(it)) }
        } ?: return
        val (queue, items) = restored
        if (player.mediaItemCount > 0) return
        player.playlistMetadata = MediaMetadata.Builder()
            .setTitle(queue.source)
            .setSubtitle(queue.sourceKind)
            .setExtras(Bundle().apply { putString(SOURCE_KEY, queue.sourceKey) })
            .build()
        player.setMediaItems(items, queue.index, queue.positionMs)
        if (player.shuffleModeEnabled && queue.shuffleOrder.isNotEmpty()) {
            player.setShuffleOrder(DefaultShuffleOrder(queue.shuffleOrder.toIntArray(), System.nanoTime()))
        }
        player.prepare()
    }

    /** Writes the queue in the background. [now] writes it at once, as the service ends. */
    private fun saveQueue(player: Player, now: Boolean = false) {
        val queue = SavedQueue.of(player)
        if (now) {
            SavedQueue.write(this, queue)
        } else {
            scope.launch(Dispatchers.IO) { SavedQueue.write(this@PlaybackService, queue) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops the service only when nothing plays. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            player?.let { saveQueue(it, now = true) }
            player?.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        session?.player?.let { saveQueue(it, now = true) }
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** The app sends songs by their MediaStore id. Their address is made here. */
    private class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.mapNotNull { item ->
                val id = item.mediaId.toLongOrNull() ?: return@mapNotNull null
                item.buildUpon().setUri(songUri(id)).build()
            }.toMutableList(),
        )
    }
}

/** Loads the covers of songs for the notification from the songs themselves. */
@UnstableApi
private class CoverBitmapLoader(private val context: Context) : BitmapLoader {
    private val other = DataSourceBitmapLoader(context)
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String) = other.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = other.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.authority != MediaStore.AUTHORITY) return other.loadBitmap(uri)
        val id = uri.lastPathSegment?.toLongOrNull() ?: return other.loadBitmap(uri)
        return executor.submit<Bitmap> { Covers.load(context, id, COVER_SIZE) ?: throw IOException("The song has no cover") }
    }

    private companion object {
        const val COVER_SIZE = 512
    }
}
