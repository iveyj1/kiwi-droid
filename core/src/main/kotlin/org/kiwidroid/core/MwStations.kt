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
    /** One of [LICENSED], [PERMIT], [SILENT], [NOTIFIED] or [UNVERIFIED]. */
    val status: String,
    /** Older FCC facility IDs consolidated into this station. */
    val mergedFacilityIds: List<Int> = emptyList(),
    /** Official list that confirmed this row: [SOURCE_LMS], [SOURCE_ISED], [SOURCE_CRT], [SOURCE_IFT_2014], or [SOURCE_FCC] alone. */
    val source: String = SOURCE_FCC,
    /** Programming format from a separate, separately licensed table; not part of the station table. */
    val format: String? = null,
) {
    val isPermit: Boolean get() = status == PERMIT
    val isSilent: Boolean get() = status == SILENT
    val isUnverified: Boolean get() = status == UNVERIFIED
    val facilityIds: List<Int> get() = listOf(facilityId) + mergedFacilityIds
    val maxKw: Double get() = listOfNotNull(dayKw, nightKw, criticalKw).maxOrNull() ?: 0.0
    val place: String get() = listOf(city, state.takeUnless { it.isEmpty() || it == "-" }, country)
        .filterNot { it.isNullOrEmpty() }.joinToString(", ")
    val label: String get() = call.ifEmpty { "(no call)" }

    companion object {
        /** Licensed/operational according to the national regulator. */
        const val LICENSED = "LIC"
        /** Construction permit or authorization not yet built. */
        const val PERMIT = "CP"
        /** Licensed but reported silent (US LMS "licensed and silent"). */
        const val SILENT = "SILENT"
        /** Treaty notification only; no national list available to confirm it. */
        const val NOTIFIED = "NOTIFIED"
        /** Treaty notification that the national list does not confirm; likely deleted or moved to FM. */
        const val UNVERIFIED = "UNVERIFIED"

        const val SOURCE_FCC = "FCC"
        const val SOURCE_LMS = "LMS"
        const val SOURCE_ISED = "ISED"
        const val SOURCE_CRT = "CRT"
        const val SOURCE_IFT_2014 = "IFT2014"
        /** Not on an official list, but a current Wikipedia article lists it on this AM channel. */
        const val SOURCE_WIKIPEDIA = "Wikipedia"
    }
}

