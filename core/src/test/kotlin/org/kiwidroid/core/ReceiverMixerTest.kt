package org.kiwidroid.core

import kotlin.math.abs
import kotlin.test.*

class ReceiverMixerTest {
    @Test fun `handover and rapid reversal crossfade continuously without summing full scale`() {
        val mixer = ReceiverMixer(0) { SoundSettings() }
        val a = mixer.createInput()
        val b = mixer.createInput()
        a.start(48000.0); b.start(48000.0)
        a.offer(ShortArray(48000) { 1000 })
        b.offer(ShortArray(48000) { 2000 })
        repeat(6) { mixer.render(480) } // prime both and complete their startup fades
        assertTrue(a.ready && b.ready)
        mixer.select(a)
        assertTrue(mixer.render(480).all { it == 1000.toShort() })
        mixer.select(b)
        val forward = mixer.render(480)
        assertEquals(1000, forward.first().toInt())
        assertTrue(forward.last() in 1498..1500)
        assertTrue(mixer.transitioning)
        mixer.select(a)
        val reverse = mixer.render(480)
        assertTrue(abs(reverse.first() - forward.last()) <= 2)
        assertTrue(reverse.last() < reverse.first())
        repeat(6) { mixer.render(480) }
        assertFalse(mixer.transitioning)
        assertTrue(mixer.render(480).all { it == 1000.toShort() })
        mixer.remove(a); mixer.remove(b)
        assertTrue(mixer.render(480).all { it == 0.toShort() })
    }

    @Test fun `mixer bounds inputs preserves PCM on retune and resets on layout change`() {
        val mixer = ReceiverMixer(0) { SoundSettings() }
        val a = mixer.createInput()
        mixer.createInput()
        assertFailsWith<IllegalStateException> { mixer.createInput() }
        a.start(48000.0); a.offer(ShortArray(10000) { 1000 })
        a.observeRssi(-70.0)
        mixer.render(480)
        assertTrue(a.ready)
        val queued = a.playbackDiagnostics().queuedAudioMs
        a.retuned()
        assertTrue(a.ready)
        assertEquals(queued, a.playbackDiagnostics().queuedAudioMs)
        mixer.select(a)
        assertTrue(mixer.render(480).all { it > 0 }, "Ordinary retunes must not insert silence")
        a.offer(ShortArray(20), channels = 2)
        assertFalse(a.ready)
        mixer.remove(a)
        assertNotNull(mixer.createInput())
    }
}
