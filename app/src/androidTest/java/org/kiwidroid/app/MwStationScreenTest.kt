package org.kiwidroid.app

import android.app.KeyguardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.kiwidroid.core.Mode
import org.kiwidroid.core.formatKhz

/** Offline: bundled FCC table only; never presses Play or contacts any receiver. */
class MwStationScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun mwPanelOnlyInBandAndStationSelectionTunes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Unlock phone first", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val app = context.applicationContext as KiwiApplication
        val radio = app.radio
        rule.waitUntil(5000) { radio.state.value.loaded }
        rule.waitUntil(10000) { app.stations.state.value.db != null }
        val original = radio.state.value.settings
        try {
            instrumentation.runOnMainSync {
                radio.stop(); radio.setMode(Mode.AM); radio.tune("5000.000"); radio.setMwIncludePermits(false)
            }
            rule.onNodeWithTag("mwStations").assertDoesNotExist()
            rule.onNodeWithTag("frequency").performTextReplacement("1030")
            rule.onNodeWithText("Tune").performClick()
            rule.onNodeWithTag("mwChannel").performScrollTo().assertTextContains("1030 kHz: WBZ Boston", substring = true)

            instrumentation.runOnMainSync { radio.setMode(Mode.CW); radio.tune("1000.000") }
            rule.onNodeWithTag("mwStations").performScrollTo().performClick()
            rule.onNodeWithTag("mwSearch").performTextReplacement("WBZ")
            rule.onNodeWithText("0 stations · tap to tune").assertExists()
            rule.onNodeWithTag("mwScopeAll").performClick()
            rule.onNodeWithText("1030 WBZ").performClick()
            rule.onNodeWithTag("tuned").assertTextEquals("Tuned: 1030.000 kHz")
            rule.onNodeWithText("Mode: AM ▾").assertExists()
            rule.onNodeWithTag("mwSearch").assertDoesNotExist()

            // US construction permits stay hidden until requested, and the choice persists.
            rule.onNodeWithTag("mwStations").performScrollTo().performClick()
            rule.onNodeWithTag("mwScopeAll").performClick()
            // WLBE is licensed on 790 kHz and holds a permit to move to 780 kHz.
            rule.onNodeWithTag("mwSearch").performTextReplacement("WLBE")
            rule.onNodeWithText("1 station · tap to tune").assertExists()
            rule.onNodeWithText("790 WLBE").assertExists()
            rule.onNodeWithText("780 WLBE").assertDoesNotExist()
            rule.onNodeWithTag("mwPermits").performClick()
            rule.onNodeWithText("2 stations · tap to tune").assertExists()
            rule.onNodeWithText("780 WLBE").assertExists()
            rule.runOnIdle { assertTrue(radio.state.value.settings.mwIncludePermits) }
            rule.onNodeWithText("Close").performClick()
        } finally {
            instrumentation.runOnMainSync {
                radio.setMode(Mode.AM)
                radio.tune(formatKhz(original.tuning.frequencyHz))
                radio.setMode(original.tuning.mode)
                radio.setMwIncludePermits(original.mwIncludePermits)
            }
        }
    }
}
