package com.dewangga.amplo.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The number of samples that one measurement of the visualizer reads. */
const val FFT_SIZE = 2048

private const val LOWEST_HZ = 50f
private const val HIGHEST_HZ = 14_000f
/** The level range that the bars show, from empty to full. */
private const val FLOOR_DB = -60f
private const val RANGE_DB = 48f

/**
 * The latest mono samples, written by the playback thread and read by the screen. Each sample
 * keeps its number since the last [clear], so the screen can read the ones that play now instead
 * of the newest, which the output still buffers.
 */
class SampleRing(capacity: Int = 1 shl 15) {
    private val samples = FloatArray(capacity)
    private var written = 0L

    @Synchronized
    fun clear() {
        written = 0
    }

    @Synchronized
    fun write(sample: Float) {
        samples[(written % samples.size).toInt()] = sample
        written++
    }

    /** Writes the first [count] of [values]. */
    @Synchronized
    fun write(values: FloatArray, count: Int) {
        for (index in 0 until count) {
            samples[(written % samples.size).toInt()] = values[index]
            written++
        }
    }

    /** The number of samples written since the last [clear]. */
    @Synchronized
    fun count(): Long = written

    /**
     * Copies the [into].size samples that end at sample number [end] into [into]. Returns
     * false when they are not all there, such as at the start of a song.
     */
    @Synchronized
    fun copy(end: Long, into: FloatArray): Boolean {
        val last = min(end, written)
        val first = last - into.size
        if (first < 0 || written - first > samples.size) return false
        for (index in into.indices) into[index] = samples[((first + index) % samples.size).toInt()]
        return true
    }
}

/** Turns samples into bars from 0 to 1, spaced evenly on a logarithmic frequency scale. */
class Analyzer(count: Int) {
    val bars = FloatArray(count)
    private val window = FloatArray(FFT_SIZE) { 0.5f - 0.5f * cos(2 * PI.toFloat() * it / FFT_SIZE) }
    private val real = FloatArray(FFT_SIZE)
    private val imaginary = FloatArray(FFT_SIZE)

    /** True when every bar is down, so nothing moves without new sound. */
    val silent: Boolean get() = bars.all { it < 0.002f }

    /** Lets every bar fall, for pauses and for silence. */
    fun decay(dt: Float) {
        val keep = exp(-dt * 9f)
        for (index in bars.indices) bars[index] *= keep
    }

    /** Measures [samples], [FFT_SIZE] of them. Bars rise fast and fall slowly, so they look steady. */
    fun update(samples: FloatArray, rate: Int, dt: Float) {
        if (rate <= 0) return decay(dt)
        for (index in 0 until FFT_SIZE) {
            real[index] = samples[index] * window[index]
            imaginary[index] = 0f
        }
        fft(real, imaginary)

        val top = min(HIGHEST_HZ, rate / 2f)
        val hzPerBin = rate.toFloat() / FFT_SIZE
        fun edge(index: Int) = LOWEST_HZ * (top / LOWEST_HZ).pow(index.toFloat() / bars.size)
        val rise = 1f - exp(-dt * 30f)
        val fall = 1f - exp(-dt * 6f)
        for (index in bars.indices) {
            val first = (edge(index) / hzPerBin).toInt()
            val last = min(max((edge(index + 1) / hzPerBin).toInt(), first + 1), FFT_SIZE / 2)
            var peak = 0f
            for (bin in min(first, last - 1) until last) peak = max(peak, hypot(real[bin], imaginary[bin]))
            // A full-scale sine under a Hann window peaks at a quarter of the FFT size.
            val db = 20f * log10(max(peak / (FFT_SIZE / 4f), 1e-6f))
            // Squaring keeps most music from filling every bar, so the shape stays readable.
            val level = ((db - FLOOR_DB) / RANGE_DB).coerceIn(0f, 1f).pow(2)
            bars[index] += (level - bars[index]) * if (level > bars[index]) rise else fall
        }
    }
}

/** An in-place radix-2 Fourier transform. The size must be a power of two. */
internal fun fft(real: FloatArray, imaginary: FloatArray) {
    val size = real.size
    var j = 0
    for (i in 1 until size) {
        var bit = size shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            real[i] = real[j].also { real[j] = real[i] }
            imaginary[i] = imaginary[j].also { imaginary[j] = imaginary[i] }
        }
    }
    var length = 2
    while (length <= size) {
        val angle = -2 * PI / length
        val stepReal = cos(angle).toFloat()
        val stepImaginary = sin(angle).toFloat()
        var start = 0
        while (start < size) {
            var turnReal = 1f
            var turnImaginary = 0f
            for (k in 0 until length / 2) {
                val a = start + k
                val b = a + length / 2
                val bReal = real[b] * turnReal - imaginary[b] * turnImaginary
                val bImaginary = real[b] * turnImaginary + imaginary[b] * turnReal
                real[b] = real[a] - bReal
                imaginary[b] = imaginary[a] - bImaginary
                real[a] += bReal
                imaginary[a] += bImaginary
                val nextReal = turnReal * stepReal - turnImaginary * stepImaginary
                turnImaginary = turnReal * stepImaginary + turnImaginary * stepReal
                turnReal = nextReal
            }
            start += length
        }
        length = length shl 1
    }
}
