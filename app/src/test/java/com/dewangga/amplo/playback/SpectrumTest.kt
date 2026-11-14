package com.dewangga.amplo.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumTest {
    private val rate = 48_000

    private fun tone(hz: Float, amplitude: Float) =
        FloatArray(FFT_SIZE) { amplitude * sin(2 * PI.toFloat() * hz * it / rate) }

    private fun analyze(samples: FloatArray) = Analyzer(28).apply { repeat(30) { update(samples, rate, 0.1f) } }

    private fun loudest(analyzer: Analyzer) = analyzer.bars.indices.maxBy { analyzer.bars[it] }

    @Test
    fun a_higher_tone_lights_a_higher_bar() {
        val low = analyze(tone(200f, 0.8f))
        val high = analyze(tone(5_000f, 0.8f))
        assertTrue(loudest(low) < loudest(high))
        assertTrue(low.bars[loudest(low)] > 0.8f)
    }

    @Test
    fun silence_gives_empty_bars() {
        val analyzer = analyze(FloatArray(FFT_SIZE))
        assertTrue(analyzer.bars.all { it < 0.01f })
        assertTrue(analyzer.silent)
    }

    @Test
    fun bars_fall_without_new_samples() {
        val analyzer = analyze(tone(1_000f, 0.8f))
        val before = analyzer.bars.sum()
        analyzer.decay(0.5f)
        assertTrue(analyzer.bars.sum() < before * 0.1f)
    }

    @Test
    fun the_ring_returns_samples_that_end_where_asked() {
        val ring = SampleRing(capacity = 8)
        val into = FloatArray(3)
        assertFalse(ring.copy(2, into))
        repeat(10) { ring.write(it.toFloat()) }
        assertEquals(10L, ring.count())
        assertTrue(ring.copy(7, into))
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), into, 0f)
        // Samples older than the capacity are gone.
        assertFalse(ring.copy(4, into))
        ring.clear()
        assertFalse(ring.copy(3, into))
    }
}
