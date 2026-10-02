package org.kiwidroid.core

import java.io.StringReader
import kotlin.test.*

class MwStationsTest {
    private val fixture = checkNotNull(javaClass.getResource("/fcc-amq-sample.txt")).readText()
    private val stations = FccAmImport.convert(fixture.lineSequence())
    private fun station(call: String) = stations.single { it.call == call }

    @Test fun `FCC records outside North America, pending applications and deleted calls are excluded`() {
        assertEquals(listOf("KFI", "NEW", "CFTR", "WLBE", "CJBC", "WKLF", "WBZ", "XENVA2", "XENVA2", "XEB", "XEUK"),
            stations.map { it.call })
    }

    @Test fun `treaty notifications are distinguished from US licences and construction permits`() {
        assertEquals(MwStation.LICENSED, station("WBZ").status)
        assertEquals(MwStation.PERMIT, station("WLBE").status)
        assertTrue(station("WLBE").isPermit)
        listOf("CJBC", "NEW", "XEB").forEach { assertEquals(MwStation.NOTIFIED, station(it).status) }
    }

    @Test fun `repeated notifications for one site merge, newest facility first`() {
        val cftr = station("CFTR")
        assertEquals(790645, cftr.facilityId)
        assertEquals(listOf(98798, 790636), cftr.mergedFacilityIds)
        assertEquals(50.0 to 50.0, cftr.dayKw to cftr.nightKw)
        assertEquals(43.1515, cftr.latitude, 0.0001)
        val xeuk = station("XEUK")
        assertEquals(1.0 to 0.25, xeuk.dayKw to xeuk.nightKw)
        assertEquals(listOf(103636), xeuk.mergedFacilityIds)
    }

    @Test fun `placeholder calls never merge even at identical coordinates`() {
        val placeholders = stations.filter { it.call == "XENVA2" }
        assertEquals(listOf("Cd.juarez", "Piedras Negras"), placeholders.map { it.city })
        assertTrue(placeholders.all { it.mergedFacilityIds.isEmpty() })
        assertTrue(FccAmImport.isPlaceholderCall("NEW") && FccAmImport.isPlaceholderCall("XE") &&
            FccAmImport.isPlaceholderCall("") && FccAmImport.isPlaceholderCall("831219"))
        assertFalse(FccAmImport.isPlaceholderCall("XEW"))
    }

    @Test fun `great circle distance`() {
        assertEquals(111.195, distanceKm(0.0, 0.0, 0.0, 1.0), 0.001)
        assertEquals(0.0, distanceKm(42.0, -71.0, 42.0, -71.0), 1e-9)
    }

    @Test fun `unlimited station has one location and directional flags for both periods`() {
        val wbz = station("WBZ")
        assertEquals(MwStation(25444, "WBZ", 1030, "US", "MA", "Boston", "A", 50.0, 50.0, null, true, true,
            42 + 16 / 60.0 + 44.4 / 3600, -(70 + 52 / 60.0 + 32.2 / 3600), "LIC"), wbz)
        assertEquals("Boston, MA, US", wbz.place)
    }

    @Test fun `separate day night and critical hours records merge into one station`() {
        val baie = station("NEW")
        assertEquals(Triple(10.0, 2.0, 5.84), Triple(baie.dayKw, baie.nightKw, baie.criticalKw))
        assertEquals("Baie-St-Paul", baie.city)
        assertEquals(MwStation.NOTIFIED, baie.status)
        val kfi = station("KFI")
        assertEquals(50.0 to 50.0, kfi.dayKw to kfi.nightKw)
    }

    @Test fun `licensed records win over construction permits for the same facility`() {
        val wklf = station("WKLF")
        assertEquals("LIC", wklf.status)
        assertEquals(1.0, wklf.dayKw)
        assertNull(wklf.nightKw)
        assertFalse(wklf.directionalDay)
    }

    @Test fun `specific day and night records take precedence over unlimited`() {
        val xeb = station("XEB")
        assertEquals("San Lorenzo Tezonco, DF, MX", xeb.place)
        assertEquals(19 + 18 / 60.0 + 31 / 3600.0, xeb.latitude, 1e-9)
    }