/** Which questionable rows to show; [hidden] are the user's own "bad entry" reports. */
data class MwVisibility(
    val permits: Boolean = false,
    val silent: Boolean = false,
    val unverified: Boolean = false,
    val hidden: List<MwFavorite> = emptyList(),
) {
    fun shows(station: MwStation): Boolean = (permits || !station.isPermit) && (silent || !station.isSilent) &&
        (unverified || !station.isUnverified) && hidden.none { it.matches(station) }
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

/** Saved by facility ID and channel so it survives call-sign changes and later facility consolidation. */
data class MwFavorite(val facilityId: Int, val khz: Int, val call: String) {
    init { require(khz in MW_FIRST_CHANNEL_KHZ..MW_LAST_CHANNEL_KHZ) { "Favorite must be an MW channel" } }
    fun matches(station: MwStation) = station.khz == khz && facilityId in station.facilityIds

    companion object { fun of(station: MwStation) = MwFavorite(station.facilityId, station.khz, station.call) }
}

class MwStationDb(val stations: List<MwStation>, val metadata: Map<String, String> = emptyMap(),
                  val formatMetadata: Map<String, String> = emptyMap()) {
    private val byKhz = stations.groupBy { it.khz }
        .mapValues { (_, list) -> list.sortedWith(POWER_ORDER) }

    private val byFacility = stations.flatMap { s -> s.facilityIds.map { (it to s.khz) to s } }.toMap()

    /** Resolves current or merged facility IDs. */
    fun station(facilityId: Int, khz: Int): MwStation? = byFacility[facilityId to khz]

    private val byFrequency = stations.sortedWith(compareBy<MwStation> { it.khz }.then(POWER_ORDER))

    /** Without a receiver location, stations on a channel are ordered by licensed power. */
    fun onChannel(khz: Int, visibility: MwVisibility = MwVisibility()): List<MwStation> =
        byKhz[khz].orEmpty().filter(visibility::shows)

    /** Matches a frequency prefix in kHz, a call-sign prefix, or a city/state/country/format substring. */
    fun search(query: String, visibility: MwVisibility = MwVisibility()): List<MwStation> {
        val q = query.trim().uppercase(Locale.ROOT)
        val sorted = byFrequency.filter(visibility::shows)
        if (q.isEmpty()) return sorted
        return sorted.filter { station ->
            if (q.all { it.isDigit() }) station.khz.toString().startsWith(q)
            else station.call.startsWith(q) || station.place.uppercase(Locale.ROOT).contains(q) ||
                station.format?.uppercase(Locale.ROOT)?.contains(q) == true
        }
    }

    /**
     * Attaches programming formats keyed by facility ID and channel. A format row means a Wikipedia article
     * lists the station on this AM channel. When the only Mexican list is the 2014 IFT extract, that confirms
     * an otherwise [MwStation.UNVERIFIED] treaty record ([MwStation.NOTIFIED], source [MwStation.SOURCE_WIKIPEDIA]).
     * A current CRT list is authoritative: rows it omits are duplicates or stations moved to FM, and articles lag.
     */
    fun withFormats(reader: Reader): MwStationDb {
        val table = readFormats(reader)
        val promote = "mx-crt" !in metadata
        return MwStationDb(stations.map { s ->
            val format = s.facilityIds.firstNotNullOfOrNull { table.formats[it to s.khz] }
            if (format != null && promote && s.isUnverified && !INACTIVE_FORMAT.containsMatchIn(format))
                s.copy(format = format, status = MwStation.NOTIFIED, source = MwStation.SOURCE_WIKIPEDIA)
            else s.copy(format = format)
        }, metadata, table.metadata)
    }

    companion object {
        /** Without a receiver location: peak power, then night power (full-time over daytimers), then class A first. */
        val POWER_ORDER: Comparator<MwStation> = compareByDescending<MwStation> { it.maxKw }
            .thenByDescending { it.nightKw ?: 0.0 }.thenBy { it.stationClass }.thenBy { it.call }

        const val HEADER = "facility\tcall\tkhz\tcountry\tstate\tcity\tclass\tday_kw\tnight_kw\tcritical_kw\tda\tlat\tlon\tstatus\tmerged\tsource"
        /** Previous layout without `source`, read only so refreshes can report differences against it. */
        private val HEADER_V1 = HEADER.removeSuffix("\tsource")
        /** Infobox formats that describe a station off the air rather than its programming. */
        private val INACTIVE_FORMAT = Regex("""\b(silent|defunct|dark|off[- ]the[- ]air|off[- ]air)\b""", RegexOption.IGNORE_CASE)
        const val FORMAT_HEADER = "facility\tkhz\tformat\tarticle"

        class FormatTable(val formats: Map<Pair<Int, Int>, String>, val metadata: Map<String, String>)

        fun readFormats(reader: Reader): FormatTable {
            val metadata = linkedMapOf<String, String>()
            val formats = hashMapOf<Pair<Int, Int>, String>()
            var header = false
            reader.buffered().lineSequence().forEachIndexed { index, line ->
                when {
                    line.startsWith("#") -> line.removePrefix("#").split(":", limit = 2)
                        .takeIf { it.size == 2 }?.let { (k, v) -> metadata[k.trim()] = v.trim() }
                    line.isBlank() -> Unit
                    !header -> { require(line == FORMAT_HEADER) { "Unexpected format table header" }; header = true }
                    else -> {
                        val f = line.split('\t')
                        require(f.size == 4) { "Format row ${index + 1} has ${f.size} fields" }
                        formats[f[0].toInt() to f[1].toInt()] = f[2]
                    }
                }
            }
            require(header) { "Missing format table header" }
            return FormatTable(formats, metadata)
        }

        fun writeFormats(rows: List<Pair<MwStation, Pair<String, String>>>, metadata: Map<String, String>, out: Appendable) {
            metadata.forEach { (k, v) -> out.append("# ").append(k).append(": ").append(v).append('\n') }
            out.append(FORMAT_HEADER).append('\n')
            rows.forEach { (s, value) ->
                val fields = listOf(s.facilityId.toString(), s.khz.toString(), value.first, value.second)
                require(fields.none { '\t' in it || '\n' in it }) { "Format field contains a separator" }
                out.append(fields.joinToString("\t")).append('\n')
            }
        }

        fun read(reader: Reader): MwStationDb {
            val metadata = linkedMapOf<String, String>()
            val stations = mutableListOf<MwStation>()
            var header = false
            reader.buffered().lineSequence().forEachIndexed { index, line ->
                when {
                    line.startsWith("#") -> line.removePrefix("#").split(":", limit = 2)
                        .takeIf { it.size == 2 }?.let { (k, v) -> metadata[k.trim()] = v.trim() }
                    line.isBlank() -> Unit
                    !header -> { require(line == HEADER || line == HEADER_V1) { "Unexpected station table header" }; header = true }
                    else -> stations += parseRow(line, index + 1)
                }
            }
            require(header) { "Missing station table header" }
            return MwStationDb(stations, metadata)
        }

        private fun parseRow(line: String, number: Int): MwStation {
            val f = line.split('\t')
            require(f.size == 16 || f.size == 15) { "Station row $number has ${f.size} fields" }
            fun kw(value: String) = value.takeIf { it.isNotEmpty() }?.toDouble()
            return MwStation(f[0].toInt(), f[1], f[2].toInt(), f[3], f[4], f[5], f[6], kw(f[7]), kw(f[8]), kw(f[9]),
                'D' in f[10], 'N' in f[10], f[11].toDouble(), f[12].toDouble(), f[13],
                f[14].split(',').filter { it.isNotEmpty() }.map { it.toInt() }, f.getOrElse(15) { MwStation.SOURCE_FCC })
        }

        fun write(stations: List<MwStation>, metadata: Map<String, String>, out: Appendable) {
            metadata.forEach { (k, v) -> out.append("# ").append(k).append(": ").append(v).append('\n') }
            out.append(HEADER).append('\n')
            stations.forEach { out.append(row(it).joinToString("\t")).append('\n') }
        }

        /** Serialized fields, at stored precision; also used to compare refreshes without rounding noise. */
        fun row(s: MwStation): List<String> {
            fun kw(value: Double?) = value?.let { String.format(Locale.ROOT, "%.3f", it).trimEnd('0').trimEnd('.') } ?: ""
            val da = (if (s.directionalDay) "D" else "") + (if (s.directionalNight) "N" else "")
            return listOf(s.facilityId.toString(), s.call, s.khz.toString(), s.country, s.state, s.city,
                s.stationClass, kw(s.dayKw), kw(s.nightKw), kw(s.criticalKw), da,
                String.format(Locale.ROOT, "%.4f", s.latitude), String.format(Locale.ROOT, "%.4f", s.longitude), s.status,
                s.mergedFacilityIds.joinToString(","), s.source)
                .also { fields -> require(fields.none { '\t' in it || '\n' in it }) { "Station field contains a separator" } }
        }
    }
}
