package org.kiwidroid.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.*

class PcmTest {
    @Test fun `resampling preserves packet boundaries and phase`() {
        val input = ShortArray(12000) { (it % 1000).toShort() }
        val whole = PcmResampler(11998.75).convert(input)
        val chunked = PcmResampler(11998.75)
        val output = input.toList().chunked(512).flatMap { chunked.convert(it.toShortArray()).toList() }.toShortArray()
        assertEquals(whole.size, output.size)
        assertTrue(whole.indices.all { abs(whole[it] - output[it]) <= 1 })
        assertTrue(abs(output.size - 48000) < 10)
    }

    @Test fun `single sample packets interpolate without discontinuity`() {
        val resampler = PcmResampler(12000.0)
        assertTrue(resampler.convert(shortArrayOf(0)).isEmpty())
        assertContentEquals(shortArrayOf(0, 25, 50, 75), resampler.convert(shortArrayOf(100)))
        assertContentEquals(shortArrayOf(100, 125, 150, 175), resampler.convert(shortArrayOf(200)))
        assertTrue(resampler.convert(shortArrayOf()).isEmpty())
    }

    @Test fun `buffer drops oldest audio and never exceeds capacity`() {
        val queue = PcmBuffer(4)
        queue.offer(shortArrayOf(1, 2))
        queue.offer(shortArrayOf(3, 4))
        queue.offer(shortArrayOf(5, 6))
        assertEquals(4, queue.queuedSamples())
        assertEquals(2, queue.droppedSamples)
        assertContentEquals(shortArrayOf(3, 4), queue.take())
        assertContentEquals(shortArrayOf(5, 6), queue.take())
        queue.offer(shortArrayOf(1, 2, 3, 4, 5, 6))
        assertContentEquals(shortArrayOf(3, 4, 5, 6), queue.take())
        queue.close()
        assertNull(queue.take())
    }

    @Test fun `clocked reads span packets preserve partial heads and pad silence`() {
        val queue = PcmBuffer(8)
        queue.offer(shortArrayOf(1, 2, 3))
        queue.offer(shortArrayOf(4, 5))
        assertContentEquals(shortArrayOf(1, 2), queue.read(2))
        assertEquals(3, queue.queuedSamples())
        assertContentEquals(shortArrayOf(3, 4, 5, 0), queue.read(4))
        queue.offer(shortArrayOf(6, 7, 8))
        queue.read(1)
        assertContentEquals(shortArrayOf(7, 8), queue.take())
        queue.offer(shortArrayOf(1, 2, 3, 4))
        queue.read(2)
        queue.offer(ShortArray(8) { 9 })
        assertEquals(2, queue.droppedSamples)
        assertContentEquals(ShortArray(8) { 9 }, queue.read(8))
        queue.offer(shortArrayOf(1, 2))
        queue.clear()
        assertEquals(0, queue.queuedSamples())
        assertContentEquals(ShortArray(2), queue.read(2))
        queue.close()
    }

    @Test fun `closing wakes a blocked consumer`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val queue = PcmBuffer(10)
            val future = executor.submit<ShortArray?> { queue.take(5) }
            queue.close()
            assertNull(future.get(1, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
