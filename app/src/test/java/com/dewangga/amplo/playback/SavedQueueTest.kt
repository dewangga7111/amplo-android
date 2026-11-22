package com.dewangga.amplo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedQueueTest {
    private val queue = SavedQueue(
        ids = listOf(10, 20, 30, 40),
        index = 2,
        positionMs = 81_500,
        shuffleOrder = listOf(2, 0, 3, 1),
        source = "Road Trip",
        sourceKind = "PLAYLIST",
        sourceKey = "playlist:/storage/emulated/0/Music/Road Trip.m3u",
    )

    @Test
    fun roundTripsThroughJson() {
        assertEquals(queue, SavedQueue.fromJson(queue.toJson()))
        assertNull(SavedQueue.fromJson("not json"))
        assertNull(SavedQueue.fromJson("""{"ids":[]}"""))
        // An order that is not of all the songs is left out, and the index stays in the queue.
        val damaged = SavedQueue.fromJson("""{"ids":[1,2],"index":9,"shuffleOrder":[0,0]}""")!!
        assertEquals(1, damaged.index)
        assertEquals(emptyList<Int>(), damaged.shuffleOrder)
    }

    @Test
    fun songsThatAreGoneAreLeftOut() {
        assertEquals(queue, queue.keeping { true })
        // The song that played stays current, where it was.
        val without10 = queue.keeping { it != 10L }!!
        assertEquals(listOf(20L, 30L, 40L), without10.ids)
        assertEquals(1, without10.index)
        assertEquals(81_500L, without10.positionMs)
        assertEquals(emptyList<Int>(), without10.shuffleOrder)
        // The next song takes the place of the one that is gone, from its start.
        val without30 = queue.keeping { it != 30L }!!
        assertEquals(listOf(10L, 20L, 40L), without30.ids)
        assertEquals(2, without30.index)
        assertEquals(0L, without30.positionMs)
        assertNull(queue.keeping { false })
    }
}
