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
                // Rank by power, not by any location the user has set, so WBZ leads 1030 kHz.
                radio.saveReceiver(original.selected, original.receiver.name, original.receiver.url)
            }
            rule.onNodeWithTag("mwStations").assertDoesNotExist()
            rule.onNodeWithTag("frequency").performTextReplacement("1030")
            rule.onNodeWithText("Tune").performClick()
            rule.onNodeWithTag("mwChannel").performScrollTo().assertTextContains("1030 kHz: WBZ Boston", substring = true)

            instrumentation.runOnMainSync { radio.setMode(Mode.CW); radio.tune("1000.000") }
            rule.onNodeWithTag("mwStations").performScrollTo().performClick()
            rule.onNodeWithTag("mwSearch").performTextReplacement("WBZ")
            rule.onNodeWithText("0 stations · tap to tune", substring = true).assertExists()
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
            rule.onNodeWithText("1 station · tap to tune", substring = true).assertExists()
            rule.onNodeWithText("790 WLBE").assertExists()
            rule.onNodeWithText("780 WLBE").assertDoesNotExist()
            rule.onNodeWithTag("mwPermits").performClick()
            rule.onNodeWithText("2 stations · tap to tune", substring = true).assertExists()
            rule.onNodeWithText("780 WLBE").assertExists()
            rule.runOnIdle { assertTrue(radio.state.value.settings.mwIncludePermits) }
            rule.onNodeWithText("Close").performClick()
        } finally {
            instrumentation.runOnMainSync {
                radio.setMode(Mode.AM)
                radio.tune(formatKhz(original.tuning.frequencyHz))
                radio.setMode(original.tuning.mode)
                radio.setMwIncludePermits(original.mwIncludePermits)
                val location = original.receiverLocation
                radio.saveReceiver(original.selected, original.receiver.name, original.receiver.url,
                    location?.latitude?.toString() ?: "", location?.longitude?.toString() ?: "")
            }
        }
    }

    @Test fun receiverLocationRanksAndFavoritesTuneFromAnyBand() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Unlock phone first", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val app = context.applicationContext as KiwiApplication
        val radio = app.radio
        rule.waitUntil(5000) { radio.state.value.loaded }
        rule.waitUntil(10000) { app.stations.state.value.db != null }
        val original = radio.state.value.settings
        val wbz = checkNotNull(app.stations.state.value.db).onChannel(1030).single { it.call == "WBZ" }
        try {
            instrumentation.runOnMainSync {
                radio.stop(); radio.setMode(Mode.AM); radio.tune("1030.000")
                radio.saveReceiver(original.selected, original.receiver.name, original.receiver.url)
                if (radio.state.value.settings.favorites.any { it.matches(wbz) }) radio.toggleFavorite(wbz)
            }
            rule.onNodeWithText("Set this receiver's location", substring = true).performScrollTo().assertExists()

            // Location is edited with the receiver and shown in the list; it does not change the receiver itself.
            rule.onNodeWithText("Servers").performScrollTo().performClick()
            rule.onAllNodesWithText("Edit")[original.selected].performScrollTo().performClick()
            rule.onNodeWithTag("receiverLatitude").performTextReplacement("44.1")
            rule.onNodeWithTag("receiverLongitude").performTextReplacement("-85.5")
            rule.onNodeWithText("Save").performClick()
            rule.onNodeWithText("44.1, -85.5").performScrollTo().assertExists()
            rule.onNodeWithText("Done").performClick()
            rule.runOnIdle {
                assertEquals(original.receiver, radio.state.value.settings.receiver)
                assertEquals(org.kiwidroid.core.GeoPoint(44.1, -85.5), radio.state.value.settings.receiverLocation)
            }
            rule.onNodeWithTag("mwChannel").performScrollTo().assertTextContains(" km ", substring = true)

            rule.onNodeWithTag("mwStations").performScrollTo().performClick()
            rule.onNodeWithText("most likely first", substring = true).assertExists()
            rule.onNodeWithTag("mwScopeAll").performClick()
            rule.onNodeWithTag("mwSearch").performTextReplacement("WBZ")
            rule.onNodeWithContentDescription("Add WBZ 1030 to favorites").performClick()
            rule.onNodeWithText("Close").performClick()

            // Favorites stay available outside the MW band, where the station panel is hidden.
            instrumentation.runOnMainSync { radio.tune("5000.000") }
            rule.onNodeWithTag("mwStations").assertDoesNotExist()
            rule.onNodeWithText("1030 WBZ").performScrollTo().performClick()
            rule.onNodeWithTag("tuned").assertTextEquals("Tuned: 1030.000 kHz")

            rule.onNodeWithTag("mwStations").performScrollTo().performClick()
            rule.onNodeWithTag("mwScopeFavorites").performClick()
            rule.onNodeWithTag("mwSearch").performTextReplacement("WBZ")  // ignore the user's own favorites
            rule.onNodeWithText("1 station · tap to tune", substring = true).assertExists()
            rule.onNodeWithContentDescription("Remove WBZ 1030 from favorites").performClick()
            rule.onNodeWithText("0 stations · tap to tune", substring = true).assertExists()
            rule.onNodeWithText("Close").performClick()
            rule.onNodeWithText("1030 WBZ").assertDoesNotExist()

            // Reporting a bad entry hides it everywhere except the Hidden view, where it can be restored.
            rule.onNodeWithTag("mwStations").performScrollTo().performClick()
            rule.onNodeWithTag("mwScopeAll").performClick()
            rule.onNodeWithTag("mwSearch").performTextReplacement("WBZ")
            rule.onNodeWithContentDescription("Hide WBZ 1030 as a bad entry").performClick()
            rule.onNodeWithText("1030 WBZ").assertDoesNotExist()
            rule.onNodeWithTag("mwScopeHidden").performClick()
            rule.onNodeWithText("1 station · tap to tune", substring = true).assertExists()
            rule.onNodeWithText("Close").performClick()
            rule.onAllNodesWithTag("mwChannel").assertAll(hasText("WBZ", substring = true).not())
            rule.onNodeWithTag("mwStations").performScrollTo().performClick()
            rule.onNodeWithTag("mwScopeHidden").performClick()
            rule.onNodeWithTag("mwSearch").performTextReplacement("WBZ")  // ignore the user's own hidden entries
            rule.onNodeWithContentDescription("Restore WBZ 1030").performClick()
            rule.onNodeWithText("0 stations · tap to tune", substring = true).assertExists()
            rule.onNodeWithText("Close").performClick()
            rule.runOnIdle { assertTrue(radio.state.value.settings.mwHidden.none { it.matches(wbz) }) }
        } finally {
            instrumentation.runOnMainSync {
                val location = original.receiverLocation
                radio.saveReceiver(original.selected, original.receiver.name, original.receiver.url,
                    location?.latitude?.toString() ?: "", location?.longitude?.toString() ?: "")
                if (radio.state.value.settings.mwHidden.any { it.matches(wbz) }) radio.toggleHidden(wbz)
                val starred = radio.state.value.settings.favorites.any { it.matches(wbz) }
                if (starred != original.favorites.any { it.matches(wbz) }) radio.toggleFavorite(wbz)
                radio.setMode(Mode.AM)
                radio.tune(formatKhz(original.tuning.frequencyHz))
                radio.setMode(original.tuning.mode)
            }
        }
    }
}
