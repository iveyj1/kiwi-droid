package org.kiwidroid.app

import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AudioFocusTest {
    @Test fun transientLossReturnsButPermanentLossStops() {
        var stops = 0
        val focus = AudioFocus(InstrumentationRegistry.getInstrumentation().targetContext) { stops++ }
        for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            focus.handleChange(loss)
            assertTrue(focus.interrupted)
            assertEquals(0, stops)
            focus.handleChange(AudioManager.AUDIOFOCUS_GAIN)
            assertFalse(focus.interrupted)
            assertEquals(0, stops)
        }
        focus.handleChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(focus.interrupted)
        assertEquals(1, stops)
        // Gain never invokes Play or starts a new session after an explicit/permanent stop.
        focus.handleChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, stops)
    }
}
