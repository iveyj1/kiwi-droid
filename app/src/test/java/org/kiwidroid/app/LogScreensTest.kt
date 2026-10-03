package org.kiwidroid.app

import java.time.Instant
import kotlin.test.*
import org.kiwidroid.core.*

class LogScreensTest {
    private fun station(call: String, khz: Int, place: String, facility: Int?) = LoggedStation(facility, khz, call, place)
    private fun entry(station: LoggedStation?, confidence: LogConfidence, hz: Long = 1_030_000) = LogEntry(
        utc = Instant.parse("2026-10-03T04:00:00Z"), receiverName = "Kiwi", receiverUrl = "http://k/", receiverLocation = null,
        frequencyHz = hz, mode = Mode.AM, passband = Mode.AM.defaultPassband, rssiDbm = null, rssiMinDbm = null,
        rssiMaxDbm = null, station = station, confidence = confidence)

    private val wbz = station("WBZ", 1030, "Boston, MA, US", 25444)
    private val cjbc = station("CJBC", 860, "Toronto, ON, CA", 98913)

    @Test fun `stats count distinct identified stations, regions and countries`() {
        val entries = listOf(entry(wbz, LogConfidence.HEARD_ID), entry(wbz, LogConfidence.HEARD_ID),
            entry(cjbc, LogConfidence.TENTATIVE), entry(LoggedStation(null, null, "Radio Rebelde"), LogConfidence.HEARD_ID),
            entry(null, LogConfidence.UNIDENTIFIED))
        assertEquals("5 entries · 2 stations identified (+1 tentative) · 1 states/provinces · 1 country", logStats(entries))
        assertEquals("0 entries · 0 stations identified · 0 states/provinces · 0 countries", logStats(emptyList()))
    }

    @Test fun `heard marks prefer the strongest confidence and ignore unidentified entries`() {
        val heard = heardStations(listOf(entry(wbz, LogConfidence.TENTATIVE), entry(wbz, LogConfidence.HEARD_ID),
            entry(cjbc, LogConfidence.TENTATIVE), entry(null, LogConfidence.UNIDENTIFIED)))
        assertEquals(mapOf((25444 to 1030) to LogConfidence.HEARD_ID, (98913 to 860) to LogConfidence.TENTATIVE), heard)
        val row = MwStation(25444, "WBZ", 1030, "US", "MA", "Boston", "A", 50.0, 50.0, null, false, false, 42.0, -71.0, MwStation.LICENSED)
        assertEquals("✓ logged", heardMark(row, heard))
        assertEquals("? logged tentatively", heardMark(row.copy(facilityId = 1, mergedFacilityIds = listOf(98913), khz = 860), heard))
        assertNull(heardMark(row.copy(khz = 1040), heard))
    }

    @Test fun `log candidates are the MW stations inside the logged passband`() {
        val rows = listOf(1000, 1010, 1030).map { khz ->
            MwStation(khz, "W$khz", khz, "US", "", "", "B", 1.0, 1.0, null, false, false, 42.0, -71.0, MwStation.LICENSED)
        }
        val db = MwStationDb(rows)
        val at = Instant.parse("2026-10-03T04:00:00Z")
        assertEquals(listOf("W1010", "W1000"), logCandidates(db, entry(null, LogConfidence.UNIDENTIFIED, 1_006_000)
            .copy(passband = Passband(-6000, 6000)), MwVisibility(), at).map { it.first.call })
        assertEquals(emptyList(), logCandidates(db, entry(null, LogConfidence.UNIDENTIFIED, 5_000_000), MwVisibility(), at))
        assertEquals(emptyList(), logCandidates(null, entry(null, LogConfidence.UNIDENTIFIED), MwVisibility(), at))
        val ranked = logCandidates(db, entry(null, LogConfidence.UNIDENTIFIED).copy(receiverLocation = GeoPoint(42.0, -72.0)), MwVisibility(), at)
        assertEquals("W1030", ranked.single().first.call)
        assertNotNull(ranked.single().second)
    }

    @Test fun `RSSI summary shows the window when recorded`() {
        assertEquals("RSSI not recorded (not playing)", rssiSummary(entry(null, LogConfidence.UNIDENTIFIED)))
        assertEquals("RSSI −62.5 dBm (−71.0 … −58.0 over 10 s)",
            rssiSummary(entry(null, LogConfidence.UNIDENTIFIED).copy(rssiDbm = -62.5, rssiMinDbm = -71.0, rssiMaxDbm = -58.0)))
    }
}
