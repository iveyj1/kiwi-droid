package org.kiwidroid.core

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class LogbookTest {
    private val wbz = LoggedStation(25444, 1030, "WBZ", "Boston, MA, US", "News/talk", 1201.4, 88.2)
    private fun entry(id: String = "e1", notes: String = "Clear ID at top of hour", station: LoggedStation? = wbz) = LogEntry(
        id = id, utc = Instant.parse("2026-10-03T04:00:12Z"), receiverName = "Local Kiwi 41",
        receiverUrl = "http://10.0.0.41:8073", receiverLocation = GeoPoint(44.1, -85.5), frequencyHz = 1_030_000,
        mode = Mode.SAM, passband = Passband(-4000, 4000), rssiDbm = -62.5, rssiMinDbm = -71.0, rssiMaxDbm = -58.25,
        station = station, confidence = LogConfidence.HEARD_ID, notes = notes)

    private val station = MwStation(25444, "WBZ", 1030, "US", "MA", "Boston", "A", 50.0, 50.0, null,
        false, false, 42.0, -71.0, MwStation.LICENSED, mergedFacilityIds = listOf(111))

    @Test fun `station log action uses latest timestamp including aliases regardless of confidence`() {
        val old = entry()
        val latest = entry("new").copy(utc = old.utc.plusSeconds(60), confidence = LogConfidence.UNIDENTIFIED,
            station = wbz.copy(facilityId = 111))
        val wrongChannel = entry("other").copy(utc = old.utc.plusSeconds(120), station = wbz.copy(khz = 1040))
        assertEquals(latest, latestStationLog(listOf(old, wrongChannel, latest), station))
        assertNull(latestStationLog(listOf(wrongChannel), station))
    }

    @Test fun `station draft prefills identity and frequency without misattributing RSSI`() {
        val same = entry(station = null).forStation(station)
        assertEquals("WBZ", same.station!!.call)
        assertEquals(-62.5, same.rssiDbm)
        val narrow = entry().copy(mode = Mode.CWN, passband = Passband(470, 530)).forStation(station)
        assertEquals(Mode.CWN, narrow.mode)
        assertEquals(Passband(470, 530), narrow.passband)
        val other = entry(station = null).forStation(station.copy(khz = 950))
        assertEquals(950000, other.frequencyHz)
        assertNull(other.rssiDbm)
        assertNull(other.rssiMinDbm)
        assertNull(other.rssiMaxDbm)
    }

    @Test fun `entries round trip through JSON lines, including absent optional fields`() {
        val full = entry(notes = "Line one\nline \"two\", with comma")
        assertEquals(full, LogCodec.decode(LogCodec.encode(full)))
        val sparse = full.copy(receiverLocation = null, rssiDbm = null, rssiMinDbm = null, rssiMaxDbm = null,
            station = LoggedStation(null, null, "Radio Rebelde"), confidence = LogConfidence.TENTATIVE, notes = "")
        assertEquals(sparse, LogCodec.decode(LogCodec.encode(sparse)))
        assertEquals(null, LogCodec.decode(LogCodec.encode(full.copy(station = null))).station)
        assertFalse('\n' in LogCodec.encode(full))
        val future = LogCodec.encode(full).replaceFirst("{", "{\"addedLater\":true,")
        assertEquals(full, LogCodec.decode(future))
        assertFailsWith<IllegalArgumentException> { LogCodec.decode(LogCodec.encode(full).replace("\"v\":1", "\"v\":2")) }
    }

    @Test fun `CSV export quotes fields and neutralizes formula prefixes`() {
        val text = StringBuilder().also {
            LogCsv.write(listOf(entry(id = "b").copy(utc = Instant.parse("2026-10-03T05:00:00Z")),
                entry(id = "a", notes = "=HYPERLINK(\"x\"), \"quoted\"\nnext")), it)
        }.toString()
        val lines = text.split("\r\n")
        assertEquals(LogCsv.HEADER.joinToString(","), lines[0])
        assertTrue(lines[1].startsWith("2026-10-03T04:00:12Z,1030.000,SAM,-4000,4000,WBZ,Heard ID,\"Boston, MA, US\",News/talk,1201,88,-62.5,-71.0,-58.3"))
        assertTrue("\"'=HYPERLINK(\"\"x\"\"), \"\"quoted\"\"\nnext\"" in text)
        assertEquals("-62.5", LogCsv.quote("-62.5"))
        assertEquals("'-cmd", LogCsv.quote("-cmd"))
        assertTrue(lines[2].startsWith("2026-10-03T05:00:00Z"))
    }

    @Test fun `RSSI window keeps extremes of fresh readings only`() {
        val window = RssiWindow(10_000)
        assertNull(window.snapshot(0))
        window.add(0, -80.0); window.add(5_000, -60.0); window.add(9_000, -70.0)
        assertEquals(Triple(-70.0, -80.0, -60.0), window.snapshot(9_000))
        window.add(12_000, -75.0)
        assertEquals(Triple(-75.0, -75.0, -60.0), window.snapshot(12_000))
        assertNull(window.snapshot(30_000))
        window.clear()
        assertNull(window.snapshot(12_000))
    }

    @Test fun `logbook appends, edits, deletes, and preserves unreadable lines`() = runBlocking {
        val dir = Files.createTempDirectory("logbook").toFile()
        try {
            val file = File(dir, "mwlog.jsonl")
            file.writeText("not json\n")
            val book = Logbook(file)
            book.load()
            assertEquals(1, book.unreadableLines)
            book.add(entry("a"))
            book.add(entry("b", notes = "second"))
            assertEquals(listOf("a", "b"), book.entries.value.map { it.id })
            book.replace(entry("a", notes = "edited"))
            book.delete("b")
            assertFailsWith<IllegalArgumentException> { book.replace(entry("missing")) }
            val reloaded = Logbook(file).also { it.load() }
            assertEquals(listOf(entry("a", notes = "edited")), reloaded.entries.value)
            assertEquals(1, reloaded.unreadableLines)
            assertEquals("not json", file.readLines().first())
            assertFalse(File(dir, "mwlog.jsonl.tmp").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `logged stations match database rows through merged facility IDs`() {
        val cftr = MwStation(790645, "CFTR", 680, "CA", "ON", "Toronto", "B", 50.0, 50.0, null, false, false, 43.0, -79.0,
            MwStation.LICENSED, mergedFacilityIds = listOf(98798))
        assertTrue(LoggedStation(98798, 680, "CFTR").matches(cftr))
        assertFalse(LoggedStation(98798, 690, "CFTR").matches(cftr))
        assertFalse(LoggedStation(null, 680, "CFTR").matches(cftr))
        assertEquals("2026-10-03 04:00Z", formatLogTime(Instant.parse("2026-10-03T04:00:59Z")))
        assertFailsWith<IllegalArgumentException> { LoggedStation(null, null, " ") }
    }
}
