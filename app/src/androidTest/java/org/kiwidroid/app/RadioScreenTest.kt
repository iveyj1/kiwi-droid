package org.kiwidroid.app

import android.app.KeyguardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.kiwidroid.core.Mode
import org.kiwidroid.core.formatKhz
import org.kiwidroid.core.RssiLatchMode
import org.kiwidroid.core.SoundSettings
import org.kiwidroid.core.parsePitchPoints

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
            instrumentation.runOnMainSync { radio.stop(); radio.setMode(Mode.AM); radio.setSound(SoundSettings()) }
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
            rule.onNodeWithTag("radioAudioSwitch").performScrollTo().assertIsOn().performClick().assertIsOff()
            rule.onNodeWithTag("rssiToneSwitch").performScrollTo().assertIsOff().performClick().assertIsOn()
            rule.onNodeWithText("Sound settings").performScrollTo().performClick()
            rule.onNodeWithTag("pitchPoints").performScrollTo().performTextReplacement("-90=C4\n-100=C5")
            rule.onNodeWithTag("saveSound").performClick()
            rule.onNodeWithTag("soundError").assertExists()
            val mapping = "-110=C3\n-90=C5\n-70=C7"
            rule.onNodeWithTag("pitchPoints").performTextReplacement(mapping)
            rule.onNodeWithText("Latch:", substring = true).performScrollTo().performClick()
            rule.onNodeWithText("Peaks only", useUnmergedTree = true).performClick()
            rule.onNodeWithTag("latchHold").performScrollTo().performTextReplacement("1500")
            rule.onNodeWithTag("latchThreshold").performScrollTo().performTextReplacement("2.5")
            rule.onNodeWithTag("saveSound").performClick()
            rule.runOnIdle {
                org.junit.Assert.assertEquals(parsePitchPoints(mapping), radio.state.value.settings.sound.tones.points)
                org.junit.Assert.assertEquals(RssiLatchMode.PEAKS, radio.state.value.settings.sound.tones.latchMode)
                org.junit.Assert.assertEquals(1500, radio.state.value.settings.sound.tones.latchHoldMs)
                org.junit.Assert.assertEquals(2.5, radio.state.value.settings.sound.tones.latchThresholdDb, 0.0001)
            }
            rule.activityRule.scenario.recreate()
            rule.onNodeWithTag("radioAudioSwitch").performScrollTo().assertIsOff()
            rule.onNodeWithTag("rssiToneSwitch").performScrollTo().assertIsOn()
        } finally {
            instrumentation.runOnMainSync {
                radio.setMode(Mode.AM)
                radio.tune(formatKhz(original.tuning.frequencyHz))
                radio.setMode(original.tuning.mode)
                radio.setStep(original.stepHz.toString())
                radio.setSound(original.sound)
            }
        }
    }
}
