package org.kiwidroid.core

import java.io.IOException
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class MwRankingTest {
    private val boston = GeoPoint(42.36, -71.06)
    private val newYork = GeoPoint(40.71, -74.01)
    private val juneNoon = Instant.parse("2026-06-21T16:47:00Z")
    private val juneMidnight = Instant.parse("2026-06-21T04:47:00Z")

    private fun station(call: String, at: GeoPoint, day: Double?, night: Double?, critical: Double? = null, khz: Int = 1000) =
        MwStation(call.hashCode(), call, khz, "US", "", "", "B", day, night, critical, false, false,
            at.latitude, at.longitude, MwStation.LICENSED)

    /** Point [km] kilometres due north of Boston. */
    private fun north(km: Double) = GeoPoint(boston.latitude + km / 111.195, boston.longitude)

    @Test fun `sun elevation matches solstice culminations`() {
        assertEquals(90 - 42.36 + 23.44, sunElevationDeg(boston, juneNoon), 0.5)
        assertEquals(42.36 + 23.44 - 90, sunElevationDeg(boston, juneMidnight), 0.5)
        assertEquals(90 - 42.36 - 23.44, sunElevationDeg(boston, Instant.parse("2026-12-21T16:42:00Z")), 0.5)
    }

    @Test fun `authorization period follows sunrise, critical hours and sunset at the station`() {
        assertEquals(MwPeriod.DAY, mwPeriod(boston, juneNoon))
        assertEquals(MwPeriod.NIGHT, mwPeriod(boston, juneMidnight))
        // Boston sunrise on the June solstice is about 09:07 UTC.
        assertEquals(MwPeriod.NIGHT, mwPeriod(boston, Instant.parse("2026-06-21T08:55:00Z")))
        assertEquals(MwPeriod.CRITICAL, mwPeriod(boston, Instant.parse("2026-06-21T10:00:00Z")))
        assertEquals(MwPeriod.DAY, mwPeriod(boston, Instant.parse("2026-06-21T11:30:00Z")))
        val daytimer = station("WDAY", boston, 5.0, null, critical = 1.0)
        assertEquals(1.0, daytimer.powerKw(MwPeriod.CRITICAL))
        assertEquals(5.0, station("WFUL", boston, 5.0, 1.0).powerKw(MwPeriod.CRITICAL))
        assertNull(daytimer.powerKw(MwPeriod.NIGHT))
    }

    @Test fun `distance bearing and midpoint`() {
        assertEquals(306.0, boston.distanceKm(newYork), 3.0)
        assertEquals("SW", compassPoint(boston.bearingTo(newYork)))
        assertEquals("NE", compassPoint(newYork.bearingTo(boston)))
        assertEquals("N", compassPoint(359.0))
        assertEquals(GeoPoint(0.0, 45.0).toString(), MwRanker.midpoint(GeoPoint(0.0, 0.0), GeoPoint(0.0, 90.0)).let {
            GeoPoint(Math.round(it.latitude * 1e6) / 1e6, Math.round(it.longitude * 1e6) / 1e6).toString()
        })
        assertEquals("1,180 km", formatDistance(1180.4))
    }

    @Test fun `daytime ranking prefers nearby ground wave`() {
        val near = station("WNEAR", north(50.0), 1.0, 1.0)
        val far = station("WFAR", north(1000.0), 50.0, 50.0)
        val stronger = station("WBIG", north(50.0), 5.0, 5.0)
        val ranked = MwRanker.rank(listOf(far, near, stronger), boston, juneNoon)
        assertEquals(listOf("WBIG", "WNEAR", "WFAR"), ranked.map { it.station.call })
        assertTrue(ranked.none { it.skywave })
        assertEquals(MwPeriod.DAY, ranked.first().period)
    }

    @Test fun `night ranking lets a distant high-power station beat a weak regional one`() {
        val regional = station("WREG", north(300.0), 0.25, 0.25)
        val distant = station("WCLR", north(1000.0), 50.0, 50.0)
        val daytimer = station("WDAY", north(10.0), 10.0, null)
        assertEquals(listOf("WREG", "WCLR"), MwRanker.rank(listOf(distant, regional), boston, juneNoon).map { it.station.call })
        val night = MwRanker.rank(listOf(regional, daytimer, distant), boston, juneMidnight)
        assertEquals(listOf("WCLR", "WREG", "WDAY"), night.map { it.station.call })
        assertTrue(night.first().skywave)
        assertFalse(night.last().onAir)
        assertNull(night.last().kw)
    }

    @Test fun `higher frequencies lose more ground wave`() {
        val low = MwRanker.rank(station("WLOW", north(300.0), 1.0, 1.0, khz = 600), boston, juneNoon)
        val high = MwRanker.rank(station("WHI", north(300.0), 1.0, 1.0, khz = 1600), boston, juneNoon)
        // 3 dB per 100 km at 1 MHz: 9 dB more excess loss at 1600 than 600 kHz over 300 km.
        assertEquals(9.0, low.scoreDb - high.scoreDb, 0.01)
    }

    @Test fun `coordinates parse and validate`() {
        assertEquals(GeoPoint(42.5, -71.25), GeoPoint.parse(" 42,5 ", "−71.25"))
        assertFailsWith<IllegalArgumentException> { GeoPoint.parse("north", "-71") }
        assertFailsWith<IllegalArgumentException> { GeoPoint.parse("91", "0") }
        assertFailsWith<IllegalArgumentException> { GeoPoint.parse("0", "-181") }
    }

    @Test fun `status location parsing ignores unset and malformed gps`() {
        val fields = ReceiverStatus.parse("status=active\nname=Test Kiwi\ngps=(44.100000, -85.500000)\nloc=Cadillac, MI\n")
        assertEquals("Cadillac, MI", fields["loc"])
        assertEquals(GeoPoint(44.1, -85.5), ReceiverStatus.location(fields))
        assertNull(ReceiverStatus.location(ReceiverStatus.parse("gps=(0.000000, 0.000000)")))
        assertNull(ReceiverStatus.location(ReceiverStatus.parse("gps=unknown")))
        assertNull(ReceiverStatus.location(ReceiverStatus.parse("gps=(95.0, 10.0)")))
        assertNull(ReceiverStatus.location(ReceiverStatus.parse("status=active")))
    }

    @Test fun `status fetch reads the receiver endpoint and does not follow redirects`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("status=active\ngps=(51.5, -0.1)\n"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/status"))
            val receiver = Receiver("Mock", server.url("/").toString())
            assertEquals(GeoPoint(51.5, -0.1), ReceiverStatus.location(ReceiverStatus.fetch(receiver)))
            assertEquals("/status", server.takeRequest().path)
            assertFailsWith<IOException> { ReceiverStatus.fetch(receiver) }
            assertEquals(2, server.requestCount)
        }
    }
}
