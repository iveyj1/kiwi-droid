package org.kiwidroid.core

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Converts the FCC AM Query pipe-delimited output (`amq?...&list=4`) into the compact station table.
 * The FCC also carries treaty notifications for other countries; those arrive with CP status and are
 * relabelled [MwStation.NOTIFIED].
 */
object FccAmImport {
    const val SOURCE_URL = "https://transition.fcc.gov/fcc-bin/amq?freq=530&fre2=1700&list=4&size=9"
    val NORTH_AMERICA = setOf("US", "CA", "MX", "CU")
    private val STATUS_RANK = mapOf("LIC" to 0, "CP" to 1)

    internal data class Record(
        val call: String, val khz: Int, val hours: String, val stationClass: String, val status: String,
        val city: String, val state: String, val country: String, val kw: Double?, val directional: Boolean,
        val facilityId: Int, val latitude: Double, val longitude: Double, val applicationId: Long,
    )

    internal fun parseLine(line: String): Record? {
        val f = line.split('|').map { it.trim() }
        if (f.size < 33 || f[3] != "AM") return null
        fun dms(deg: String, min: String, sec: String) = deg.toDouble() + min.toDouble() / 60 + sec.toDouble() / 3600
        return Record(
            call = f[1].takeUnless { it == "-" } ?: "",
            khz = f[2].removeSuffix("kHz").trim().toInt(),
            hours = f[5],
            stationClass = f[7],
            status = f[9],
            city = titleCase(f[10]),
            state = f[11],
            country = f[12],
            kw = f[14].removeSuffix("kW").trim().toDoubleOrNull(),
            directional = f[15] == "Directional",
            facilityId = f[18].toInt(),
            latitude = dms(f[20], f[21], f[22]) * (if (f[19] == "S") -1 else 1),
            longitude = dms(f[24], f[25], f[26]) * (if (f[23] == "W") -1 else 1),
            applicationId = f[31].toLongOrNull() ?: 0,
        )
    }

    /**
     * Consolidates FCC rows into one station per channel:
     * 1. per facility/channel, keep the best status (licence over permit) and the newest record per hours category,
     *    merging day/night/unlimited/critical-hours records;
     * 2. merge facilities sharing a real call sign and channel within [MERGE_RADIUS_KM] (repeated treaty
     *    notifications, or a site split into day-only and night-only facilities), newest facility first.
     * US calls prefixed with "D" are deleted facilities and are dropped.
     */
    fun convert(lines: Sequence<String>, countries: Set<String> = NORTH_AMERICA): List<MwStation> =
        lines.mapNotNull(::parseLine)
            .filter { it.country in countries && it.status in STATUS_RANK && it.khz in MW_FIRST_CHANNEL_KHZ..MW_LAST_CHANNEL_KHZ }
            .filterNot { it.country == "US" && it.call.startsWith("D") }
            .groupBy { it.facilityId to it.khz }
            .values.map(::facilityStation)
            .groupBy { if (isPlaceholderCall(it.call)) it.facilityId.toString() else "${it.country} ${it.call} ${it.khz}" }
            .values.flatMap(::mergeNearby)
            .sortedWith(compareBy<MwStation> { it.khz }.thenBy { it.country }.thenBy { it.call }.thenBy { it.facilityId })

    const val MERGE_RADIUS_KM = 10.0
    /** Unassigned or generic calls in the FCC treaty data identify unrelated stations. */
    fun isPlaceholderCall(call: String) = call.matches(Regex("|NEW|XE|XENVA\\d*|\\d+"))

    private fun facilityStation(group: List<Record>): MwStation {
        val best = group.minOf { STATUS_RANK.getValue(it.status) }
        val byHours = group.filter { STATUS_RANK.getValue(it.status) == best }
            .groupBy { it.hours }.mapValues { (_, list) -> list.maxBy { it.applicationId } }
        val day = byHours["DAY"] ?: byHours["UNL"]
        val night = byHours["NIG"] ?: byHours["UNL"]
        val newest = byHours.values.maxBy { it.applicationId }
        val site = day ?: night ?: newest
        // The FCC lists treaty notifications as CP; only US permits are construction permits.
        val status = if (newest.country != "US") MwStation.NOTIFIED else newest.status
        return MwStation(newest.facilityId, newest.call, newest.khz, newest.country, newest.state, newest.city,
            newest.stationClass, day?.kw, night?.kw, byHours["CRI"]?.kw,
            day?.directional == true, night?.directional == true, site.latitude, site.longitude, status)
    }

    /** Higher facility IDs are newer notifications; each period's power comes from the newest facility listing it. */
    private fun mergeNearby(sameCall: List<MwStation>): List<MwStation> {
        val clusters = mutableListOf<MutableList<MwStation>>()
        sameCall.sortedByDescending { it.facilityId }.forEach { station ->
            clusters.firstOrNull { cluster -> cluster.any {
                distanceKm(it.latitude, it.longitude, station.latitude, station.longitude) <= MERGE_RADIUS_KM
            } }?.add(station) ?: clusters.add(mutableListOf(station))
        }
        return clusters.map { cluster ->
            val primary = cluster.first()
            val day = cluster.firstOrNull { it.dayKw != null }
            val night = cluster.firstOrNull { it.nightKw != null }
            primary.copy(dayKw = day?.dayKw, nightKw = night?.nightKw,
                criticalKw = cluster.firstNotNullOfOrNull { it.criticalKw },
                directionalDay = day?.directionalDay == true, directionalNight = night?.directionalNight == true,
                mergedFacilityIds = cluster.drop(1).map { it.facilityId }.sorted())
        }
    }

