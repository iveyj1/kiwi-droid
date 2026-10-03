package org.kiwidroid.app

import android.app.KeyguardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.kiwidroid.core.LogConfidence
import org.kiwidroid.core.Mode
import org.kiwidroid.core.formatKhz

/** Offline: never presses Play. Removes only the entries it creates; the user's own log is left alone. */
class LogbookScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun logEditTuneAndDeleteAnEntry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Unlock phone first", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val app = context.applicationContext as KiwiApplication
        val radio = app.radio
        rule.waitUntil(5000) { radio.state.value.loaded }
        rule.waitUntil(10000) { app.stations.state.value.db != null }
        val original = radio.state.value.settings
        val marker = "test-${UUID.randomUUID()}"
        fun ours() = radio.logbook.entries.value.filter { marker in it.notes }
        try {
            instrumentation.runOnMainSync {
                radio.stop(); radio.setMode(Mode.AM); radio.tune("1030.000")
                // Without a location candidates are ordered by power, so WBZ leads 1030 kHz.
                radio.saveReceiver(original.selected, original.receiver.name, original.receiver.url)
            }
            rule.onNodeWithTag("logButton").performScrollTo().performClick()
            rule.onNodeWithTag("logRssi").assertTextEquals("RSSI not recorded (not playing)")
            rule.onNodeWithTag("logStation-WBZ").assertIsSelected()
            rule.onNodeWithTag("logConfidence-HEARD_ID").performScrollTo().performClick().assertIsSelected()
            rule.onNodeWithTag("logNotes").performScrollTo().performTextReplacement("$marker clear ID")
            rule.onNodeWithTag("logSave").performClick()
            rule.waitUntil(5000) { ours().size == 1 }
            ours().single().let {
                assertEquals("WBZ", it.station?.call)
                assertEquals(LogConfidence.HEARD_ID, it.confidence)
                assertEquals(1_030_000L, it.frequencyHz)
                assertNull(it.rssiDbm)
            }

            // The station list marks logged stations.
            rule.onNodeWithTag("mwStations").performScrollTo().performClick()
            rule.onNodeWithText("✓ logged", substring = true).assertExists()
            rule.onNodeWithText("Close").performClick()

            // From another band, the logbook tunes back to the entry.
            instrumentation.runOnMainSync { radio.tune("5000.000") }
            rule.onNodeWithTag("logbookButton").performScrollTo().performClick()
            rule.onNodeWithTag("logSearch").performTextReplacement(marker)
            rule.onAllNodesWithTag("logEntry").assertCountEquals(1)
            rule.onNodeWithTag("logTune").performClick()
            rule.onNodeWithTag("tuned").assertTextEquals("Tuned: 1030.000 kHz")

            // Edit, then delete with the two-tap confirmation.
            rule.onNodeWithTag("logbookButton").performScrollTo().performClick()
            rule.onNodeWithTag("logSearch").performTextReplacement(marker)
            rule.onNodeWithTag("logEdit").performClick()
            rule.onNodeWithTag("logNotes").performScrollTo().performTextReplacement("$marker edited")
            rule.onNodeWithTag("logSave").performClick()
            rule.waitUntil(5000) { ours().singleOrNull()?.notes == "$marker edited" }
            rule.onNodeWithTag("logSearch").performTextReplacement(marker)
            rule.onNodeWithTag("logEdit").performClick()
            rule.onNodeWithTag("logDelete").performScrollTo().performClick()
            rule.onNodeWithText("Tap again to delete").performClick()
            rule.waitUntil(5000) { ours().isEmpty() }
            rule.onAllNodesWithTag("logEntry").assertCountEquals(0)
        } finally {
            instrumentation.runOnMainSync {
                ours().forEach { radio.deleteLog(it.id) }
                val location = original.receiverLocation
                radio.saveReceiver(original.selected, original.receiver.name, original.receiver.url,
                    location?.latitude?.toString() ?: "", location?.longitude?.toString() ?: "")
                radio.setMode(Mode.AM)
                radio.tune(formatKhz(original.tuning.frequencyHz))
                radio.setMode(original.tuning.mode)
            }
        }
    }
}
