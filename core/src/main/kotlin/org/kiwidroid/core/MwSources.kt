package org.kiwidroid.core

import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale

/** FCC LMS `facility.dat` (pipe-delimited, records end with `|^|`): current status of US AM facilities. */
object LmsAm {
    data class Facility(val facilityId: Int, val call: String, val khz: Int?, val status: String)

    const val LICENSED = "LICEN"
    const val SILENT = "LICSL"
    const val REDUCED_POWER = "LICRP"
    /** Licence cancelled, facility void, or permit cancelled: no longer a station. */
    val GONE = setOf("LICAN", "FVOID", "PRCAN")

    fun parse(text: String): Map<Int, Facility> {
        val records = text.split("^|\n", "^|\r\n").filter { it.isNotBlank() }
        val header = records.first().trim().split('|').filter { it.isNotEmpty() }
        fun column(name: String) = header.indexOf(name).also { require(it >= 0) { "LMS facility table lacks $name" } }
        val service = column("service_code")
        val id = column("facility_id")
        val call = column("callsign")
        val frequency = column("frequency")
        val status = column("facility_status")
        return records.drop(1).map { it.trim('\n', '\r').split('|') }
            .filter { it.size > maxOf(service, id, call, frequency, status) && it[service] == "AM" }
            .mapNotNull { f ->
                val facility = f[id].toIntOrNull() ?: return@mapNotNull null
                facility to Facility(facility, f[call], f[frequency].toDoubleOrNull()?.toInt(), f[status])
            }.toMap()
    }
}

/** Minimal dBASE III reader for the ISED broadcasting extract. */
object Dbf {
    fun read(bytes: ByteArray): List<Map<String, String>> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getInt(4)
        val headerLength = buffer.getShort(8).toInt() and 0xffff
        val recordLength = buffer.getShort(10).toInt() and 0xffff
        val fields = mutableListOf<Pair<String, Int>>()
        var offset = 32
        while (bytes[offset] != 0x0D.toByte()) {
            val name = String(bytes, offset, 11, Charsets.ISO_8859_1).substringBefore('\u0000')
            fields += name to (bytes[offset + 16].toInt() and 0xff)
            offset += 32
        }
        return (0 until count).mapNotNull { i ->
            val start = headerLength + i * recordLength
            if (start + recordLength > bytes.size || bytes[start] == '*'.code.toByte()) return@mapNotNull null
            var position = start + 1
            fields.associate { (name, length) ->
                val value = String(bytes, position, length, Charsets.ISO_8859_1).trim()
                position += length
                name to value
            }
        }
    }
}

/** ISED `amstatio.dbf`: Canadian AM stations with day/night operational status. */
object IsedAm {
    val PROVINCES = setOf("AB", "BC", "MB", "NB", "NL", "NF", "NS", "NT", "NU", "ON", "PE", "QC", "SK", "YT")
    /** Operational or temporary operation. */
    private val OPERATING = setOf("OP", "TO")
    /** Approved or authorized but not (yet) operating. */
    private val AUTHORIZED = setOf("AU", "AP", "CN")
    /** Synthetic IDs for stations with no FCC record: 1e9 + ISED file number. */
    const val SYNTHETIC_ID_BASE = 1_000_000_000

    private fun dms(value: String, west: Boolean): Double? {
        val n = value.toLongOrNull()?.takeIf { it != 0L } ?: return null
        val abs = Math.abs(n)
        val degrees = abs / 10000 + (abs / 100 % 100) / 60.0 + (abs % 100) / 3600.0
        return if (west) -degrees else degrees * Math.signum(n.toDouble())
    }