    private fun titleCase(text: String) = text.lowercase(Locale.ROOT).split(' ').joinToString(" ") { word ->
        word.split('-').joinToString("-") { part -> part.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }
}

/**
 * Build-time entry point; see tools/update-mw-db.sh. Options (all but --fcc and --out optional):
 * `--fcc amq.txt --lms facility.dat --ised amstatio.dbf --mx-crt crt.csv --mx-ift ift2014.tsv --out mw_stations.tsv --report report.txt`
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    fun file(name: String) = options[name]?.let(::File)?.also { require(it.isFile) { "--$name ${it.path} not found" } }
    val fccFile = requireNotNull(file("fcc")) { "--fcc is required" }
    val out = File(requireNotNull(options["out"]) { "--out is required" })
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun fetched(f: File) = Instant.ofEpochMilli(f.lastModified()).truncatedTo(ChronoUnit.SECONDS).toString()

    val fccBytes = fccFile.readBytes()
    val fcc = FccAmImport.convert(String(fccBytes, Charsets.ISO_8859_1).lineSequence())
    check(fcc.size > 1000) { "Only ${fcc.size} FCC stations parsed; refusing to replace the database" }
    val inputs = MwVetting.Inputs(
        lms = file("lms")?.let { LmsAm.parse(it.readText(Charsets.ISO_8859_1)) }?.also { check(it.size > 1000) { "LMS facility table looks truncated" } },
        isedRows = file("ised")?.let { Dbf.read(it.readBytes()) }?.also { check(it.size > 1000) { "ISED table looks truncated" } },
        mexicoCrt = file("mx-crt")?.let { MexicoAm.parseCrt(it.readText()) }?.also { check(it.size > 50) { "CRT list has only ${it.size} AM rows" } },
        mexicoIft2014 = file("mx-ift")?.let { f -> f.reader().use(MexicoAm::parseIft2014) },
    )
    val report = MwImportReport()
    val stations = MwVetting.apply(fcc, inputs, report)

    val metadata = linkedMapOf(
        "KiwiDroid MW station database" to "v2",
        "source" to FccAmImport.SOURCE_URL,
        "fetched" to fetched(fccFile),
        "sha256" to sha256(fccBytes),
    )
    file("lms")?.let { metadata["lms"] = "FCC LMS facility table, fetched ${fetched(it)}, sha256 ${sha256(it.readBytes())}" }
    file("ised")?.let { metadata["ised"] = "ISED broadcasting extract amstatio.dbf, fetched ${fetched(it)}, sha256 ${sha256(it.readBytes())}" }
    file("mx-crt")?.let { metadata["mx-crt"] = "CRT Infraestructura de Estaciones AM/FM (CC BY 4.0), ${it.name}, sha256 ${sha256(it.readBytes())}" }
    file("mx-ift")?.let { metadata["mx-ift"] = "IFT Infraestructura de Estaciones de Radio AM, 26 Nov 2014 (call/kHz extract)" }
    metadata["countries"] = FccAmImport.NORTH_AMERICA.sorted().joinToString(" ")
    metadata["stations"] = stations.size.toString()
    stations.groupingBy { "${it.country} ${it.status}" }.eachCount().toSortedMap()
        .let { counts -> metadata["by status"] = counts.entries.joinToString(", ") { "${it.key} ${it.value}" } }
    metadata["merged facilities"] = stations.sumOf { it.mergedFacilityIds.size }.toString()
    metadata["note"] = "FCC/ISED data; Mexican rows are FCC treaty records checked against the national list; " +
        "UNVERIFIED rows were not confirmed and are hidden by default"

    val previous = out.takeIf { it.isFile }?.let { runCatching { it.reader().use(MwStationDb::read).stations }.getOrNull() }
    previous?.let { old ->
        val before = old.associateBy { it.facilityId to it.khz }
        val after = stations.associateBy { it.facilityId to it.khz }
        (after.keys - before.keys).forEach { k -> after.getValue(k).let { report.note("Diff: added", "${it.label} ${it.khz} ${it.place} ${it.status}") } }
        (before.keys - after.keys).forEach { k -> before.getValue(k).let { report.note("Diff: removed", "${it.label} ${it.khz} ${it.place} ${it.status}") } }
        (after.keys intersect before.keys).forEach { k ->
            val a = after.getValue(k); val b = before.getValue(k)
            if (a.status != b.status || a.call != b.call) report.note("Diff: status or call changed", "${b.label} ${b.status} → ${a.label} ${a.status} ${a.khz}")
            else if (MwStationDb.row(a).dropLast(1) != MwStationDb.row(b).dropLast(1))
                report.note("Diff: technical details changed", "${a.label} ${a.khz}")
        }
    }

    out.bufferedWriter().use { MwStationDb.write(stations, metadata, it) }
    val rendered = report.render()
    options["report"]?.let { File(it).apply { parentFile?.mkdirs() }.writeText(rendered) }
    println(report.render(maxExamples = 0))
    println("Wrote ${stations.size} stations to ${out.path}" + (options["report"]?.let { "; full report in $it" } ?: ""))
}
