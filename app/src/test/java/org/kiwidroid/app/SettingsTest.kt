package org.kiwidroid.app

import kotlin.test.*
import kotlinx.serialization.json.*
import org.kiwidroid.core.*

class SettingsTest {
    @Test fun `settings round trip includes receiver set tuning mode and custom step`() {
        val settings = Settings(listOf(Receiver("Test", "https://example.com:8073")),
            tuning = Tuning(335001, Mode.CW), stepHz = 17)
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
    }

    @Test fun `receiver locations and MW favorites persist and default empty for older settings`() {
        val receiver = Receiver("Test", "http://example.com:8073")
        val settings = Settings(listOf(receiver), receiverLocations = mapOf(receiver.locationKey to GeoPoint(42.36, -71.06)),
            favorites = listOf(MwFavorite(25444, 1030, "WBZ"), MwFavorite(29733, 700, "WLW")))
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(settings, decoded)
        assertEquals(GeoPoint(42.36, -71.06), decoded.receiverLocation)
        assertEquals("http://example.com:8073/", receiver.locationKey)
        val unslashed = JsonObject(Json.parseToJsonElement(SettingsCodec.encode(settings)).jsonObject +
            ("receiverLocations" to buildJsonObject { put("http://example.com:8073", buildJsonObject { put("lat", 1.0); put("lon", 2.0) }) }))
        assertEquals(GeoPoint(1.0, 2.0), SettingsCodec.decode(unslashed.toString()).receiverLocation)
        val older = Json.parseToJsonElement(SettingsCodec.encode(settings)).jsonObject
            .let { JsonObject(it - "receiverLocations" - "favorites") }.toString()
        assertEquals(Settings(listOf(receiver)), SettingsCodec.decode(older))
        assertFailsWith<IllegalArgumentException> {
            Settings(favorites = listOf(MwFavorite(1, 1030, "A"), MwFavorite(1, 1030, "B")))
        }
    }

    @Test fun `MW permit filter persists and defaults off for older settings`() {
        val settings = Settings(mwIncludePermits = true)
        assertTrue(SettingsCodec.decode(SettingsCodec.encode(settings)).mwIncludePermits)
        val older = Json.parseToJsonElement(SettingsCodec.encode(settings)).jsonObject
            .let { JsonObject(it - "mwIncludePermits") }.toString()
        assertFalse(SettingsCodec.decode(older).mwIncludePermits)
    }

    @Test fun `invalid settings cannot become a connection`() {
        assertFailsWith<IllegalArgumentException> { Settings(receivers = emptyList()) }
        assertFailsWith<IllegalArgumentException> { Settings(selected = 42) }
        assertFailsWith<IllegalArgumentException> { Settings(stepHz = 0) }
        assertFailsWith<IllegalArgumentException> {
            Settings(receivers = listOf(Receiver("A", "http://example.com"), Receiver("B", "http://example.com/")))
        }
        assertFailsWith<IllegalArgumentException> { SettingsCodec.decode("""{"version":2}""") }
    }

    @Test fun `all sound settings survive persistence`() {
        val sound = SoundSettings(radioEnabled = false, radioVolumePercent = 34, tones = RssiToneSettings(
            enabled = true, points = parsePitchPoints("-112.25=C3\n-95.5=F#4\n-73.125=C7"),
            scale = MusicalScale.CUSTOM, root = 2, customOffsets = listOf(0, 3, 7), volumePercent = 8,
            durationMs = 60, intervalMs = 150, fadeMs = 5, smoothingMs = 30, hysteresisDb = 0.25,
            waveform = ToneWaveform.TRIANGLE, latchMode = RssiLatchMode.DIPS, latchHoldMs = 2500, latchThresholdDb = 2.5,
        ))
        val settings = Settings(sound = sound)
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
        assertEquals(sound.tones.points, parsePitchPoints(formatPitchPoints(sound.tones.points)))
    }

    @Test fun `pre-tone settings retain all controls and use safe sound defaults`() {
        val previous = Settings(tuning = Tuning(335000, Mode.CW), stepHz = 17, selected = 2)
        val oldJson = JsonObject(Json.parseToJsonElement(SettingsCodec.encode(previous)).jsonObject - "sound").toString()
        val restored = SettingsCodec.decode(oldJson)
        assertEquals(previous, restored)
        assertTrue(restored.sound.radioEnabled)
        assertFalse(restored.sound.tones.enabled)
    }

    @Test fun `pre-latch sound settings retain calibration and gain with new latch defaults`() {
        val previous = Settings(sound = SoundSettings(radioEnabled = false, tones = RssiToneSettings(enabled = true,
            points = defaultPitchPoints(-85.0), volumePercent = 7)))
        val current = Json.parseToJsonElement(SettingsCodec.encode(previous)).jsonObject
        val oldSound = JsonObject(current.getValue("sound").jsonObject - setOf("latchMode", "latchHoldMs", "latchThresholdDb"))
        val oldJson = JsonObject(current + ("sound" to oldSound)).toString()
        val restored = SettingsCodec.decode(oldJson)
        assertEquals(previous, restored)
        assertEquals(RssiLatchMode.BOTH, restored.sound.tones.latchMode)
        assertEquals(1000, restored.sound.tones.latchHoldMs)
        assertEquals(1.0, restored.sound.tones.latchThresholdDb)
    }

    @Test fun `every mode keeps its own passband across persistence`() {
        val bands = Mode.entries.associateWith { Passband(it.lowCut + 10, it.highCut - 10) }
        val settings = Settings(tuning = Tuning(335000, Mode.SAS, bands))
        val restored = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(settings, restored)
        Mode.entries.forEach { assertEquals(bands.getValue(it), restored.tuning.copy(mode = it).passband) }
    }

    @Test fun `pre-passband preferences retain controls and fill missing mode defaults`() {
        val previous = Settings(tuning = Tuning(335000, Mode.CW), stepHz = 17)
        val json = Json.parseToJsonElement(SettingsCodec.encode(previous)).jsonObject
        assertEquals(previous, SettingsCodec.decode(JsonObject(json - "passbands").toString()))
        val partial = JsonObject(mapOf("CW" to buildJsonObject { put("lowHz", 700); put("highHz", 900) }))
        val restored = SettingsCodec.decode(JsonObject(json + ("passbands" to partial)).toString())
        assertEquals(Passband(700, 900), restored.tuning.passband)
        assertEquals(Mode.CWN.defaultPassband, restored.tuning.copy(mode = Mode.CWN).passband)
        assertEquals(17, restored.stepHz)
    }

    @Test fun `defaults match proposed local receiver set`() {
        val defaults = Settings()
        assertEquals(4, defaults.receivers.size)
        assertEquals("http://10.0.0.41:8073", defaults.receiver.url)
        assertEquals(5_000_000, defaults.tuning.frequencyHz)
        assertEquals(100, defaults.stepHz)
    }
}
