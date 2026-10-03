package org.kiwidroid.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.Locale
import org.kiwidroid.core.*

private const val NO_STATION = "none"
private const val OTHER_STATION = "other"
private const val MAX_CANDIDATES = 20

private fun stationKey(station: MwStation) = "${station.facilityId}/${station.khz}"

/** MW stations inside the logged passband, most likely first when the receiver location is known. */
internal fun logCandidates(db: MwStationDb?, entry: LogEntry, visibility: MwVisibility, now: Instant): List<Pair<MwStation, MwRanking?>> {
    if (db == null || entry.frequencyHz !in MW_BAND_HZ) return emptyList()
    val tuning = Tuning(entry.frequencyHz, entry.mode).withPassband(entry.passband)
    val stations = mwChannelsInPassband(tuning).flatMap { db.onChannel(it, visibility) }
    val location = entry.receiverLocation
    return if (location == null) stations.map { it to null } else MwRanker.rank(stations, location, now).map { it.station to it }
}

/** New entry (from [LogEntry] drafts) or edit of an existing one; station, confidence and notes are editable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogEditor(entry: LogEntry, isNew: Boolean, candidates: List<Pair<MwStation, MwRanking?>>,
                       onDismiss: () -> Unit, onSave: (LogEntry) -> Unit, onDelete: () -> Unit) {
    val shown = candidates.take(MAX_CANDIDATES)
    val logged = entry.station
    val initial = when {
        logged == null -> if (isNew) shown.firstOrNull()?.first?.let(::stationKey) ?: NO_STATION else NO_STATION
        else -> shown.firstOrNull { logged.matches(it.first) }?.first?.let(::stationKey) ?: OTHER_STATION
    }
    var selected by rememberSaveable { mutableStateOf(initial) }
    var other by rememberSaveable { mutableStateOf(if (initial == OTHER_STATION) logged?.call.orEmpty() else "") }
    var confidence by rememberSaveable {
        mutableStateOf(if (isNew) (if (initial == NO_STATION) LogConfidence.UNIDENTIFIED else LogConfidence.TENTATIVE) else entry.confidence)
    }
    var notes by rememberSaveable { mutableStateOf(entry.notes) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isNew) "Log reception" else "Edit log entry") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${formatLogTime(entry.utc)} · ${formatKhz(entry.frequencyHz)} kHz ${entry.mode.name} · ${entry.receiverName}",
                style = MaterialTheme.typography.bodyMedium)
            Text(rssiSummary(entry), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("logRssi"))
            Text("Station", style = MaterialTheme.typography.titleSmall)
            @Composable fun Choice(key: String, title: String, detail: String?, tag: String) {
                Row(Modifier.fillMaxWidth().selectable(selected == key, role = Role.RadioButton) {
                    selected = key
                    if (key == NO_STATION) confidence = LogConfidence.UNIDENTIFIED
                    else if (confidence == LogConfidence.UNIDENTIFIED) confidence = LogConfidence.TENTATIVE
                }.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == key, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            shown.forEach { (station, ranking) ->
                Choice(stationKey(station), "${station.khz} ${station.label} ${station.place}",
                    listOfNotNull(station.format, ranking?.let { "${formatDistance(it.distanceKm)} ${compassPoint(it.bearingDeg)}" })
                        .joinToString(" · ").ifEmpty { null }, "logStation-${station.label}")
            }
            if (candidates.size > shown.size) Text("${candidates.size - shown.size} less likely stations not shown; use Other.",
                style = MaterialTheme.typography.bodySmall)
            Choice(OTHER_STATION, if (shown.isEmpty()) "Station" else "Other", null, "logStationOther")
            if (selected == OTHER_STATION) ReplacementTextField(other, { other = it }, label = { Text("Station or call sign") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("logStationText"))
            Choice(NO_STATION, "Unidentified", null, "logStationNone")
            Text("Identification", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogConfidence.entries.forEach { option ->
                    FilterChip(confidence == option, { confidence = option }, { Text(option.label) },
                        enabled = (selected == NO_STATION) == (option == LogConfidence.UNIDENTIFIED),
                        modifier = Modifier.testTag("logConfidence-${option.name}"))
                }
            }
            ReplacementTextField(notes, { notes = it.take(LogEntry.MAX_NOTES) }, label = { Text("Notes") }, minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("logNotes"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!isNew) TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                modifier = Modifier.testTag("logDelete")) {
                Text(if (confirmDelete) "Tap again to delete" else "Delete entry", color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try {
                val station = when (selected) {
                    NO_STATION -> null
                    OTHER_STATION -> LoggedStation(null, null, other.trim())
                    else -> shown.first { stationKey(it.first) == selected }.let { (s, r) ->
                        // Keep the original snapshot when editing an entry whose station is unchanged.
                        if (logged != null && logged.matches(s)) logged else LoggedStation.of(s, r)
                    }
                }
                onSave(entry.copy(station = station, confidence = if (station == null) LogConfidence.UNIDENTIFIED else confidence,
                    notes = notes.trim()))
            } catch (e: IllegalArgumentException) { error = e.message }
        }, modifier = Modifier.testTag("logSave")) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun dbm(value: Double) = String.format(Locale.ROOT, "%.1f", value).replace('-', '−')

internal fun rssiSummary(entry: LogEntry): String {
    val now = entry.rssiDbm ?: return "RSSI not recorded (not playing)"
    val min = entry.rssiMinDbm
    val max = entry.rssiMaxDbm
    val range = if (min != null && max != null) " (${dbm(min)} … ${dbm(max)} over 10 s)" else ""
    return "RSSI ${dbm(now)} dBm$range"
}

/** Best confidence per logged MW station, keyed by facility ID and channel, for "heard" marks. */
internal fun heardStations(entries: List<LogEntry>): Map<Pair<Int, Int>, LogConfidence> =
    entries.mapNotNull { e -> e.station?.let { s -> s.facilityId?.let { id -> s.khz?.let { (id to it) to e.confidence } } } }
        .filter { it.second != LogConfidence.UNIDENTIFIED }
        .groupBy({ it.first }, { it.second }).mapValues { (_, list) -> list.minBy { it.ordinal } }