    @Test fun `table round trips through the asset format with metadata`() {
        val text = StringBuilder().also { MwStationDb.write(stations, mapOf("source" to "test: fixture"), it) }.toString()
        val db = MwStationDb.read(StringReader(text))
        assertEquals(mapOf("source" to "test: fixture"), db.metadata)
        assertEquals(stations.size, db.stations.size)
        db.stations.zip(stations).forEach { (read, original) ->
            assertEquals(original.copy(latitude = 0.0, longitude = 0.0), read.copy(latitude = 0.0, longitude = 0.0))
            assertEquals(original.latitude, read.latitude, 0.0001)
            assertEquals(original.longitude, read.longitude, 0.0001)
        }
    }

    @Test fun `malformed tables are rejected`() {
        assertFailsWith<IllegalArgumentException> { MwStationDb.read(StringReader("# only: metadata\n")) }
        assertFailsWith<IllegalArgumentException> { MwStationDb.read(StringReader("${MwStationDb.HEADER}\n1\tX\n")) }
        assertFailsWith<IllegalArgumentException> { MwStationDb.read(StringReader("facility\tcall\n")) }
    }

    @Test fun `channels inside the passband follow mode and cuts`() {
        assertEquals(listOf(1000), mwChannelsInPassband(Tuning(1_000_000, Mode.AM)))
        assertEquals(setOf(1000, 1010), mwChannelsInPassband(Tuning(1_005_000, Mode.AM)).toSet())
        assertEquals(listOf(1000), mwChannelsInPassband(Tuning(1_004_000, Mode.AM).withPassband(Passband(-4000, 4000))))
        assertEquals(listOf(1010), mwChannelsInPassband(Tuning(1_007_000, Mode.AM)))
        assertEquals(listOf(1010, 1000), mwChannelsInPassband(Tuning(1_006_000, Mode.AM).withPassband(Passband(-6000, 6000))))
        assertEquals(listOf(1000), mwChannelsInPassband(Tuning(999_500, Mode.USB)))
        assertEquals(emptyList(), mwChannelsInPassband(Tuning(1_000_500, Mode.USB)))
        assertEquals(listOf(1000), mwChannelsInPassband(Tuning(1_002_000, Mode.LSB)))
        assertEquals(listOf(1000), mwChannelsInPassband(Tuning(1_000_000, Mode.CW)))
        assertEquals(emptyList(), mwChannelsInPassband(Tuning(1_001_000, Mode.CWN)))
        assertEquals(listOf(530), mwChannelsInPassband(Tuning(527_000, Mode.AM)))
        assertEquals(emptyList(), mwChannelsInPassband(Tuning(5_000_000, Mode.AM)))
    }

    @Test fun `tuning to a station keeps AM-family and sideband modes but leaves CW and IQ`() {
        assertEquals(Tuning(1_030_000, Mode.SAM), Tuning(1_034_100, Mode.SAM).toBroadcastChannel(1030))
        assertEquals(Mode.USB, Tuning(mode = Mode.USB).toBroadcastChannel(1030).mode)
        listOf(Mode.CW, Mode.CWN, Mode.IQ).forEach { mode ->
            assertEquals(Tuning(1_030_000, Mode.AM), Tuning(mode = mode).toBroadcastChannel(1030))
        }
    }

    @Test fun `search matches frequency call sign and place`() {
        val db = MwStationDb(stations)
        assertEquals(stations.filterNot { it.isPermit }.map { it.call }.sorted(), db.search("").map { it.call }.sorted())
        assertEquals(stations.size, db.search("", includePermits = true).size)
        assertEquals(emptyList(), db.search("WLBE"))
        assertEquals(listOf("WLBE"), db.search("WLBE", includePermits = true).map { it.call })
        assertEquals(emptyList(), db.onChannel(780))
        assertEquals(listOf("WLBE"), db.onChannel(780, includePermits = true).map { it.call })
        assertEquals(listOf("WBZ"), db.search("1030").map { it.call })
        assertEquals(listOf("WKLF", "WBZ", "XENVA2", "XENVA2"), db.search("10").map { it.call })
        assertEquals(listOf("WBZ"), db.search(" wbz ").map { it.call })
        assertEquals(listOf("CFTR", "CJBC"), db.search("toronto").map { it.call })
        assertEquals(listOf("XEB"), db.search("tezonco").map { it.call })
        assertEquals(4, db.search(", MX").size)
        assertEquals(listOf("WBZ"), db.onChannel(1030).map { it.call })
        assertEquals(emptyList(), db.onChannel(1040))
    }
}