    /**
     * Merges per-call/channel records (ISED may split day and night operation into separate rows).
     * [fccIds] maps call+kHz to FCC facility IDs so favorites saved against FCC rows keep working.
     */
    fun convert(rows: List<Map<String, String>>, fccIds: Map<Pair<String, Int>, Int>): List<MwStation> {
        val used = hashSetOf<Pair<Int, Int>>()
        return rows.filter { it["PROVINCE"] in PROVINCES && !it["CALL_SIGN"].orEmpty().endsWith("-HD") }
            .groupBy { it.getValue("CALL_SIGN") to it.getValue("FREQUENCY").toDouble().toInt() }
            .mapNotNull { (key, records) ->
                val (call, khz) = key
                if (khz !in MW_FIRST_CHANNEL_KHZ..MW_LAST_CHANNEL_KHZ) return@mapNotNull null
                fun power(status: String, field: String, accept: Set<String>) = records
                    .filter { it[status] in accept }.mapNotNull { it[field]?.toDoubleOrNull()?.takeIf { w -> w > 0 } }
                    .maxOrNull()?.div(1000)
                val operating = records.any { it["STATUS1"] in OPERATING || it["STATUS2"] in OPERATING }
                val authorized = records.any { it["STATUS1"] in AUTHORIZED || it["STATUS2"] in AUTHORIZED }
                if (!operating && !authorized) return@mapNotNull null
                val accept = if (operating) OPERATING else AUTHORIZED
                val day = power("STATUS1", "POWERDAY", accept)
                val night = power("STATUS2", "POWERNIGHT", accept)
                val critical = records.mapNotNull { it["POWERCRIT"]?.toDoubleOrNull()?.takeIf { w -> w > 0 } }.maxOrNull()?.div(1000)
                // Night site in LATITUDE/LONGITUDE; day site in LATITUDE2/LONGITUDE2 only when different.
                val site = records.firstNotNullOfOrNull { r ->
                    dms(r.getValue("LATITUDE2"), false)?.let { lat -> dms(r.getValue("LONGITUDE2"), true)?.let { lat to it } }
                } ?: records.firstNotNullOfOrNull { r ->
                    dms(r.getValue("LATITUDE"), false)?.let { lat -> dms(r.getValue("LONGITUDE"), true)?.let { lat to it } }
                } ?: return@mapNotNull null
                val first = records.first()
                var id = fccIds[call to khz] ?: (SYNTHETIC_ID_BASE + (first["DOC_FILE"]?.toIntOrNull() ?: 0))
                while (!used.add(id to khz)) id++
                MwStation(id, call, khz, "CA", first.getValue("PROVINCE").replace("NF", "NL"), first.getValue("CITY"),
                    first.getValue("CLASS"), day, night, critical, false, false, site.first, site.second,
                    if (operating) MwStation.LICENSED else MwStation.PERMIT, source = MwStation.SOURCE_ISED)
            }
    }
}

/** Mexican official AM lists, used to confirm (and correct) FCC treaty records. */
object MexicoAm {
    /** [city] is empty when the list has none (the 2014 IFT extract). */
    data class Station(val call: String, val khz: Int, val city: String = "")

    fun normalizeCall(call: String) = call.trim().uppercase(Locale.ROOT).removeSuffix("-AM").replace(" ", "")

    /** Accent-, case- and punctuation-insensitive city key; "Cd." abbreviates "Ciudad". */
    fun cityKey(city: String) = fold(city).uppercase(Locale.ROOT).replace(Regex("^CD\\.?\\s*"), "CIUDAD ")
        .filter { it.isLetter() }

    fun sameCity(a: String, b: String): Boolean {
        val x = cityKey(a)
        val y = cityKey(b)
        if (x.length < 4 || y.length < 4 || x == "SINDATO" || y == "SINDATO") return false
        return x == y || (minOf(x.length, y.length) >= 6 && (x.startsWith(y) || y.startsWith(x)))
    }

