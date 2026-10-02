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

/** Build-time entry point: `./gradlew :core:importMwStations --args="<amq.txt> <out.tsv>"`. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: <fcc-amq-list4.txt> <mw_stations.tsv>" }
    val input = File(args[0])
    val bytes = input.readBytes()
    val stations = FccAmImport.convert(String(bytes, Charsets.ISO_8859_1).lineSequence())
    check(stations.size > 1000) { "Only ${stations.size} stations parsed; refusing to replace the database" }
    val metadata = linkedMapOf(
        "KiwiDroid MW station database" to "v1",
        "source" to FccAmImport.SOURCE_URL,
        "fetched" to Instant.ofEpochMilli(input.lastModified()).truncatedTo(ChronoUnit.SECONDS).toString(),
        "sha256" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "countries" to FccAmImport.NORTH_AMERICA.sorted().joinToString(" "),
        "stations" to stations.size.toString(),
        "merged facilities" to stations.sumOf { it.mergedFacilityIds.size }.toString(),
        "note" to "FCC data (public domain); non-US entries are treaty notifications and may be out of date",
    )
    File(args[1]).bufferedWriter().use { MwStationDb.write(stations, metadata, it) }
    println("Wrote ${stations.size} stations to ${args[1]}")
}
