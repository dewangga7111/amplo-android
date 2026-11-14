package com.dewangga.amplo.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The rate and channels of the decoded audio. */
data class AudioShape(val sampleRate: Int, val channels: Int)

/**
 * Copies the decoded audio of [PlaybackService] for the visualizer. The service runs in the
 * app's process, so the screens read this object directly.
 */
@OptIn(UnstableApi::class)
object AudioTap : TeeAudioProcessor.AudioBufferSink {
    /**
     * How long the output holds audio before it is heard. Media3 gives the output a buffer of
     * 250 ms for most songs, and the device adds a little.
     */
    private const val OUTPUT_DELAY_NANOS = 280_000_000L

    val ring = SampleRing()
    private val shapeFlow = MutableStateFlow<AudioShape?>(null)
    val shape: StateFlow<AudioShape?> = shapeFlow.asStateFlow()

    @Volatile private var encoding = C.ENCODING_INVALID
    @Volatile private var channels = 0
    @Volatile private var lastWrite = 0L
    /** Only the playback thread uses it. */
    private var mono = FloatArray(4096)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
        channels = channelCount
        ring.clear()
        if (sampleRateHz > 0 && channelCount > 0) shapeFlow.value = AudioShape(sampleRateHz, channelCount)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val count = channels
        if (count <= 0) return
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frames = data.remaining() / (bytes * count)
        if (mono.size < frames) mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            repeat(count) { sum += if (bytes == 2) data.short / 32768f else data.float }
            mono[frame] = sum / count
        }
        ring.write(mono, frames)
        lastWrite = System.nanoTime()
    }

    /**
     * Copies the [FFT_SIZE] samples that are heard now into [into]. Returns the sample rate,
     * or 0 when there are none yet.
     */
    fun heardNow(into: FloatArray, now: Long = System.nanoTime()): Int {
        val rate = shapeFlow.value?.sampleRate ?: return 0
        // Audio arrives in pieces, so the time since the last piece moves the window on.
        val since = (now - lastWrite).coerceIn(0, OUTPUT_DELAY_NANOS)
        val behind = (OUTPUT_DELAY_NANOS - since) * rate / 1_000_000_000L
        return if (ring.copy(ring.count() - behind, into)) rate else 0
    }
}
