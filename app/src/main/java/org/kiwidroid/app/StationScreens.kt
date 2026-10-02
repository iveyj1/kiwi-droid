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
internal fun MwStationPanel(tuning: Tuning, directory: StationDirectoryState, includePermits: Boolean,
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
                    val listed = db.onChannel(khz, includePermits)
                    val more = if (listed.size > 1) " (+${listed.size - 1})" else ""
                    val text = when {
                        listed.isEmpty() -> "$khz kHz: no listed station"
                        location == null -> listed.first().let { "$khz kHz: ${it.label} ${it.place} · ${powerText(it)}$more" }
                        else -> MwRanker.rank(listed, location, now).first().let {
                            "$khz kHz: ${it.station.label} ${it.station.place} · ${rankingText(it)}$more"
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

internal enum class MwScope(val label: String) { PASSBAND("In passband"), ALL("All"), FAVORITES("Favorites") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MwStationPicker(db: MwStationDb, tuning: Tuning, includePermits: Boolean, favorites: List<MwFavorite>,
                             location: GeoPoint?, now: Instant, onIncludePermits: (Boolean) -> Unit,
                             onToggleFavorite: (MwStation) -> Unit, onDismiss: () -> Unit, onSelect: (MwStation) -> Unit) {
    val channels = remember(tuning) { mwChannelsInPassband(tuning) }
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(if (channels.isNotEmpty()) MwScope.PASSBAND else MwScope.ALL) }
    val rows: List<Pair<MwStation, MwRanking?>> = remember(db, query, scope, includePermits, channels, favorites, location, now) {
        val found = db.search(query, includePermits = includePermits || scope == MwScope.FAVORITES)
        val scoped = when (scope) {
            MwScope.PASSBAND -> found.filter { it.khz in channels }
            MwScope.ALL -> found
            MwScope.FAVORITES -> found.filter { station -> favorites.any { it.matches(station) } }
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
                MwScope.entries.forEach { option ->
                    FilterChip(scope == option, { scope = option }, { Text(option.label) },
                        enabled = option != MwScope.PASSBAND || channels.isNotEmpty(),
                        modifier = Modifier.testTag("mwScope${option.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }}"))
                }
                FilterChip(includePermits, { onIncludePermits(!includePermits) }, { Text("Construction permits") },
                    modifier = Modifier.testTag("mwPermits"))
            }
            Text("${rows.size} station${if (rows.size == 1) "" else "s"} · tap to tune" +
                if (scope == MwScope.PASSBAND && location != null) " · most likely first" else "",
                style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(rows, key = { "${it.first.facilityId}/${it.first.khz}" }) { (station, ranking) ->
                    val favorite = favorites.any { it.matches(station) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onSelect(station) }.padding(vertical = 6.dp)) {
                            Text("${station.khz} ${station.label}", style = MaterialTheme.typography.titleSmall)
                            Text(detailText(station), style = MaterialTheme.typography.bodySmall)
                            ranking?.let { Text(rankingText(it, bestDb), style = MaterialTheme.typography.bodySmall) }
                        }
                        val action = if (favorite) "Remove ${station.label} ${station.khz} from favorites"
                            else "Add ${station.label} ${station.khz} to favorites"
                        TextButton(onClick = { onToggleFavorite(station) },
                            modifier = Modifier.semantics { contentDescription = action }) {
                            Text(if (favorite) "★" else "☆", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            Text((if (location == null) "Ordered by licensed power; set the receiver location to rank by likely reception. "
                else "Likelihood is a coarse day/night ground- and sky-wave estimate from the receiver location; " +
                    "directional antennas and conditions are ignored. ") +
                "FCC AM data${db.metadata["fetched"]?.let { " fetched ${it.take(10)}" } ?: ""}. " +
                "Canadian, Mexican and Cuban entries are treaty notifications and may be out of date.",
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

private fun detailText(station: MwStation): String = buildString {
    append(station.place).append(" · class ").append(station.stationClass).append(" · ").append(powerText(station))
    if (station.directionalDay || station.directionalNight) append(" · DA")
    if (station.isPermit) append(" · construction permit")
}
