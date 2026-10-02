package org.kiwidroid.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.kiwidroid.core.*

/** Shown only while tuned within the MW band; other bands keep the plain tuning screen. */
@Composable
internal fun MwStationPanel(tuning: Tuning, directory: StationDirectoryState, includePermits: Boolean,
                            enabled: Boolean, onOpen: () -> Unit) {
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
                    val text = listed.firstOrNull()?.let { top ->
                        "$khz kHz: ${top.label} ${top.place} · ${powerText(top)}" +
                            if (listed.size > 1) " (+${listed.size - 1})" else ""
                    } ?: "$khz kHz: no listed station"
                    Text(text, Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen).testTag("mwChannel"),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        OutlinedButton(onClick = onOpen, enabled = enabled && db != null,
            modifier = Modifier.fillMaxWidth().testTag("mwStations")) { Text("MW stations") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MwStationPicker(db: MwStationDb, tuning: Tuning, includePermits: Boolean,
                             onIncludePermits: (Boolean) -> Unit, onDismiss: () -> Unit, onSelect: (MwStation) -> Unit) {
    val channels = remember(tuning) { mwChannelsInPassband(tuning) }
    var query by rememberSaveable { mutableStateOf("") }
    var inPassband by rememberSaveable { mutableStateOf(channels.isNotEmpty()) }
    val results = remember(db, query, inPassband, includePermits, channels) {
        db.search(query, includePermits).let { found -> if (inPassband) found.filter { it.khz in channels } else found }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("MW stations") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplacementTextField(query, { query = it }, label = { Text("Call, city, state or kHz") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("mwSearch"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(inPassband, { inPassband = true }, { Text("In passband") }, enabled = channels.isNotEmpty(),
                    modifier = Modifier.testTag("mwScopePassband"))
                FilterChip(!inPassband, { inPassband = false }, { Text("All") }, modifier = Modifier.testTag("mwScopeAll"))
                FilterChip(includePermits, { onIncludePermits(!includePermits) }, { Text("Construction permits") },
                    modifier = Modifier.testTag("mwPermits"))
            }
            Text("${results.size} stations · tap to tune", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(results, key = { "${it.facilityId}/${it.khz}" }) { station ->
                    Column(Modifier.fillMaxWidth().clickable { onSelect(station) }.padding(vertical = 6.dp)) {
                        Text("${station.khz} ${station.label}", style = MaterialTheme.typography.titleSmall)
                        Text(detailText(station), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("FCC AM data${db.metadata["fetched"]?.let { " fetched ${it.take(10)}" } ?: ""}. " +
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

private fun detailText(station: MwStation): String = buildString {
    append(station.place).append(" · class ").append(station.stationClass).append(" · ").append(powerText(station))
    if (station.directionalDay || station.directionalNight) append(" · DA")
    if (station.isPermit) append(" · construction permit")
}
