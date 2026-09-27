package org.kiwidroid.app

import kotlin.test.*
import org.kiwidroid.core.*

class SettingsTest {
    @Test fun `settings round trip includes receiver set tuning mode and custom step`() {
        val settings = Settings(listOf(Receiver("Test", "https://example.com:8073")),
            tuning = Tuning(335001, Mode.CW), stepHz = 17)
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
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

    @Test fun `defaults match proposed local receiver set`() {
        val defaults = Settings()
        assertEquals(4, defaults.receivers.size)
        assertEquals("http://10.0.0.41:8073", defaults.receiver.url)
        assertEquals(5_000_000, defaults.tuning.frequencyHz)
        assertEquals(100, defaults.stepHz)
    }
}
