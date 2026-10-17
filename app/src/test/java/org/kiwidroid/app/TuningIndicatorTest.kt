package org.kiwidroid.app

import kotlin.test.*
import org.kiwidroid.core.*

class TuningIndicatorTest {
    @Test fun `indicator waits for matching source command fresh PCM and estimated settling`() {
        val settings = Settings()
        val metrics = SessionMetrics(-60.0, false, 0, 0, 12000.0,
            PlaybackDiagnostics(), tuningCommand = settings.tuning.command())
        val stable = RadioUiState(settings = settings, running = true, audibleReceiver = settings.receiver,
            audibleTuning = settings.tuning, metrics = metrics)
        assertFalse(stable.transitioning)
        assertTrue(stable.copy(audibleReceiver = null).transitioning)
        assertTrue(stable.copy(audibleTuning = settings.tuning.step(1)).transitioning)
        assertTrue(stable.copy(metrics = metrics.copy(tuningCommand = null)).transitioning)
        assertTrue(stable.copy(metrics = metrics.copy(settling = true)).transitioning)
        assertTrue(stable.copy(metrics = metrics.copy(stale = true)).transitioning)
        assertTrue(stable.copy(metrics = metrics.copy(playback = PlaybackDiagnostics(buffering = true))).transitioning)
        assertFalse(stable.copy(running = false, metrics = null).transitioning)
    }
}
