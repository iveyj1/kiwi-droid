package org.kiwidroid.core

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** How sure the listener is of the identification. */
enum class LogConfidence(val label: String) { HEARD_ID("Heard ID"), TENTATIVE("Tentative"), UNIDENTIFIED("Unidentified") }

/** Station as it was when logged; later database refreshes do not rewrite history. */
data class LoggedStation(
    /** Null for a station typed in rather than chosen from the MW database. */
    val facilityId: Int?,
    val khz: Int?,
    val call: String,
    val place: String = "",
    val format: String? = null,
    val distanceKm: Double? = null,
    val bearingDeg: Double? = null,
) {
    init { require(call.isNotBlank() && call.length <= 80) { "Station must be 1–80 characters" } }

    fun matches(station: MwStation) = facilityId != null && khz == station.khz && facilityId in station.facilityIds

    companion object {
        fun of(station: MwStation, ranking: MwRanking?) = LoggedStation(station.facilityId, station.khz, station.label,
            station.place, station.format, ranking?.distanceKm, ranking?.bearingDeg)
    }
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val utc: Instant,
    val receiverName: String,
    val receiverUrl: String,
    val receiverLocation: GeoPoint?,
    val frequencyHz: Long,
    val mode: Mode,
    val passband: Passband,
    /** Latest non-stale reading, and the extremes of the preceding window; null when not playing. */
    val rssiDbm: Double?,
    val rssiMinDbm: Double?,
    val rssiMaxDbm: Double?,
    val station: LoggedStation?,
    val confidence: LogConfidence,
    val notes: String = "",
) {
    init {
        require(notes.length <= MAX_NOTES) { "Notes must be at most $MAX_NOTES characters" }
        require(frequencyHz in 0..MAX_FREQUENCY_HZ) { "Frequency must be 0–30000 kHz" }
    }

    companion object { const val MAX_NOTES = 2000 }
}

/** One JSON object per line; unknown fields are ignored so later versions can add them. */
object LogCodec {
    fun encode(entry: LogEntry): String = buildJsonObject {
        put("v", 1)
        put("id", entry.id)
        put("utc", entry.utc.toString())
        put("receiver", buildJsonObject {
            put("name", entry.receiverName); put("url", entry.receiverUrl)
            entry.receiverLocation?.let { put("lat", it.latitude); put("lon", it.longitude) }
        })
        put("hz", entry.frequencyHz)
        put("mode", entry.mode.name)
        put("low", entry.passband.lowHz)
        put("high", entry.passband.highHz)
        entry.rssiDbm?.let { put("rssi", it) }
        entry.rssiMinDbm?.let { put("rssiMin", it) }
        entry.rssiMaxDbm?.let { put("rssiMax", it) }
        entry.station?.let { s -> put("station", buildJsonObject {
            s.facilityId?.let { put("facility", it) }
            s.khz?.let { put("khz", it) }
            put("call", s.call)
            put("place", s.place)
            s.format?.let { put("format", it) }
            s.distanceKm?.let { put("km", it) }
            s.bearingDeg?.let { put("bearing", it) }
        }) }
        put("confidence", entry.confidence.name)
        put("notes", entry.notes)
    }.toString()

    fun decode(line: String): LogEntry {
        val o = Json.parseToJsonElement(line).jsonObject
        require(o.getValue("v").jsonPrimitive.int == 1) { "Unknown log entry version" }
        val receiver = o.getValue("receiver").jsonObject
        fun JsonObject.double(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
        return LogEntry(
            id = o.getValue("id").jsonPrimitive.content,
            utc = Instant.parse(o.getValue("utc").jsonPrimitive.content),
            receiverName = receiver.getValue("name").jsonPrimitive.content,
            receiverUrl = receiver.getValue("url").jsonPrimitive.content,
            receiverLocation = receiver.double("lat")?.let { lat -> receiver.double("lon")?.let { GeoPoint(lat, it) } },
            frequencyHz = o.getValue("hz").jsonPrimitive.long,
            mode = Mode.valueOf(o.getValue("mode").jsonPrimitive.content),
            passband = Passband(o.getValue("low").jsonPrimitive.int, o.getValue("high").jsonPrimitive.int),
            rssiDbm = o.double("rssi"),
            rssiMinDbm = o.double("rssiMin"),
            rssiMaxDbm = o.double("rssiMax"),
            station = o["station"]?.jsonObject?.let { s ->
                LoggedStation(s["facility"]?.jsonPrimitive?.intOrNull, s["khz"]?.jsonPrimitive?.intOrNull,
                    s.getValue("call").jsonPrimitive.content, s["place"]?.jsonPrimitive?.content.orEmpty(),
                    s["format"]?.jsonPrimitive?.content, s.double("km"), s.double("bearing"))
            },
            confidence = LogConfidence.valueOf(o.getValue("confidence").jsonPrimitive.content),
            notes = o["notes"]?.jsonPrimitive?.content.orEmpty(),
        )
    }
}

/** RFC 4180 CSV with UTC timestamps and kHz frequencies, for spreadsheets and log-sharing. */
object LogCsv {
    val HEADER = listOf("utc", "khz", "mode", "low_cut_hz", "high_cut_hz", "station", "confidence", "place", "format",
        "distance_km", "bearing_deg", "rssi_dbm", "rssi_min_dbm", "rssi_max_dbm", "receiver", "receiver_url",
        "receiver_lat", "receiver_lon", "facility_id", "notes", "id")

