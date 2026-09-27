package org.kiwidroid.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Offline device smoke test: never presses Play or contacts any receiver. */
class RadioScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun tuneStepModeAndRotationWithoutNetwork() {
        rule.waitUntil(5000) { rule.onAllNodesWithText("Stopped").fetchSemanticsNodes().isNotEmpty() }
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
        // Leave ordinary defaults for subsequent manual listening.
        rule.onNodeWithTag("frequency").performTextReplacement("5000.000")
        rule.onNodeWithText("Tune").performClick()
        rule.onNodeWithText("Mode:", substring = true).performClick()
        rule.onNodeWithText("AM", useUnmergedTree = true).performClick()
    }
}