internal fun heardMark(station: MwStation, heard: Map<Pair<Int, Int>, LogConfidence>): String? =
    station.facilityIds.firstNotNullOfOrNull { heard[it to station.khz] }?.let {
        if (it == LogConfidence.HEARD_ID) "✓ logged" else "? logged tentatively"
    }

@Composable
internal fun LogbookDialog(entries: List<LogEntry>, onDismiss: () -> Unit, onEdit: (LogEntry) -> Unit,
                           onTune: (LogEntry) -> Unit, onExport: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(entries, query) {
        val q = query.trim().lowercase(Locale.ROOT)
        entries.sortedByDescending { it.utc }.filter { e ->
            q.isEmpty() || listOfNotNull(formatKhz(e.frequencyHz), e.station?.call, e.station?.place, e.station?.format,
                e.notes, e.receiverName, formatLogTime(e.utc)).any { q in it.lowercase(Locale.ROOT) }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Logbook") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(logStats(entries), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("logStats"))
            ReplacementTextField(query, { query = it }, label = { Text("Search kHz, station, place, notes") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("logSearch"))
            if (entries.isEmpty()) Text("No entries yet. Use Log while listening to record what you hear.",
                style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(shown, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().clickable { onEdit(e) }.padding(vertical = 6.dp).testTag("logEntry")) {
                        Text("${formatLogTime(e.utc)} · ${formatKhz(e.frequencyHz)} kHz ${e.mode.name}", style = MaterialTheme.typography.titleSmall)
                        val station = e.station
                        Text(listOfNotNull(station?.let { s -> listOf(s.call, s.place).filter { it.isNotEmpty() }.joinToString(" ") }
                            ?: "Unidentified", e.confidence.label.takeIf { station != null },
                            station?.distanceKm?.let { km -> formatDistance(km) + (station.bearingDeg?.let { " " + compassPoint(it) } ?: "") })
                            .joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                        Text("${rssiSummary(e)} · ${e.receiverName}", style = MaterialTheme.typography.bodySmall)
                        if (e.notes.isNotEmpty()) Text(e.notes, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onTune(e) }, modifier = Modifier.testTag("logTune")) { Text("Tune") }
                            TextButton(onClick = { onEdit(e) }, modifier = Modifier.testTag("logEdit")) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onExport, enabled = entries.isNotEmpty(), modifier = Modifier.testTag("logExport")) { Text("Export CSV") } })
}

/** Totals for the logbook header: entries, distinct stations by confidence, and states/provinces/countries heard. */
internal fun logStats(entries: List<LogEntry>): String {
    fun key(s: LoggedStation) = s.facilityId?.let { id -> "$id/${s.khz}" } ?: s.call.uppercase(Locale.ROOT)
    val identified = entries.filter { it.confidence == LogConfidence.HEARD_ID }.mapNotNull { it.station }
    val stations = identified.map(::key).toSet()
    val tentative = entries.filter { it.confidence == LogConfidence.TENTATIVE }.mapNotNull { it.station }.map(::key).toSet() - stations
    // Places are "City, State, Country" for database stations; typed-in stations have none.
    val regions = identified.mapNotNull { s -> s.place.split(", ").takeIf { it.size >= 3 }?.let { "${it[it.size - 2]}, ${it.last()}" } }.toSet()
    val countries = identified.mapNotNull { s -> s.place.split(", ").takeIf { it.size >= 2 }?.last() }.toSet()
    return "${entries.size} entr${if (entries.size == 1) "y" else "ies"} · ${stations.size} stations identified" +
        (if (tentative.isNotEmpty()) " (+${tentative.size} tentative)" else "") +
        " · ${regions.size} states/provinces · ${countries.size} countr${if (countries.size == 1) "y" else "ies"}"
}