    fun write(entries: List<LogEntry>, out: Appendable) {
        out.append(HEADER.joinToString(",")).append("\r\n")
        entries.sortedBy { it.utc }.forEach { e -> out.append(row(e).joinToString(",", transform = ::quote)).append("\r\n") }
    }

    fun row(e: LogEntry): List<String> {
        fun n(value: Double?, digits: Int) = value?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: ""
        return listOf(e.utc.toString(), formatKhz(e.frequencyHz), e.mode.name, e.passband.lowHz.toString(),
            e.passband.highHz.toString(), e.station?.call.orEmpty(), e.confidence.label, e.station?.place.orEmpty(),
            e.station?.format.orEmpty(), n(e.station?.distanceKm, 0), n(e.station?.bearingDeg, 0), n(e.rssiDbm, 1),
            n(e.rssiMinDbm, 1), n(e.rssiMaxDbm, 1), e.receiverName, e.receiverUrl, n(e.receiverLocation?.latitude, 5),
            n(e.receiverLocation?.longitude, 5), e.station?.facilityId?.toString().orEmpty(), e.notes, e.id)
    }

    /** Quotes fields with separators, quotes or line breaks; neutralizes spreadsheet formula prefixes. */
    fun quote(field: String): String {
        val safe = if (field.isNotEmpty() && field[0] in "=+@\t\r" || (field.startsWith("-") && field.toDoubleOrNull() == null)) "'$field" else field
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}

/** Rolling RSSI extremes over the last [windowMs] of fresh readings (monotonic milliseconds). */
class RssiWindow(private val windowMs: Long = 10_000) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    @Synchronized fun add(atMs: Long, dbm: Double) {
        samples.addLast(atMs to dbm)
        while (samples.isNotEmpty() && samples.first().first < atMs - windowMs) samples.removeFirst()
    }

    @Synchronized fun clear() = samples.clear()

    /** (latest, min, max) of readings no older than the window at [nowMs], or null when none. */
    @Synchronized fun snapshot(nowMs: Long): Triple<Double, Double, Double>? {
        val fresh = samples.filter { it.first >= nowMs - windowMs }.map { it.second }
        return if (fresh.isEmpty()) null else Triple(fresh.last(), fresh.min(), fresh.max())
    }
}

/**
 * Append-only JSON Lines log. Additions append one line; edits and deletions rewrite via a temporary file and
 * atomic rename. Unreadable lines are preserved verbatim on rewrite and counted, never silently dropped.
 */
class Logbook(private val file: File) {
    private val mutex = Mutex()
    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = mutableEntries.asStateFlow()
    private var unreadable: List<String> = emptyList()
    val unreadableLines: Int get() = unreadable.size

    suspend fun load() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val lines = if (file.isFile) file.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList()
            val parsed = lines.map { line -> line to runCatching { LogCodec.decode(line) }.getOrNull() }
            unreadable = parsed.filter { it.second == null }.map { it.first }
            mutableEntries.value = parsed.mapNotNull { it.second }
        }
    }

    suspend fun add(entry: LogEntry) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.appendText(LogCodec.encode(entry) + "\n", Charsets.UTF_8)
        }
        mutableEntries.value = mutableEntries.value + entry
    }

    suspend fun replace(entry: LogEntry) = rewrite { list ->
        require(list.any { it.id == entry.id }) { "Log entry no longer exists" }
        list.map { if (it.id == entry.id) entry else it }
    }

    suspend fun delete(id: String) = rewrite { list -> list.filterNot { it.id == id } }

    private suspend fun rewrite(transform: (List<LogEntry>) -> List<LogEntry>) = mutex.withLock {
        val next = transform(mutableEntries.value)
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText((unreadable + next.map(LogCodec::encode)).joinToString("") { it + "\n" }, Charsets.UTF_8)
            check(temp.renameTo(file)) { "Could not replace ${file.name}" }
        }
        mutableEntries.value = next
    }
}

private val LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

fun formatLogTime(instant: Instant): String = LOG_TIME.format(instant)
