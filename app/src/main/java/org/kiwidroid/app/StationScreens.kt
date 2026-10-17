package org.kiwidroid.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.Locale
import org.kiwidroid.core.*

/** Shown only while tuned within the MW band; other bands keep the plain tuning screen. */
@Composable
internal fun MwStationPanel(tuning: Tuning, directory: StationDirectoryState, visibility: MwVisibility,
                            location: GeoPoint?, now: Instant, enabled: Boolean, onOpen: () -> Unit) {
    val db = directory.db
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            directory.error != null -> Text("MW station list unavailable: ${directory.error}",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            db == null -> Text("Loading MW stations…", style = MaterialTheme.typography.bodySmall)
            else -> {
                val channels = mwChannelsInPassband(tuning).take(2)
                if (channels.isEmpty()) Text("No 10 kHz MW channel in the passband", Modifier.testTag("mwChannel"),
                    style = MaterialTheme.typography.bodyMedium)
                channels.forEach { khz ->
                    val listed = db.onChannel(khz, visibility)
                    val more = if (listed.size > 1) " (+${listed.size - 1})" else ""
                    val text = when {
                        listed.isEmpty() -> "$khz kHz: no listed station"
                        location == null -> listed.first().let { "$khz kHz: ${it.label} ${it.place}${formatSuffix(it)} · ${powerText(it)}$more" }
                        else -> MwRanker.rank(listed, location, now).first().let {
                            "$khz kHz: ${it.station.label} ${it.station.place}${formatSuffix(it.station)} · ${rankingText(it)}$more"
                        }
                    }
                    Text(text, Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen).testTag("mwChannel"),
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (location == null) Text("Set this receiver's location (Servers → Edit) to rank stations by likely reception.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = onOpen, enabled = enabled && db != null,
            modifier = Modifier.fillMaxWidth().testTag("mwStations")) { Text("MW stations") }
    }
}

/** User-created shortcuts, shown on every band only once something has been starred. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MwFavoriteChips(favorites: List<MwFavorite>, db: MwStationDb?, enabled: Boolean, onTune: (Int) -> Unit) {
    if (favorites.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        favorites.sortedBy { it.khz }.forEach { favorite ->
            val call = db?.station(favorite.facilityId, favorite.khz)?.label ?: favorite.call
            AssistChip(onClick = { onTune(favorite.khz) }, label = { Text("${favorite.khz} $call") }, enabled = enabled,
                modifier = Modifier.testTag("favoriteChip"))
        }
    }
}

internal enum class MwScope(val label: String) {
    PASSBAND("In passband"), ALL("All"), FAVORITES("Favorites"), HIDDEN("Hidden")
}

/** Callbacks for the picker's visibility chips and per-row actions. */
internal class MwPickerActions(
    val onIncludePermits: (Boolean) -> Unit,
    val onIncludeSilent: (Boolean) -> Unit,
    val onIncludeUnverified: (Boolean) -> Unit,
    val onToggleFavorite: (MwStation) -> Unit,
    val onToggleHidden: (MwStation) -> Unit,
    val onLog: (MwStation) -> Unit = {},
    val logged: Set<Pair<Int, Int>> = emptySet(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MwStationPicker(db: MwStationDb, tuning: Tuning, visibility: MwVisibility, favorites: List<MwFavorite>,
                             location: GeoPoint?, now: Instant, heard: Map<Pair<Int, Int>, LogConfidence>,
                             actions: MwPickerActions,
                             onDismiss: () -> Unit, onSelect: (MwStation) -> Unit) {
    val channels = remember(tuning) { mwChannelsInPassband(tuning) }
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(if (channels.isNotEmpty()) MwScope.PASSBAND else MwScope.ALL) }
    val hidden = visibility.hidden
    val rows: List<Pair<MwStation, MwRanking?>> = remember(db, query, scope, visibility, channels, favorites, location, now) {
        // Favorites and the Hidden view list the user's own choices whatever the status filters say.
        val everything = MwVisibility(permits = true, silent = true, unverified = true)
        val scoped = when (scope) {
            MwScope.PASSBAND -> db.search(query, visibility).filter { it.khz in channels }
            MwScope.ALL -> db.search(query, visibility)
            MwScope.FAVORITES -> db.search(query, everything.copy(hidden = hidden)).filter { station -> favorites.any { it.matches(station) } }
            MwScope.HIDDEN -> db.search(query, everything).filter { station -> hidden.any { it.matches(station) } }
        }
        if (location == null) scoped.map { it to null }
        else if (scope == MwScope.PASSBAND) MwRanker.rank(scoped, location, now).map { it.station to it }
        else scoped.map { it to MwRanker.rank(it, location, now) }
    }
    val bestDb = if (scope == MwScope.PASSBAND) rows.firstOrNull()?.second?.takeIf { it.onAir }?.scoreDb else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("MW stations") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplacementTextField(query, { query = it }, label = { Text("Call, city, state or kHz") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("mwSearch"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MwScope.entries.filter { it != MwScope.HIDDEN || hidden.isNotEmpty() || scope == MwScope.HIDDEN }.forEach { option ->
                    FilterChip(scope == option, { scope = option }, { Text(option.label) },
                        enabled = option != MwScope.PASSBAND || channels.isNotEmpty(),
                        modifier = Modifier.testTag("mwScope${option.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }}"))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Also show:", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                FilterChip(visibility.permits, { actions.onIncludePermits(!visibility.permits) }, { Text("Permits") },
                    modifier = Modifier.testTag("mwPermits"))
                FilterChip(visibility.silent, { actions.onIncludeSilent(!visibility.silent) }, { Text("Silent") },
                    modifier = Modifier.testTag("mwSilent"))
                FilterChip(visibility.unverified, { actions.onIncludeUnverified(!visibility.unverified) }, { Text("Unverified") },
                    modifier = Modifier.testTag("mwUnverified"))
            }
            Text("${rows.size} station${if (rows.size == 1) "" else "s"} · tap to tune" +
                if (scope == MwScope.PASSBAND && location != null) " · most likely first" else "",
                style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(rows, key = { "${it.first.facilityId}/${it.first.khz}" }) { (station, ranking) ->
                    val favorite = favorites.any { it.matches(station) }
                    val isHidden = hidden.any { it.matches(station) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onSelect(station) }.padding(vertical = 6.dp)) {
                            Text("${station.khz} ${station.label}", style = MaterialTheme.typography.titleSmall)
                            station.format?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(detailText(station) + (heardMark(station, heard)?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall)
                            ranking?.let { Text(rankingText(it, bestDb), style = MaterialTheme.typography.bodySmall) }
                        }
                        val logged = station.facilityIds.any { (it to station.khz) in actions.logged }
                        TextButton(onClick = { actions.onLog(station) }, modifier = Modifier.semantics {
                            contentDescription = if (logged) "Edit latest log for ${station.label}" else "Log ${station.label}"
                        }) { Text(if (logged) "✓ log" else "+ log") }
                        val favoriteAction = if (favorite) "Remove ${station.label} ${station.khz} from favorites"
                            else "Add ${station.label} ${station.khz} to favorites"
                        TextButton(onClick = { actions.onToggleFavorite(station) },
                            modifier = Modifier.semantics { contentDescription = favoriteAction }) {
                            Text(if (favorite) "★" else "☆", style = MaterialTheme.typography.titleLarge)
                        }
                        val hideAction = if (isHidden) "Restore ${station.label} ${station.khz}"
                            else "Hide ${station.label} ${station.khz} as a bad entry"
                        TextButton(onClick = { actions.onToggleHidden(station) },
                            modifier = Modifier.semantics { contentDescription = hideAction }) {
                            Text(if (isHidden) "↺" else "⊘", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            Text((if (location == null) "Ordered by licensed power; set the receiver location to rank by likely reception. "
                else "Likelihood is a coarse day/night ground- and sky-wave estimate from the receiver location; " +
                    "directional antennas and conditions are ignored. ") +
                sourcesText(db),
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

private fun kw(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.') + " kW"

internal fun powerText(station: MwStation): String {
    val day = station.dayKw
    val night = station.nightKw
    return when {
        day != null && night != null && day == night -> kw(day)
        day != null && night != null -> "${kw(day)} day / ${kw(night)} night"
        day != null -> "${kw(day)} daytime only"
        night != null -> "${kw(night)} night only"
        else -> "power not listed"
    }
}

private fun periodName(period: MwPeriod) = when (period) {
    MwPeriod.DAY -> "day"
    MwPeriod.CRITICAL -> "critical hours"
    MwPeriod.NIGHT -> "night"
}

/** Distance and bearing from the receiver, the station's current authorization, and optional dB below the best. */
internal fun rankingText(ranking: MwRanking, bestDb: Double? = null): String = buildString {
    append(formatDistance(ranking.distanceKm)).append(' ').append(compassPoint(ranking.bearingDeg)).append(" · ")
    val kw = ranking.kw
    if (kw == null) append("not authorized at ").append(periodName(ranking.period))
    else {
        append(periodName(ranking.period)).append(' ').append(kw(kw))
        if (ranking.skywave) append(" · sky wave")
        if (bestDb != null) append(String.format(Locale.ROOT, " · %+.0f dB", ranking.scoreDb - bestDb).replace('-', '−'))
    }
}

private fun formatSuffix(station: MwStation) = station.format?.let { " · $it" } ?: ""

private fun statusText(station: MwStation): String? = when (station.status) {
    MwStation.PERMIT -> "construction permit"
    MwStation.SILENT -> "licensed but silent"
    MwStation.UNVERIFIED -> "unverified treaty record"
    MwStation.NOTIFIED -> if (station.source == MwStation.SOURCE_WIKIPEDIA) "treaty record, listed on Wikipedia" else "treaty record"
    else -> null
}

private fun sourceText(source: String) = when (source) {
    MwStation.SOURCE_LMS -> "FCC LMS"
    MwStation.SOURCE_ISED -> "ISED"
    MwStation.SOURCE_CRT -> "CRT"
    MwStation.SOURCE_IFT_2014 -> "IFT 2014 list"
    else -> null
}

private fun detailText(station: MwStation): String = buildString {
    append(station.place).append(" · class ").append(station.stationClass).append(" · ").append(powerText(station))
    if (station.directionalDay || station.directionalNight) append(" · DA")
    statusText(station)?.let { append(" · ").append(it) }
    sourceText(station.source)?.let { append(" · confirmed by ").append(it) }
}

private fun sourcesText(db: MwStationDb): String = buildString {
    append("Stations: FCC AM Query")
    db.metadata["fetched"]?.let { append(" (").append(it.take(10)).append(")") }
    if ("lms" in db.metadata) append(", FCC LMS")
    if ("ised" in db.metadata) append(", ISED Canada")
    if ("mx-crt" in db.metadata) append(", CRT México (CC BY 4.0)") else if ("mx-ift" in db.metadata) append(", IFT México 2014 list")
    append(". Cuban entries are unconfirmed treaty records. ")
    if (db.formatMetadata.isNotEmpty()) {
        append("Formats: Wikipedia contributors, CC BY-SA 4.0")
        db.formatMetadata["fetched"]?.let { append(" (").append(it.take(10)).append(")") }
        append(".")
    }
}
