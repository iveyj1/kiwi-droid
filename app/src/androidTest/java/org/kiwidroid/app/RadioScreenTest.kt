package org.kiwidroid.app

import android.app.KeyguardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.kiwidroid.core.Mode
import org.kiwidroid.core.formatKhz

/** Offline device smoke test: never presses Play or contacts any receiver. */
class RadioScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun tuneStepModeAndRotationWithoutNetwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assert.assertFalse("Unlock the phone before running UI tests",
            instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val radio = (instrumentation.targetContext.applicationContext as KiwiApplication).radio
        rule.waitUntil(5000) { radio.state.value.loaded }
        val original = radio.state.value.settings
        try {
            instrumentation.runOnMainSync { radio.stop(); radio.setMode(Mode.AM) }
            rule.onNodeWithTag("frequency").performTextReplacement("5000.000")
            rule.onNodeWithText("Tune").performClick()
            rule.onNodeWithTag("tuned").assertTextEquals("Tuned: 5000.000 kHz")
            rule.onNodeWithText("Step:", substring = true).performClick()
            rule.onNodeWithText("100 Hz", useUnmergedTree = true).performClick()
            rule.onNodeWithTag("stepUp").performClick()
            rule.onNodeWithTag("tuned").assertTextEquals("Tuned: 5000.100 kHz")
            rule.onNodeWithTag("stepDown").performClick()
            rule.onNodeWithTag("tuned").assertTextEquals("Tuned: 5000.000 kHz")
            rule.onNodeWithText("Mode:", substring = true).performClick()
            rule.onNodeWithText("CW", useUnmergedTree = true).performClick()
            rule.onNodeWithText("Mode: CW ▾").assertExists()
            rule.activityRule.scenario.recreate()
            rule.onNodeWithTag("tuned").assertTextEquals("Tuned: 5000.000 kHz")
            rule.onNodeWithTag("status").assertTextEquals("Stopped")
            rule.onNodeWithTag("rssi").assertTextEquals("RSSI: —")
            rule.onNodeWithTag("frequency").performTextReplacement("not-a-frequency")
            rule.onNodeWithText("Tune").performClick()
            rule.onNodeWithTag("error").assertExists()
            rule.onNodeWithTag("tuned").assertTextEquals("Tuned: 5000.000 kHz")
        } finally {
            instrumentation.runOnMainSync {
                radio.setMode(Mode.AM)
                radio.tune(formatKhz(original.tuning.frequencyHz))
                radio.setMode(original.tuning.mode)
                radio.setStep(original.stepHz.toString())
            }
        }
    }
}
