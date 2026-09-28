package org.kiwidroid.core

import kotlin.test.*

class StereoPcmTest {
    @Test fun `mono duplicates to both output channels`() {
        val resampler = StereoPcmResampler(12000.0)
        val result = resampler.convert(shortArrayOf(0, 100, 200), 1)
        assertContentEquals(shortArrayOf(0, 0, 25, 25, 50, 50, 75, 75, 100, 100, 125, 125, 150, 150, 175, 175), result)
    }

    @Test fun `stereo keeps independent I Q values with matched timing`() {
        val resampler = StereoPcmResampler(12000.0)
        val result = resampler.convert(shortArrayOf(0, 1000, 100, -1000, 200, 1000), 2)
        assertContentEquals(shortArrayOf(0, 1000, 25, 500, 50, 0, 75, -500, 100, -1000, 125, -500, 150, 0, 175, 500), result)
        assertEquals(2, resampler.inputChannels)
    }

    @Test fun `stereo packet boundaries keep channel phases intact`() {
        val input = ShortArray(2400) { if (it % 2 == 0) (it / 2).toShort() else (-it / 2).toShort() }
        val whole = StereoPcmResampler(12000.0).convert(input, 2)
        val converter = StereoPcmResampler(12000.0)
        val chunks = input.toList().chunked(512).flatMap { converter.convert(it.toShortArray(), 2).toList() }.toShortArray()
        assertContentEquals(whole, chunks)
    }

    @Test fun `format switch resets previous channel samples without cross channel bleed`() {
        val converter = StereoPcmResampler(12000.0)
        converter.convert(shortArrayOf(30000, -30000, 30000, -30000), 2)
        assertTrue(converter.convert(shortArrayOf(0, 0, 0), 1).all { it == 0.toShort() })
        assertTrue(converter.convert(shortArrayOf(0, 0, 0, 0), 2).all { it == 0.toShort() })
        assertFailsWith<IllegalArgumentException> { converter.convert(shortArrayOf(1), 2) }
    }

    @Test fun `stereo jitter counts frames not channel samples and fades both equally`() {
        val buffer = RadioJitterBuffer(48000, 9600, channels = 2)
        val input = ShortArray(14400 * 2) { if (it % 2 == 0) 10000 else -10000 }
        buffer.offer(input)
        assertEquals(300, buffer.diagnostics().queuedAudioMs)
        assertEquals(300, buffer.diagnostics().targetAudioMs)
        val first = buffer.render(480)
        assertEquals(960, first.size)
        assertTrue(first.indices.step(2).all { first[it].toInt() == -first[it + 1].toInt() })
        assertEquals(290, buffer.diagnostics().queuedAudioMs)
        repeat(4) { buffer.render(480) }
        val fadedIn = buffer.render(480)
        assertTrue(fadedIn.indices.step(2).all { fadedIn[it] == 10000.toShort() && fadedIn[it + 1] == (-10000).toShort() })
        buffer.clear()
        assertEquals(0, buffer.diagnostics().queuedAudioMs)
        assertEquals(0, buffer.diagnostics().radioUnderruns)
        assertTrue(buffer.render(480).all { it == 0.toShort() })
    }

    @Test fun `centered tones mix to both channels without collapsing stereo`() {
        val radio = shortArrayOf(1000, -1000, 30000, -30000)
        val tone = shortArrayOf(100, 10000)
        assertContentEquals(shortArrayOf(1100, -900, 32767, -20000), mixStereoSound(radio, tone, SoundSettings()))
        assertContentEquals(shortArrayOf(100, 100, 10000, 10000), mixStereoSound(radio, tone, SoundSettings(radioEnabled = false)))
    }
}
