package org.kiwidroid.core

import kotlin.test.*

class RssiLatchTest {
    @Test fun `one received RSSI dip survives recovery before the next audio block`() {
        val config = RssiToneSettings(enabled = true, smoothingMs = 2000)
        val synth = RssiToneSynth()
        synth.observe(RssiReading(-90.0, 0), config)
        synth.render(480, config, 0)
        assertEquals(72.0, synth.soundingMidi)
        synth.observe(RssiReading(-110.0, 43), config)
        synth.observe(RssiReading(-90.0, 86), config) // neither sample needs to coincide with rendering
        synth.render(13920, config, 86)
        synth.render(480, config, 300)
        assertEquals(48.0, synth.soundingMidi) // raw C3, not the smoothed C5-ish value
        assertTrue(synth.currentMidi!! > 70)
    }

    @Test fun `one received peak is sounded even after immediate recovery`() {
        val config = RssiToneSettings(enabled = true, smoothingMs = 2000)
        val synth = RssiToneSynth()
        synth.observe(RssiReading(-90.0, 0), config)
        synth.render(480, config, 0)
        synth.observe(RssiReading(-70.0, 43), config)
        synth.observe(RssiReading(-90.0, 86), config)
        synth.render(13920, config, 86)
        synth.render(480, config, 300)
        assertEquals(96.0, synth.soundingMidi)
    }

    @Test fun `both directions retain the strongest extremes and alternate without starvation`() {
        val latch = RssiExtremaLatch(RssiToneSettings())
        latch.observe(-95.0, -90.0, 10)
        latch.observe(-105.0, -90.0, 11)
        latch.observe(-99.0, -90.0, 12)
        latch.observe(-85.0, -90.0, 13)
        latch.observe(-75.0, -90.0, 14)
        latch.observe(-82.0, -90.0, 15)
        assertEquals(-105.0, latch.next(300))
        assertEquals(-75.0, latch.next(600))
        assertEquals(-105.0, latch.next(900))
        assertEquals(-75.0, latch.next(1200))
        assertEquals(-75.0, latch.next(1500)) // dip expires first
        assertNull(latch.next(1800))
    }

    @Test fun `an unsounded event outlives hold duration and is held starting at first beep`() {
        val latch = RssiExtremaLatch(RssiToneSettings(latchHoldMs = 100))
        latch.observe(-100.0, -90.0, 1)
        assertEquals(-100.0, latch.next(5000))
        assertEquals(-100.0, latch.next(5099))
        assertNull(latch.next(5100))
    }

    @Test fun `off dip-only peak-only and threshold controls`() {
        for (mode in RssiLatchMode.entries) {
            val latch = RssiExtremaLatch(RssiToneSettings(latchMode = mode, latchThresholdDb = 2.0))
            latch.observe(-91.9, -90.0, 1)
            latch.observe(-88.1, -90.0, 2)
            assertNull(latch.next(3))
            latch.observe(-92.0, -90.0, 4)
            latch.observe(-88.0, -90.0, 5)
            when (mode) {
                RssiLatchMode.OFF -> assertNull(latch.next(6))
                RssiLatchMode.DIPS -> assertEquals(-92.0, latch.next(6))
                RssiLatchMode.PEAKS -> assertEquals(-88.0, latch.next(6))
                RssiLatchMode.BOTH -> {
                    assertEquals(-92.0, latch.next(6))
                    assertEquals(-88.0, latch.next(7))
                }
            }
        }
    }

    @Test fun `tenth-dB threshold equality tolerates binary rounding`() {
        val latch = RssiExtremaLatch(RssiToneSettings(latchThresholdDb = 0.1))
        latch.observe(-90.1, -90.0, 1)
        latch.observe(-89.9, -90.0, 2)
        assertEquals(-90.1, latch.next(3))
        assertEquals(-89.9, latch.next(4))
    }

    @Test fun `equal observations do not prolong hold and a stronger extreme gets a new full hold`() {
        val latch = RssiExtremaLatch(RssiToneSettings(latchHoldMs = 100))
        latch.observe(-100.0, -90.0, 0)
        assertEquals(-100.0, latch.next(1))
        latch.observe(-100.0, -90.0, 50)
        assertNull(latch.next(101))
        latch.observe(-100.0, -90.0, 200)
        assertEquals(-100.0, latch.next(201))
        latch.observe(-110.0, -90.0, 250)
        assertEquals(-110.0, latch.next(500))
        assertEquals(-110.0, latch.next(599))
        assertNull(latch.next(600))
    }

    @Test fun `stale stream clears unsounded latch rather than replaying after recovery`() {
        val config = RssiToneSettings(enabled = true)
        val synth = RssiToneSynth()
        synth.observe(RssiReading(-90.0, 0), config)
        synth.render(480, config, 0)
        synth.observe(RssiReading(-110.0, 43), config)
        assertTrue(synth.render(480, config, 2043).all { it == 0.toShort() })
        synth.observe(RssiReading(-90.0, 2044), config)
        synth.render(480, config, 2044)
        assertEquals(72.0, synth.soundingMidi)
    }

    @Test fun `a stale gap clears latch even if rendering was stalled during the gap`() {
        val config = RssiToneSettings(enabled = true)
        val synth = RssiToneSynth()
        synth.observe(RssiReading(-90.0, 0), config)
        synth.observe(RssiReading(-110.0, 43), config)
        synth.observe(RssiReading(-90.0, 2043), config)
        synth.render(480, config, 2043)
        assertEquals(72.0, synth.soundingMidi)
    }

    @Test fun `configuration changes and disabling clear held events`() {
        val config = RssiToneSettings(enabled = true)
        for (change in listOf(config.copy(enabled = false), config.copy(latchMode = RssiLatchMode.OFF), config.copy(volumePercent = 15))) {
            val synth = RssiToneSynth()
            synth.observe(RssiReading(-90.0, 0), config)
            synth.observe(RssiReading(-110.0, 43), config)
            synth.render(480, change, 44)
            synth.observe(RssiReading(-90.0, 86), config)
            synth.render(480, config, 86)
            assertEquals(72.0, synth.soundingMidi)
        }
    }

    @Test fun `receipt winning the lock after render timestamp does not discard a transient`() {
        val config = RssiToneSettings(enabled = true)
        val synth = RssiToneSynth()
        synth.observe(RssiReading(-90.0, 10), config)
        synth.observe(RssiReading(-110.0, 20), config)
        synth.observe(RssiReading(-90.0, 21), config)
        synth.render(480, config, 19)
        assertEquals(48.0, synth.soundingMidi)
    }

    @Test fun `latch off keeps normal smoothing and hold parameters are validated`() {
        val config = RssiToneSettings(enabled = true, smoothingMs = 0, latchMode = RssiLatchMode.OFF)
        val synth = RssiToneSynth()
        synth.observe(RssiReading(-90.0, 0), config)
        synth.render(480, config, 0)
        synth.observe(RssiReading(-110.0, 43), config)
        synth.observe(RssiReading(-90.0, 86), config)
        synth.render(13920, config, 86)
        synth.render(480, config, 300)
        assertEquals(72.0, synth.soundingMidi)
        assertFailsWith<IllegalArgumentException> { config.copy(latchHoldMs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(latchHoldMs = 10001) }
        assertFailsWith<IllegalArgumentException> { config.copy(latchThresholdDb = 0.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(latchThresholdDb = Double.NaN) }
    }
}
