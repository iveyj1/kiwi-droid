package org.kiwidroid.core

import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class MwSourcesTest {
    private fun station(call: String, khz: Int, country: String = "US", status: String = MwStation.LICENSED, id: Int = call.hashCode()) =
        MwStation(id, call, khz, country, "", "", "B", 1.0, 1.0, null, false, false, 40.0, -80.0,
            if (country == "US") status else MwStation.NOTIFIED)

    @Test fun `LMS facility table parses AM rows only`() {
        val text = "active_ind|callsign|facility_id|facility_status|frequency|service_code|^|\n" +
            "Y|WBZ|25444|LICEN|1030|AM|^|\nY|DWPLI|41111|LICAN|610|AM|^|\nY|WXYZ-FM|1|LICEN|99.1|FM|^|\n" +
            "Y|WKTR|73191|LICEN|640.0|AM|^|\n"
        val lms = LmsAm.parse(text)
        assertEquals(setOf(25444, 41111, 73191), lms.keys)
        assertEquals(LmsAm.Facility(73191, "WKTR", 640, "LICEN"), lms[73191])
    }

    @Test fun `US vetting drops cancelled facilities, flags silent, and keeps channel mismatches for review`() {
        val lms = mapOf(1 to LmsAm.Facility(1, "WAAA", 1000, "LICEN"), 2 to LmsAm.Facility(2, "DWBBB", 610, "LICAN"),
            3 to LmsAm.Facility(3, "WCCC", 700, "LICSL"), 4 to LmsAm.Facility(4, "WNEW", 840, "LICEN"),
            5 to LmsAm.Facility(5, "WEEE", 1030, "LICEN"))
        val report = MwImportReport()
        val out = MwVetting.apply(listOf(station("WAAA", 1000, id = 1), station("WBBB", 610, id = 2), station("WCCC", 700, id = 3),
            station("WOLD", 840, id = 4), station("WEEE", 1000, id = 5), station("WZZZ", 1500, id = 6)),
            MwVetting.Inputs(lms = lms), report)
        assertEquals(listOf("WAAA" to "LIC", "WCCC" to "SILENT", "WNEW" to "LIC", "WEEE" to "LIC", "WZZZ" to "LIC"),
            out.sortedBy { it.facilityId }.map { it.call to it.status })
        assertEquals(1000, out.single { it.call == "WEEE" }.khz)
        assertEquals(MwStation.SOURCE_FCC, out.single { it.call == "WZZZ" }.source)
        assertEquals(MwStation.SOURCE_LMS, out.single { it.call == "WAAA" }.source)
        assertEquals(1, report.summary()["US: LMS frequency differs; kept FCC AM Query channel, review"])
        assertTrue(report.render().contains("WBBB 610"))
    }

    private fun ised(call: String, khz: Int, s1: String, s2: String, day: Int, night: Int, lat: String = "431250",
                     lon: String = "793629", lat2: String = "0", lon2: String = "0", province: String = "ON", doc: String = "90") =
        mapOf("PROVINCE" to province, "CITY" to "Toronto", "CALL_SIGN" to call, "FREQUENCY" to "$khz.00", "CLASS" to "B",
            "STATUS1" to s1, "STATUS2" to s2, "POWERDAY" to "$day", "POWERNIGHT" to "$night", "POWERCRIT" to "0",
            "LATITUDE" to lat, "LONGITUDE" to lon, "LATITUDE2" to lat2, "LONGITUDE2" to lon2, "DOC_FILE" to doc)

    @Test fun `ISED rows merge day and night records and drop allotments`() {
        val rows = listOf(
            ised("CJAD", 800, "OP", "", 50000, 0, lat = "0", lon = "0", lat2 = "451450", lon2 = "733123"),
            ised("CJAD", 800, "", "OP", 0, 50000, lat = "451000", lon = "733000"),
            ised("NEW", 650, "AL", "AL", 10000, 2500, doc = "7"),
            ised("CNEW", 1000, "AU", "AU", 1000, 0, doc = "8"),
            ised("CHLO-HD", 530, "OP", "OP", 1000, 250),
            ised("KUSA", 1000, "OP", "OP", 1000, 1000, province = "TX"))
        val stations = IsedAm.convert(rows, mapOf(("CJAD" to 800) to 9000))
        assertEquals(listOf("CJAD", "CNEW"), stations.map { it.call })
        val cjad = stations.first()
        assertEquals(9000, cjad.facilityId)
        assertEquals(50.0 to 50.0, cjad.dayKw to cjad.nightKw)
        assertEquals(45 + 14 / 60.0 + 50 / 3600.0, cjad.latitude, 1e-9)
        assertEquals(-(73 + 31 / 60.0 + 23 / 3600.0), cjad.longitude, 1e-9)
        assertEquals(MwStation.SOURCE_ISED, cjad.source)
        assertEquals(MwStation.PERMIT, stations[1].status)
        assertEquals(IsedAm.SYNTHETIC_ID_BASE + 8, stations[1].facilityId)
        assertNull(stations[1].nightKw)
    }

    @Test fun `Canadian FCC treaty records are replaced by ISED`() {
        val fcc = listOf(station("CJAD", 800, "CA", id = 9000), station("CFHS", 530, "CA"))
        val report = MwImportReport()
        val out = MwVetting.apply(fcc, MwVetting.Inputs(isedRows = listOf(ised("CJAD", 800, "OP", "OP", 50000, 50000))), report)
        assertEquals(listOf("CJAD"), out.map { it.call })
        assertTrue(report.render().contains("CFHS 530"))
    }

    @Test fun `dBASE reader decodes fields and skips deleted records`() {
        val fields = listOf("CALL_SIGN" to 12, "FREQUENCY" to 7)
        val header = 32 + 32 * fields.size + 1
        val record = 1 + fields.sumOf { it.second }
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 3); putInt(4, 2); putShort(8, header.toShort()); putShort(10, record.toShort())
        }.array())
        fields.forEach { (name, length) ->
            out.write(ByteArray(32).also { name.toByteArray().copyInto(it); it[11] = 'C'.code.toByte(); it[16] = length.toByte() })
        }
        out.write(0x0D)
        out.write(" CJAD         800.00".toByteArray(Charsets.ISO_8859_1))
        out.write("*CDEL         900.00".toByteArray(Charsets.ISO_8859_1))
        assertEquals(listOf(mapOf("CALL_SIGN" to "CJAD", "FREQUENCY" to "800.00")), Dbf.read(out.toByteArray()))
    }

    @Test fun `Mexican lists parse CRT CSV by header and the IFT extract`() {
        val csv = "\uFEFFfolio_electronico,poblacion,estado,tipo,tipo_uso,concesionario,distintivo_adic,banda,frecuencia,tipo_frecuencia\n" +
            "1,Ciudad de México,Ciudad de México,Concesión,Comercial,\"Radiópolis, S.A.\",XEW-AM,AM,900.0,Principal\n" +
            "2,Monterrey,Nuevo León,Concesión,Comercial,X,XHFM,FM,99.3,Principal\n" +
            "3,Santa Rosalía,Baja California Sur,Concesión,Comercial,Y,XERLA,AM,940.0,Adicional\n"
        assertEquals(listOf(MexicoAm.Station("XEW", 900, "Ciudad de México"), MexicoAm.Station("XERLA", 940, "Santa Rosalía")),
            MexicoAm.parseCrt(csv))
        assertEquals(listOf(MexicoAm.Station("XEW", 900)), MexicoAm.parseCrt("Distintivo;Banda;Frecuencia\nXEW;AM;900 kHz\nXHX;FM;99.3\n"))
        assertEquals(listOf(listOf("a", "b,c", "d\"e")), MexicoAm.parseCsv("a,\"b,c\",\"d\"\"e\""))
        assertFailsWith<IllegalArgumentException> { MexicoAm.parseCrt("Folio,Estado\n1,X\n") }
        assertEquals(listOf(MexicoAm.Station("XEHS", 540), MexicoAm.Station("XEW", 900)),
            MexicoAm.parseIft2014(StringReader("# comment\ncall\tkhz\nXEHS\t540\nXEW-AM\t900\n")))
    }

    @Test fun `city keys ignore accents, punctuation and the Cd abbreviation`() {
        assertTrue(MexicoAm.sameCity("Cd.victoria", "Ciudad Victoria"))
        assertTrue(MexicoAm.sameCity("Mexicali", "MEXICALI"))
        assertTrue(MexicoAm.sameCity("Santa Rosalia", "Santa Rosalía"))
        assertFalse(MexicoAm.sameCity("Tijuana", "Tecate"))
        assertFalse(MexicoAm.sameCity("Sin Dato", "Sin Dato"))
    }

    @Test fun `Mexican treaty records are confirmed, corrected or marked unverified`() {
        fun mx(call: String, khz: Int, city: String, id: Int) = station(call, khz, "MX", id = id).copy(city = city)
        val fcc = listOf(
            mx("XEW", 900, "Mexico", 1),             // exact
            mx("XEDF", 970, "Mexico", 2),            // official list moved it to 1500
            mx("XEAI", 1500, "Mexico", 3),           // official list moved it to 1470
            mx("XESW", 970, "Ciudad de México", 4),  // official call is now XERFR
            mx("XE", 1630, "Cd. Juarez", 5),         // placeholder named by kHz + city
            mx("XEOLD", 1000, "Tijuana", 6),         // gone
            station("CMBC", 1350, "CU"))
        val official = listOf(MexicoAm.Station("XEW", 900, "Ciudad de México"), MexicoAm.Station("XEDF", 1500, "Ciudad de México"),
            MexicoAm.Station("XEAI", 1470, "Ciudad de México"), MexicoAm.Station("XERFR", 970, "Ciudad de México"),
            MexicoAm.Station("XEJCZ", 1630, "Ciudad Juárez"), MexicoAm.Station("XENEW", 1620, "Guachochi"))
        val report = MwImportReport()
        val out = MwVetting.apply(fcc, MwVetting.Inputs(mexicoCrt = official), report).associateBy { it.facilityId }
        assertEquals(Triple("XEW", 900, "LIC"), out.getValue(1).let { Triple(it.call, it.khz, it.status) })
        assertEquals(Triple("XEDF", 1500, "LIC"), out.getValue(2).let { Triple(it.call, it.khz, it.status) })
        assertEquals(Triple("XEAI", 1470, "LIC"), out.getValue(3).let { Triple(it.call, it.khz, it.status) })
        assertEquals(Triple("XERFR", 970, "LIC"), out.getValue(4).let { Triple(it.call, it.khz, it.status) })
        assertEquals(Triple("XEJCZ", 1630, "LIC"), out.getValue(5).let { Triple(it.call, it.khz, it.status) })
        assertEquals(MwStation.UNVERIFIED, out.getValue(6).status)
        assertEquals(MwStation.SOURCE_CRT, out.getValue(4).source)
        assertEquals(MwStation.NOTIFIED, out.values.single { it.call == "CMBC" }.status)
        assertTrue(report.render().contains("XENEW 1620 Guachochi"))
        assertTrue(report.render().contains("XESW 970 → XERFR 970"))
        // Without CRT, the 2014 list confirms by call (and moves stale frequencies) but cannot match cities.
        val ift = MwVetting.apply(fcc, MwVetting.Inputs(mexicoIft2014 = listOf(MexicoAm.Station("XEW", 900), MexicoAm.Station("XE", 1630))))
            .associateBy { it.facilityId }
        assertEquals(MwStation.SOURCE_IFT_2014, ift.getValue(1).source)
        assertEquals(MwStation.UNVERIFIED, ift.getValue(5).status)
    }

    @Test fun `visibility hides permits, silent, unverified and user-hidden rows unless asked`() {
        val rows = listOf(station("WLIC", 1000), station("WCP", 1000, status = MwStation.PERMIT),
            station("WSIL", 1000, status = MwStation.SILENT), station("XEUNV", 1000, "MX").copy(status = MwStation.UNVERIFIED))
        val db = MwStationDb(rows)
        assertEquals(listOf("WLIC"), db.onChannel(1000).map { it.call })
        assertEquals(4, db.onChannel(1000, MwVisibility(permits = true, silent = true, unverified = true)).size)
        assertEquals(emptyList(), db.onChannel(1000, MwVisibility(hidden = listOf(MwFavorite.of(rows[0])))))
    }

    @Test fun `infobox parameters and cleaned formats`() {
        val text = """Intro {{Short description|x}}
            {{Infobox radio station
            | name = WBZ
            | frequency = 1030 [[kilohertz|kHz]] <ref>FCC</ref>
            | format = {{ubl|[[News radio|News]]/[[Talk radio|talk]]|[[Sports radio]] (nights)}}<!-- note -->
            | logo = [[File:X.png|200px]]
            }} Body""".trimIndent()
        val params = WikiInfobox.params(text)
        assertEquals("News/talk / Sports radio (nights)", WikiInfobox.clean(params.getValue("format")))
        assertTrue(WikiInfobox.frequencyMatches(params, 1030))
        assertFalse(WikiInfobox.frequencyMatches(params, 103))
        assertFalse(WikiInfobox.frequencyMatches(mapOf("frequency" to "101.3 MHz"), 1013))
        assertEquals("Oldies / religious (mornings)", WikiInfobox.clean("[[Oldies]]<br />religious (mornings)&nbsp;"))
        assertEquals("", WikiInfobox.clean("[{Classics hits}] ]] | power = 1,000"))
        assertEquals(emptyMap(), WikiInfobox.params("{{Infobox television station | format = x }}"))
    }

    @Test fun `formats attach by facility and confirm otherwise unverified rows`() {
        val wbz = station("WBZ", 1030, id = 25444)
        val xela = station("XELA", 830, "MX", id = 7).copy(status = MwStation.UNVERIFIED)
        val text = StringBuilder().also {
            MwStationDb.writeFormats(listOf(wbz to ("News/talk" to "WBZ (AM)"), xela to ("Rock" to "XELA-AM")), mapOf("license" to "CC BY-SA 4.0"), it)
        }.toString()
        val db = MwStationDb(listOf(wbz, xela, station("WXYZ", 1030))).withFormats(StringReader(text))
        assertEquals("CC BY-SA 4.0", db.formatMetadata["license"])
        assertEquals("News/talk", db.station(25444, 1030)?.format)
        assertNull(db.stations.single { it.call == "WXYZ" }.format)
        val confirmed = db.station(7, 830)!!
        assertEquals(MwStation.NOTIFIED to MwStation.SOURCE_WIKIPEDIA, confirmed.status to confirmed.source)
        assertEquals(listOf("WBZ"), db.search("news").map { it.call })
        val silent = station("XEAA", 1340, "MX", id = 8).copy(status = MwStation.UNVERIFIED)
        val silentText = StringBuilder().also { MwStationDb.writeFormats(listOf(silent to ("Silent" to "XEAA")), emptyMap(), it) }.toString()
        assertEquals(MwStation.UNVERIFIED, MwStationDb(listOf(silent)).withFormats(StringReader(silentText)).stations.single().status)
        // A current CRT list is authoritative; Wikipedia does not override it.
        val crt = MwStationDb(listOf(xela), mapOf("mx-crt" to "CRT")).withFormats(StringReader(text)).stations.single()
        assertEquals(MwStation.UNVERIFIED to "Rock", crt.status to crt.format)
    }
}