    /** Committed extract of the IFT's 2014 AM infrastructure list (`call<TAB>khz`). */
    fun parseIft2014(reader: Reader): List<Station> = reader.buffered().lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("call\t") }
        .map { it.split('\t') }.map { Station(normalizeCall(it[0]), it[1].trim().toInt()) }.toList()

    /**
     * CRT "Infraestructura de Estaciones AM/FM" CSV. Columns are located by header name (distintivo, banda,
     * frecuencia, poblacion), ignoring case and accents, so minor monthly layout changes do not break the import.
     */
    fun parseCrt(text: String): List<Station> {
        val rows = parseCsv(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "Empty CRT CSV" }
        val header = rows.first().map(::fold).map { it.lowercase(Locale.ROOT) }
        fun column(word: String) = header.indexOfFirst { word in it }.also { require(it >= 0) { "CRT CSV lacks a '$word' column: $header" } }
        val call = column("distintivo")
        val band = column("banda")
        val frequency = column("frecuencia")
        val city = header.indexOfFirst { "poblacion" in it || "localidad" in it }
        return rows.drop(1).filter { it.size > maxOf(call, band, frequency) && fold(it[band]).lowercase(Locale.ROOT) == "am" }
            .mapNotNull { r ->
                val value = r[frequency].replace(',', '.').filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return@mapNotNull null
                Station(normalizeCall(r[call]), (if (value < 2) value * 1000 else value).toInt(), r.getOrNull(city).orEmpty().trim())
            }.distinct()
    }

    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").trim()

    internal fun parseCsv(text: String): List<List<String>> {
        val delimiter = if (text.lineSequence().first().count { it == ';' } > text.lineSequence().first().count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { row += field.toString(); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        row += field.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }
}

/** Human-readable record of every vetting decision, for review before committing a refreshed table. */
class MwImportReport {
    private val counts = linkedMapOf<String, Int>()
    private val examples = linkedMapOf<String, MutableList<String>>()
    fun note(category: String, example: String? = null) {
        counts[category] = (counts[category] ?: 0) + 1
        if (example != null) examples.getOrPut(category) { mutableListOf() }.add(example)
    }
    fun summary(): Map<String, Int> = counts
    fun render(maxExamples: Int = Int.MAX_VALUE): String = buildString {
        counts.forEach { (category, n) ->
            append(String.format(Locale.ROOT, "%6d  %s%n", n, category))
            examples[category]?.take(maxExamples)?.forEach { append("          ").append(it).append('\n') }
        }
    }
}

/** Applies national lists to the FCC-derived table. Each input is optional; absent lists leave rows as FCC reports them. */
object MwVetting {
    data class Inputs(
        val lms: Map<Int, LmsAm.Facility>? = null,
        val isedRows: List<Map<String, String>>? = null,
        val mexicoCrt: List<MexicoAm.Station>? = null,
        val mexicoIft2014: List<MexicoAm.Station>? = null,
    )

    fun apply(fcc: List<MwStation>, inputs: Inputs, report: MwImportReport = MwImportReport()): List<MwStation> {
        val us = fcc.filter { it.country == "US" }.mapNotNull { vetUs(it, inputs.lms, report) }
        val canada = inputs.isedRows?.let { rows ->
            val fccCanada = fcc.filter { it.country == "CA" }
            val ised = IsedAm.convert(rows, fccCanada.associate { (it.call to it.khz) to it.facilityId })
            val isedKeys = ised.map { it.call to it.khz }.toSet()
            fccCanada.filter { (it.call to it.khz) !in isedKeys }
                .forEach { report.note("CA: FCC treaty record dropped (not operational/authorized in ISED)", "${it.label} ${it.khz} ${it.place}") }
            ised.forEach { report.note("CA: from ISED as ${it.status}", if (fccCanada.none { f -> f.call == it.call && f.khz == it.khz }) "${it.label} ${it.khz} ${it.place} (not in FCC data)" else null) }
            ised
        } ?: fcc.filter { it.country == "CA" }.onEach { report.note("CA: FCC treaty record kept (no ISED input)") }
        val mexico = vetMexico(fcc.filter { it.country == "MX" }, inputs, report)
        val other = fcc.filter { it.country !in setOf("US", "CA", "MX") }.onEach { report.note("${it.country}: treaty record, no national list") }
        return (us + canada + mexico + other)
            .sortedWith(compareBy<MwStation> { it.khz }.thenBy { it.country }.thenBy { it.call }.thenBy { it.facilityId })
    }

    private fun vetUs(station: MwStation, lms: Map<Int, LmsAm.Facility>?, report: MwImportReport): MwStation? {
        if (lms == null) return station.also { report.note("US: no LMS input") }
        val facility = lms[station.facilityId]
            ?: return station.also { report.note("US: not in LMS; kept as FCC reports it", "${station.label} ${station.khz} ${station.place}") }
        if (facility.status in LmsAm.GONE) {
            report.note("US: dropped, LMS status ${facility.status}", "${station.label} ${station.khz} ${station.place} (LMS ${facility.call})")
            return null
        }
        val call = facility.call.takeIf { it.isNotEmpty() && !it.startsWith("D") && it != station.call }
        if (call != null) report.note("US: call sign updated from LMS", "${station.label} → $call ${station.khz}")
        if (facility.khz != null && facility.khz != station.khz && station.status == MwStation.LICENSED)
            report.note("US: LMS frequency differs; kept FCC AM Query channel, review", "${station.label} ${station.khz} vs LMS ${facility.khz}")
        val status = when {
            station.status == MwStation.PERMIT -> MwStation.PERMIT
            facility.status == LmsAm.SILENT -> MwStation.SILENT.also { report.note("US: licensed and silent", "${station.label} ${station.khz} ${station.place}") }
            facility.status == LmsAm.LICENSED || facility.status == LmsAm.REDUCED_POWER -> MwStation.LICENSED
                .also { if (facility.status == LmsAm.REDUCED_POWER) report.note("US: licensed, reduced power", "${station.label} ${station.khz}") }
            else -> MwStation.PERMIT.also { report.note("US: LMS status ${facility.status}; treated as permit", "${station.label} ${station.khz}") }
        }
        report.note("US: ${status.lowercase(Locale.ROOT)} per LMS")
        return station.copy(call = call ?: station.call, status = status, source = MwStation.SOURCE_LMS)
    }

    /**
     * Matches each official station to at most one FCC treaty row, in three passes:
     * exact call + kHz; same call (the FCC row is on an old frequency, so it moves to the official one);
     * same kHz and city (the FCC row has an old or placeholder call, so it takes the official one).
     * Coordinates and power stay from the FCC record. Unmatched FCC rows become UNVERIFIED.
     */
    private fun vetMexico(rows: List<MwStation>, inputs: Inputs, report: MwImportReport): List<MwStation> {
        val (list, source) = inputs.mexicoCrt?.let { it to MwStation.SOURCE_CRT }
            ?: inputs.mexicoIft2014?.let { it to MwStation.SOURCE_IFT_2014 }
            ?: return rows.onEach { report.note("MX: no national list; treaty record kept") }
        val result = rows.toMutableList()
        val matched = BooleanArray(rows.size)
        val pending = list.toMutableList()
        fun pass(name: String, candidates: (MexicoAm.Station) -> List<Int>) {
            pending.removeAll { official ->
                val index = candidates(official).filterNot { matched[it] }.maxByOrNull { rows[it].maxKw } ?: return@removeAll false
                matched[index] = true
                val before = rows[index]
                result[index] = before.copy(call = official.call, khz = official.khz, status = MwStation.LICENSED, source = source)
                report.note("MX: confirmed by $source ($name)",
                    if (before.call != official.call || before.khz != official.khz) "${before.label} ${before.khz} → ${official.call} ${official.khz} ${official.city}" else null)
                true
            }
        }
        val real = rows.indices.filterNot { FccAmImport.isPlaceholderCall(rows[it].call) }
        pass("call and kHz") { o -> real.filter { MexicoAm.normalizeCall(rows[it].call) == o.call && rows[it].khz == o.khz } }
        pass("call; FCC frequency outdated") { o -> real.filter { MexicoAm.normalizeCall(rows[it].call) == o.call } }
        pass("kHz and city; FCC call outdated or placeholder") { o -> rows.indices.filter { rows[it].khz == o.khz && MexicoAm.sameCity(rows[it].city, o.city) } }
        pending.sortedBy { it.khz }.forEach { o ->
            report.note("MX: on the national list but no matching FCC record (no coordinates; not added)", "${o.call} ${o.khz} ${o.city}")
        }
        rows.indices.filterNot { matched[it] }.forEach { i ->
            report.note("MX: not on the $source list; unverified (hidden by default)")
            result[i] = rows[i].copy(status = MwStation.UNVERIFIED)
        }
        return result
    }
}
