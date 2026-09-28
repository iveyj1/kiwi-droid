package org.kiwidroid.core

import kotlin.test.*

class RadioJitterBufferTest {
    private val rate = 48000
    private val block = 480
    private fun pcm(count: Int) = ShortArray(count) { 10000 }

    @Test fun `initial priming covers actual device buffer plus jitter allowance`() {
        val buffer = RadioJitterBuffer(rate, 15392) // observed Pixel 8 Pro Bluetooth ring: ~321 ms
        assertEquals(420, buffer.diagnostics().targetAudioMs)
        buffer.offer(pcm(20191))
        assertTrue(buffer.render(block).all { it == 0.toShort() })
        assertTrue(buffer.diagnostics().buffering)
        buffer.offer(pcm(1))
        assertTrue(buffer.render(block).any { it != 0.toShort() })
        assertFalse(buffer.diagnostics().buffering)
        assertEquals(0, buffer.diagnostics().radioUnderruns)
    }

    @Test fun `exact queue drain does not restart fade or require rebuffering`() {
        val buffer = RadioJitterBuffer(rate, 0)
        buffer.offer(pcm(7200))
        repeat(15) { buffer.render(block) }
        assertEquals(0, buffer.diagnostics().queuedAudioMs)
        assertFalse(buffer.diagnostics().buffering)
        buffer.offer(pcm(block)) // next packet arrives just in time, not an underrun
        assertTrue(buffer.render(block).all { it == 10000.toShort() })
        assertEquals(0, buffer.diagnostics().radioUnderruns)
        assertFalse(buffer.diagnostics().buffering)
    }

    @Test fun `a real short read counts once and grows the target not on every waiting block`() {
        val buffer = RadioJitterBuffer(rate, 0)
        buffer.offer(pcm(7200 + 100))
        repeat(15) { buffer.render(block) }
        val last = buffer.render(block)
        assertTrue(last.take(100).any { it != 0.toShort() })
        assertTrue(last.drop(100).all { it == 0.toShort() })
        assertEquals(1, buffer.diagnostics().radioUnderruns)
        assertEquals(200, buffer.diagnostics().targetAudioMs)
        repeat(100) { assertTrue(buffer.render(block).all { it == 0.toShort() }) }
        assertEquals(1, buffer.diagnostics().radioUnderruns)
        buffer.offer(pcm(9600))
        assertTrue(buffer.render(block).any { it != 0.toShort() })
        assertFalse(buffer.diagnostics().buffering)
    }

    @Test fun `target growth and audio backlog stay bounded`() {
        val buffer = RadioJitterBuffer(rate, 0)
        repeat(30) {
            val target = buffer.diagnostics().targetAudioMs * rate / 1000
            buffer.offer(pcm(target))
            repeat(target / block + 1) { buffer.render(block) }
        }
        assertEquals(1000, buffer.diagnostics().targetAudioMs)
        buffer.offer(pcm(rate * 3))
        assertTrue(buffer.diagnostics().queuedAudioMs <= 1200)
        assertTrue(buffer.diagnostics().droppedAudioMs >= 1800)
    }

    @Test fun `mute flushes old PCM and does not count intentional silence as starvation`() {
        val buffer = RadioJitterBuffer(rate, 0)
        buffer.offer(pcm(7200))
        buffer.render(block)
        assertTrue(buffer.render(block, false).all { it == 0.toShort() })
        assertEquals(0, buffer.diagnostics().queuedAudioMs)
        assertFalse(buffer.diagnostics().buffering)
        buffer.offer(pcm(10000), false)
        assertEquals(0, buffer.diagnostics().queuedAudioMs)
        assertTrue(buffer.render(block, true).all { it == 0.toShort() })
        assertTrue(buffer.diagnostics().buffering)
        assertEquals(0, buffer.diagnostics().radioUnderruns)
        assertEquals(0, buffer.diagnostics().droppedAudioMs)
        buffer.close()
        buffer.offer(pcm(10000))
        assertTrue(buffer.render(block).all { it == 0.toShort() })
        assertFalse(buffer.diagnostics().buffering)
    }

    @Test fun `no gaps with ordered Kiwi packets jitter and bursty device consumption`() {
        val buffer = RadioJitterBuffer(rate, 15392)
        var packet = 0
        val jitters = intArrayOf(0, 30, 5, 20, 0, 35, 10, 0)
        fun nextArrivalMs(): Int = packet * 512 * 1000 / 12000 + jitters[packet % jitters.size]
        var audibleBlocks = 0
        for (ms in 0..60000) {
            while (nextArrivalMs() <= ms) {
                buffer.offer(pcm(if (packet == 0) 2044 else 2048))
                packet++
            }
            // Native ring frees 80 ms at once, not a uniformly timed 10 ms per write.
            if (ms % 80 == 0) repeat(8) {
                val out = buffer.render(block)
                if (!buffer.diagnostics().buffering) {
                    audibleBlocks++
                    if (ms > 1000) assertTrue(out.all { it == 10000.toShort() }, "Inserted silence/fade at $ms ms")
                }
            }
        }
        assertTrue(audibleBlocks > 5800)
        assertEquals(0, buffer.diagnostics().radioUnderruns)
        assertEquals(0, buffer.diagnostics().droppedAudioMs)
    }
}
