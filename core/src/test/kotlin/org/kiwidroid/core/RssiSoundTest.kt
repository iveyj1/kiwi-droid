package org.kiwidroid.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.*

class RssiSoundTest {
    @Test fun `default mapping doubles pitch for every ten dB and clamps extremes`() {
        val mapping = RssiPitchMapping(RssiToneSettings())
        for (dbm in -110..-80) {
            assertEquals(2.0, noteFrequency(mapping.midi(dbm + 10.0)) / noteFrequency(mapping.midi(dbm.toDouble())), 0.00001)
        }
        assertEquals(60.0, mapping.midi(-100.0))
        assertEquals(61.0, mapping.midi(-100.0 + 10.0 / 12))
        assertEquals(48.0, mapping.midi(-160.0))
        assertEquals(96.0, mapping.midi(0.0))
        assertEquals(440.0, noteFrequency(69.0))
    }

    @Test fun `custom points interpolate in semitones not hertz`() {
        val config = RssiToneSettings(points = parsePitchPoints("-120=C3\n-100=C4\n-95=C5"), scale = MusicalScale.CONTINUOUS)
        val mapping = RssiPitchMapping(config)
        assertEquals(54.0, mapping.midi(-110.0))
        assertEquals(66.0, mapping.midi(-97.5))
        assertEquals(-97.5, mapping.dbmAtMidi(66.0))
        assertEquals(config.points, parsePitchPoints(formatPitchPoints(config.points)))
    }

    @Test fun `note names and sharps flats and MIDI numbers`() {
        assertEquals(60, parseNote("C4"))
        assertEquals(61, parseNote("C#4"))
        assertEquals(61, parseNote("Db4"))
        assertEquals(58, parseNote("Bb3"))
        assertEquals(69, parseNote("69"))
        assertEquals("C4", noteName(60))
        for (text in listOf("C9", "35", "109", "Q3", "440Hz", "", "C9999999999999")) {
            assertFailsWith<IllegalArgumentException> { parseNote(text) }
        }
    }

    @Test fun `selected scale root and custom intervals determine allowed notes`() {
        val major = RssiPitchMapping(RssiToneSettings(scale = MusicalScale.MAJOR))
        assertEquals(60.0, major.midi(-100.0 + 10.0 / 12)) // C# is midway, choose lower C
        val transposed = RssiPitchMapping(RssiToneSettings(scale = MusicalScale.MAJOR, root = 1))
        assertEquals(61.0, transposed.midi(-100.0 + 10.0 / 12))
        val octaves = RssiPitchMapping(RssiToneSettings(scale = MusicalScale.CUSTOM, customOffsets = listOf(0)))
        assertEquals(60.0, octaves.midi(-97.0))
        assertEquals(72.0, octaves.midi(-92.0))
    }

