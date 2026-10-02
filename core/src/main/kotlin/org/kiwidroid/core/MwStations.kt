package org.kiwidroid.core

import java.io.Reader
import java.util.Locale

/** North American AM broadcast channels: 10 kHz raster including the 1610–1700 kHz expanded band. */
const val MW_FIRST_CHANNEL_KHZ = 530
const val MW_LAST_CHANNEL_KHZ = 1700
const val MW_CHANNEL_SPACING_KHZ = 10
/** Displayed frequencies where the MW station panel is relevant. */
val MW_BAND_HZ = 520_000L..1_710_000L

/** One station on one channel, merged from the FCC's separate day/night/critical-hours records. */
data class MwStation(
    val facilityId: Int,
    val call: String,
    val khz: Int,
    val country: String,
    val state: String,
    val city: String,
    val stationClass: String,
    val dayKw: Double?,
    val nightKw: Double?,
    val criticalKw: Double?,
    val directionalDay: Boolean,
    val directionalNight: Boolean,
    val latitude: Double,
    val longitude: Double,
    /** LIC (US licence), CP (US construction permit, not yet licensed) or NOTIFIED (non-US treaty notification). */
    val status: String,
    /** Older FCC facility IDs consolidated into this station. */
    val mergedFacilityIds: List<Int> = emptyList(),
) {
    val isPermit: Boolean get() = status == PERMIT
    val facilityIds: List<Int> get() = listOf(facilityId) + mergedFacilityIds
    val maxKw: Double get() = listOfNotNull(dayKw, nightKw, criticalKw).maxOrNull() ?: 0.0
    val place: String get() = listOf(city, state.takeUnless { it.isEmpty() || it == "-" }, country)
        .filterNot { it.isNullOrEmpty() }.joinToString(", ")
    val label: String get() = call.ifEmpty { "(no call)" }

    companion object {
        const val LICENSED = "LIC"
        const val PERMIT = "CP"
        const val NOTIFIED = "NOTIFIED"
    }
}

/** Great-circle distance on a spherical Earth (mean radius); adequate for station siting and ranking. */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 2 * 6371.0088 * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
}

/** Channels whose carrier lies inside the current RF passband (inclusive), nearest the passband center first. */
fun mwChannelsInPassband(tuning: Tuning): List<Int> {
    val lowHalfHz = tuning.radioFrequencyHalfHz + 2L * tuning.passband.lowHz
    val highHalfHz = tuning.radioFrequencyHalfHz + 2L * tuning.passband.highHz
    val centerHalfHz = (lowHalfHz + highHalfHz) / 2
    return (MW_FIRST_CHANNEL_KHZ..MW_LAST_CHANNEL_KHZ step MW_CHANNEL_SPACING_KHZ)
        .filter { it * 2000L in lowHalfHz..highHalfHz }
        .sortedBy { Math.abs(it * 2000L - centerHalfHz) }
}

/** Tunes the displayed frequency to a carrier; modes that cannot sensibly receive AM switch to AM. */
fun Tuning.toBroadcastChannel(khz: Int): Tuning =
    copy(frequencyHz = khz * 1000L, mode = if (mode in setOf(Mode.CW, Mode.CWN, Mode.IQ)) Mode.AM else mode)

class MwStationDb(val stations: List<MwStation>, val metadata: Map<String, String> = emptyMap()) {
    private val byKhz = stations.groupBy { it.khz }
        .mapValues { (_, list) -> list.sortedWith(POWER_ORDER) }

    private val byFrequency = stations.sortedWith(compareBy<MwStation> { it.khz }.then(POWER_ORDER))

    /** Phase 1 has no receiver location, so stations on a channel are ordered by licensed power. */
    fun onChannel(khz: Int, includePermits: Boolean = false): List<MwStation> =
        byKhz[khz].orEmpty().filter { includePermits || !it.isPermit }

    /** Matches a frequency prefix in kHz, a call-sign prefix, or a city/state/country substring. */
    fun search(query: String, includePermits: Boolean = false): List<MwStation> {
        val q = query.trim().uppercase(Locale.ROOT)
        val sorted = byFrequency.filter { includePermits || !it.isPermit }
        if (q.isEmpty()) return sorted
        return sorted.filter { station ->
            if (q.all { it.isDigit() }) station.khz.toString().startsWith(q)
            else station.call.startsWith(q) || station.place.uppercase(Locale.ROOT).contains(q)
        }
    }

    companion object {
        /** Without a receiver location: peak power, then night power (full-time over daytimers), then class A first. */
        val POWER_ORDER: Comparator<MwStation> = compareByDescending<MwStation> { it.maxKw }
            .thenByDescending { it.nightKw ?: 0.0 }.thenBy { it.stationClass }.thenBy { it.call }

        const val HEADER = "facility\tcall\tkhz\tcountry\tstate\tcity\tclass\tday_kw\tnight_kw\tcritical_kw\tda\tlat\tlon\tstatus\tmerged"

        fun read(reader: Reader): MwStationDb {
            val metadata = linkedMapOf<String, String>()
            val stations = mutableListOf<MwStation>()
            var header = false
            reader.buffered().lineSequence().forEachIndexed { index, line ->
                when {
                    line.startsWith("#") -> line.removePrefix("#").split(":", limit = 2)
                        .takeIf { it.size == 2 }?.let { (k, v) -> metadata[k.trim()] = v.trim() }
                    line.isBlank() -> Unit
                    !header -> { require(line == HEADER) { "Unexpected station table header" }; header = true }
                    else -> stations += parseRow(line, index + 1)
                }
            }
            require(header) { "Missing station table header" }
            return MwStationDb(stations, metadata)
        }

        private fun parseRow(line: String, number: Int): MwStation {
            val f = line.split('\t')
            require(f.size == 15) { "Station row $number has ${f.size} fields" }
            fun kw(value: String) = value.takeIf { it.isNotEmpty() }?.toDouble()
            return MwStation(f[0].toInt(), f[1], f[2].toInt(), f[3], f[4], f[5], f[6], kw(f[7]), kw(f[8]), kw(f[9]),
                'D' in f[10], 'N' in f[10], f[11].toDouble(), f[12].toDouble(), f[13],
                f[14].split(',').filter { it.isNotEmpty() }.map { it.toInt() })
        }

        fun write(stations: List<MwStation>, metadata: Map<String, String>, out: Appendable) {
            metadata.forEach { (k, v) -> out.append("# ").append(k).append(": ").append(v).append('\n') }
            out.append(HEADER).append('\n')
            fun kw(value: Double?) = value?.let { String.format(Locale.ROOT, "%.3f", it).trimEnd('0').trimEnd('.') } ?: ""
            stations.forEach { s ->
                val da = (if (s.directionalDay) "D" else "") + (if (s.directionalNight) "N" else "")
                val fields = listOf(s.facilityId.toString(), s.call, s.khz.toString(), s.country, s.state, s.city,
                    s.stationClass, kw(s.dayKw), kw(s.nightKw), kw(s.criticalKw), da,
                    String.format(Locale.ROOT, "%.4f", s.latitude), String.format(Locale.ROOT, "%.4f", s.longitude), s.status,
                    s.mergedFacilityIds.joinToString(","))
                require(fields.none { '\t' in it || '\n' in it }) { "Station field contains a separator" }
                out.append(fields.joinToString("\t")).append('\n')
            }
        }
    }
}
