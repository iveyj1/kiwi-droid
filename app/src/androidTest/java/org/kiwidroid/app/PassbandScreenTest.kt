package org.kiwidroid.app

import android.app.KeyguardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.kiwidroid.core.*

/** No live receiver: validate edits, per-mode recall, and actual preference persistence. */
class PassbandScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun passbandsValidateAndPersistIndependentlyAcrossAllModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Unlock phone first", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val radio = (context.applicationContext as KiwiApplication).radio
        rule.waitUntil(5000) { radio.state.value.loaded }
        val original = radio.state.value.settings.tuning
        fun select(mode: Mode) {
            rule.onNodeWithText("Mode:", substring = true).performScrollTo().performClick()
            rule.onNodeWithText(mode.name, useUnmergedTree = true).performScrollTo().performClick()
        }
        try {
            instrumentation.runOnMainSync {
                radio.stop()
                radio.setMode(Mode.AM)
                radio.tune("5000.000")
                radio.setPassband(Mode.AM.defaultPassband)
            }
            rule.onNodeWithTag("passband").performScrollTo().performClick()
            rule.onNodeWithTag("passbandLow").performTextReplacement("4000")
            rule.onNodeWithTag("passbandHigh").performTextReplacement("1000")
            rule.onNodeWithTag("savePassband").performClick()
            rule.onNodeWithTag("passbandError").assertExists()
            assertEquals(Mode.AM.defaultPassband, radio.state.value.settings.tuning.passband)
            rule.onNodeWithTag("passbandLow").performTextReplacement("-4200")
            rule.onNodeWithTag("passbandHigh").performTextReplacement("4200")
            rule.onNodeWithTag("savePassband").performClick()
            select(Mode.USB)
            rule.onNodeWithTag("passband").performScrollTo().performClick()
            rule.onNodeWithTag("passbandLow").performTextReplacement("250")
            rule.onNodeWithTag("passbandHigh").performTextReplacement("2700")
            rule.onNodeWithTag("savePassband").performClick()
            select(Mode.AM)
            rule.onNodeWithText("Passband: -4200 … 4200 Hz").assertExists()
            select(Mode.USB)
            rule.onNodeWithText("Passband: 250 … 2700 Hz").assertExists()
            rule.onNodeWithTag("passband").performScrollTo().performClick()
            rule.onNodeWithText("Mode defaults").performScrollTo().performClick()
            rule.onNodeWithText("Cancel").performClick()
            assertEquals(Passband(250, 2700), radio.state.value.settings.tuning.passband)
            for (mode in listOf(Mode.SAM, Mode.SAU, Mode.SAL, Mode.SAS, Mode.IQ, Mode.CWN)) {
                select(mode)
                assertEquals(mode, radio.state.value.settings.tuning.mode)
            }
            rule.activityRule.scenario.recreate()
            assertEquals(Mode.CWN, radio.state.value.settings.tuning.mode)
            val expected = radio.state.value.settings.tuning
            runBlocking {
                withTimeout(5000) {
                    while (SettingsStore(context).load().tuning != expected) delay(20)
                }
            }
            assertEquals(Passband(-4200, 4200), expected.passbands[Mode.AM])
            assertEquals(Passband(250, 2700), expected.passbands[Mode.USB])
        } finally {
            instrumentation.runOnMainSync {
                radio.stop()
                radio.setMode(Mode.AM)
                radio.tune("5000.000")
                for (mode in Mode.entries) { radio.setMode(mode); radio.setPassband(original.passbands.getValue(mode)) }
                radio.setMode(Mode.AM)
                radio.tune(formatKhz(original.frequencyHz))
                radio.setMode(original.mode)
            }
        }
    }
}