    @Test fun `invalid sound settings are rejected before playback`() {
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(points = listOf(PitchPoint(-90.0, 60))) }
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(points = parsePitchPoints("-90=C4\n-100=C5")) }
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(points = parsePitchPoints("-100=C5\n-90=C4")) }
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(points = parsePitchPoints("NaN=C4\n-90=C5")) }
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(durationMs = 300, intervalMs = 300) }
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(durationMs = 20, fadeMs = 11) }
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(smoothingMs = -1) }
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(hysteresisDb = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(customOffsets = listOf(0, 12)) }
        assertFailsWith<IllegalArgumentException> { RssiToneSettings(points = parsePitchPoints("-100=C4\n-90=C#4"),
            scale = MusicalScale.CUSTOM, customOffsets = listOf(7)) }
        assertFailsWith<IllegalArgumentException> { SoundSettings(radioVolumePercent = 101) }
    }

    @Test fun `sine beeps have configured frequency length interval gain and click-free envelope`() {
        val config = RssiToneSettings(enabled = true, volumePercent = 20, smoothingMs = 0)
        val synth = RssiToneSynth()
        val result = synth.render(28800, config, RssiReading(-100.0, 0), 0) // two 300 ms cycles
        assertEquals(0, result[0].toInt())
        assertEquals(0, result[4799].toInt())
        assertTrue(result.sliceArray(4800 until 14400).all { it == 0.toShort() })
        assertContentEquals(result.sliceArray(0 until 14400), result.sliceArray(14400 until 28800))
        assertTrue(result.any { it != 0.toShort() })
        assertTrue(result.all { abs(it.toInt()) <= 6554 })
        val crossings = (1 until 4700).count { result[it - 1] <= 0 && result[it] > 0 }
        assertTrue(crossings in 25..27, "C4 is about 26 cycles per 100 ms, got $crossings")
    }

    @Test fun `synth is independent of audio block size`() {
        val config = RssiToneSettings(enabled = true, waveform = ToneWaveform.TRIANGLE)
        val reading = RssiReading(-90.0, 0)
        val whole = RssiToneSynth().render(48000, config, reading, 0)
        val chunkedSynth = RssiToneSynth()
        val chunked = (0 until 100).flatMap { chunkedSynth.render(480, config, reading, it * 10L).toList() }.toShortArray()
        assertContentEquals(whole, chunked)
    }

    @Test fun `stale missing disabled and invalid readings are silent and reset old pitch`() {
        val synth = RssiToneSynth()
        val config = RssiToneSettings(enabled = true)
        assertTrue(synth.render(4800, config, RssiReading(-90.0, 0), 0).any { it != 0.toShort() })
        assertTrue(synth.render(4800, config, RssiReading(-90.0, 0), 2000).all { it == 0.toShort() })
        assertNull(synth.currentMidi)
        assertTrue(synth.render(4800, config, null, 2001).all { it == 0.toShort() })
        assertTrue(synth.render(4800, config, RssiReading(Double.NaN, 2002), 2002).all { it == 0.toShort() })
        assertTrue(synth.render(4800, config.copy(enabled = false), RssiReading(-90.0, 2003), 2003).all { it == 0.toShort() })
        synth.render(480, config, RssiReading(-110.0, 3000), 3000)
        assertEquals(48.0, synth.currentMidi)
    }

    @Test fun `hysteresis prevents note chatter but real cable changes move pitch`() {
        val synth = RssiToneSynth()
        val config = RssiToneSettings(enabled = true, smoothingMs = 0, hysteresisDb = 0.15)
        fun observe(dbm: Double, time: Long) { synth.render(480, config, RssiReading(dbm, time), time) }
        observe(-100.0, 0)
        assertEquals(60.0, synth.currentMidi)
        observe(-99.55, 100) // slightly above the C4-C#4 midpoint, inside deadband
        assertEquals(60.0, synth.currentMidi)
        observe(-99.4, 200)
        assertEquals(61.0, synth.currentMidi)
        observe(-99.6, 300)
        assertEquals(61.0, synth.currentMidi)
        observe(-99.8, 400)
        assertEquals(60.0, synth.currentMidi)
        observe(-80.0, 500)
        assertEquals(84.0, synth.currentMidi)
    }

    @Test fun `smoothing uses elapsed time not UI frame count`() {
        val synth = RssiToneSynth()
        val config = RssiToneSettings(enabled = true, scale = MusicalScale.CONTINUOUS, smoothingMs = 100)
        synth.render(480, config, RssiReading(-100.0, 0), 0)
        synth.render(480, config, RssiReading(-90.0, 100), 100)
        val expected = RssiPitchMapping(config).midi(-100 + (1 - exp(-1.0)) * 10)
        assertEquals(expected, synth.currentMidi!!, 0.0001)
        synth.render(480, config, RssiReading(-90.0, 100), 110)
        assertEquals(expected, synth.currentMidi!!, 0.0001) // repeated stale value is not another measurement
    }

    @Test fun `muting radio leaves tones and PCM mixing saturates safely`() {
        val radio = shortArrayOf(32000, -32000, 10000)
        val tones = shortArrayOf(10000, -10000, 0)
        assertContentEquals(tones, mixSound(radio, tones, SoundSettings(radioEnabled = false)))
        assertContentEquals(shortArrayOf(32767, -32768, 10000), mixSound(radio, tones, SoundSettings()))
        assertContentEquals(shortArrayOf(8000, -8000, 2500), mixSound(radio, ShortArray(3), SoundSettings(radioVolumePercent = 25)))
    }
}
