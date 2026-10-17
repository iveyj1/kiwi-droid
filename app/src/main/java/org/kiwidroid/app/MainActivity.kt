package org.kiwidroid.app

import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kiwidroid.core.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val model: RadioViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                val stations by model.stations.collectAsStateWithLifecycle()
                val log by model.logEntries.collectAsStateWithLifecycle()
                RadioScreen(state, stations, log, model)
            }
        }
    }
}

@Composable
private fun RadioScreen(state: RadioUiState, stations: StationDirectoryState, log: List<LogEntry>, model: RadioViewModel) {
    val settings = state.settings
    var frequency by rememberSaveable(settings.tuning.frequencyHz) { mutableStateOf(formatKhz(settings.tuning.frequencyHz)) }
    var serversOpen by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var stepOpen by rememberSaveable { mutableStateOf(false) }
    var soundOpen by rememberSaveable { mutableStateOf(false) }
    var passbandOpen by rememberSaveable { mutableStateOf(false) }
    var stationsOpen by rememberSaveable { mutableStateOf(false) }
    var logbookOpen by rememberSaveable { mutableStateOf(false) }
    // Draft or entry being edited, and whether it is new; not saveable across process death (drafts are cheap to redo).
    var editing by remember { mutableStateOf<Pair<LogEntry, Boolean>?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(model::exportLog)
    }
    val focus = LocalFocusManager.current
    // Station day/night authorization and sky-wave darkness change with time; re-rank once a minute.
    val now by produceState(Instant.now()) { while (true) { delay(60_000); value = Instant.now() } }
    fun tune() { if (model.tune(frequency)) focus.clearFocus() }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("KiwiDroid", style = MaterialTheme.typography.headlineMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Receiver", settings.receiver.name, settings.receivers.map { it.name }, state.loaded,
                    Modifier.weight(1f), model::selectReceiver)
                OutlinedButton(onClick = { serversOpen = true }, enabled = state.loaded) { Text("Servers") }
            }
            Text(settings.receiver.url, style = MaterialTheme.typography.bodySmall)
            Text(state.status, Modifier.testTag("status"), style = MaterialTheme.typography.titleMedium)
            state.audibleReceiver?.let { receiver ->
                val tune = state.audibleTuning ?: settings.tuning
                Text("Listening: ${receiver.name} · ${formatKhz(tune.frequencyHz)} kHz ${tune.mode.name}",
                    style = MaterialTheme.typography.bodySmall)
            }
            state.retainedReceiver?.let { Text("Retained: ${it.name}", style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplacementTextField(value = frequency, onValueChange = { frequency = it },
                    label = { Text("Frequency (kHz)") }, singleLine = true,
                    enabled = state.loaded,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { tune() }),
                    modifier = Modifier.weight(1f).testTag("frequency"))
                Button(onClick = { tune() }, enabled = state.loaded, modifier = Modifier.padding(top = 8.dp)) { Text("Tune") }
            }
            Text("Tuned: ${formatKhz(settings.tuning.frequencyHz)} kHz", Modifier.testTag("tuned"))
            MwFavoriteChips(settings.favorites, stations.db, state.loaded) { model.tuneToStation(it) }
            if (settings.tuning.frequencyHz in MW_BAND_HZ) MwStationPanel(settings.tuning, stations,
                settings.mwVisibility, settings.receiverLocation, now, state.loaded) { stationsOpen = true }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.step(-1) }, enabled = state.loaded,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("stepDown")) { Text("−") }
                OutlinedButton(onClick = { stepOpen = true }, enabled = state.loaded,
                    modifier = Modifier.heightIn(min = 56.dp)) { Text("Step: ${settings.stepHz} Hz") }
                Button(onClick = { model.step(1) }, enabled = state.loaded,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("stepUp")) { Text("+") }
            }
            Choice("Mode", settings.tuning.mode.name, Mode.entries.map { it.name }, state.loaded,
                Modifier.fillMaxWidth()) { model.setMode(Mode.entries[it]) }
            OutlinedButton(onClick = { passbandOpen = true }, enabled = state.loaded,
                modifier = Modifier.fillMaxWidth().testTag("passband")) {
                Text("Passband: ${settings.tuning.passband.lowHz} … ${settings.tuning.passband.highHz} Hz")
            }
            if (settings.tuning.mode == Mode.IQ) Text("IQ: I → left, Q → right (resampled playback, not demodulated audio or raw-IQ export). " +
                "Disable RSSI beeps for clean I/Q playback.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (state.running) model.stop() else model.play() }, enabled = state.loaded,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("playStop")) {
                Text(if (state.running) "Stop" else if (state.status == "Error") "Retry" else "Play")
            }
            val metrics = state.metrics
            val rssi = metrics?.rssiDbm
            Text(if (rssi == null) "RSSI: —" else String.format(Locale.ROOT, "RSSI: %.1f dBm%s", rssi,
                if (metrics.stale) " (stale)" else ""), Modifier.testTag("rssi"), style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(progress = { if (rssi == null || metrics.stale) 0f
                else ((rssi + 130) / 110).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            if (metrics != null) Text(String.format(Locale.ROOT,
                "Source %.2f Hz · %d ch · missing frames %d · ADC overloads %d",
                metrics.sourceRate, metrics.channels, metrics.missingFrames, metrics.adcOverflows), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = model.draftLog() to true }, enabled = state.loaded,
                    modifier = Modifier.weight(1f).testTag("logButton")) { Text("Log…") }
                OutlinedButton(onClick = { logbookOpen = true }, enabled = state.loaded,
                    modifier = Modifier.weight(1f).testTag("logbookButton")) { Text("Logbook (${log.size})") }
            }
            metrics?.playback?.let { audio ->
                if (audio.focusSilenced) Text("Temporarily silenced by other audio; resumes automatically.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Local underruns ${audio.radioUnderruns} · device underruns ${audio.deviceUnderruns} · dropped audio ${audio.droppedAudioMs} ms",
                    Modifier.testTag("audioDiagnostics"), style = MaterialTheme.typography.bodySmall)
                Text("Radio buffer ${audio.queuedAudioMs}/${audio.targetAudioMs} ms · device buffer ${audio.deviceBufferMs} ms" +
                    if (audio.buffering) " · buffering" else "", style = MaterialTheme.typography.bodySmall)
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error")) }
            state.notice?.let { Text(it, modifier = Modifier.testTag("notice")) }
            HorizontalDivider()
            SoundSwitch("Receiver audio", settings.sound.radioEnabled, state.loaded, "radioAudioSwitch") { model.setRadioAudio(it) }
            SoundSwitch("RSSI beeps", settings.sound.tones.enabled, state.loaded, "rssiToneSwitch") { model.setRssiTones(it) }
            OutlinedButton(onClick = { soundOpen = true }, enabled = state.loaded, modifier = Modifier.fillMaxWidth()) { Text("Sound settings") }
            val retention = listOf(0, 15, 30, 60, 120, 300, 600)
            Choice("Keep previous receiver", if (settings.keepReceiverSeconds == 0) "Off" else "${settings.keepReceiverSeconds} s",
                retention.map { if (it == 0) "Off" else "$it s" }, state.loaded, Modifier.fillMaxWidth()) {
                model.setKeepReceiverSeconds(retention[it])
            }
            Text("Switches keep the old audio playing until the new receiver is ready. Retaining it afterwards uses a second receiver slot and stream; retuning drops it.",
                style = MaterialTheme.typography.bodySmall)
            if (!settings.sound.radioEnabled) Text("Receiver audio muted locally; RSSI and the network stream remain active.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Playback continues in the background and with the screen off. Stop here or in the media notification. Use the phone’s media volume.",
                style = MaterialTheme.typography.bodySmall)
            Text("Uncompressed audio uses about ${if (settings.tuning.mode.channels == 2) 173 else 86} MB/hour, plus overhead." +
                if (settings.receiver.cleartext) " This receiver uses unencrypted HTTP/WS." else " This receiver uses HTTPS/WSS.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (serversOpen) AlertDialog(onDismissRequest = { serversOpen = false }, title = { Text("Receivers") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.receivers.forEachIndexed { index, receiver ->
                    Text(receiver.name, style = MaterialTheme.typography.titleSmall)
                    Text(receiver.url, style = MaterialTheme.typography.bodySmall)
                    Text(settings.locationOf(receiver)?.let { formatLocation(it) } ?: "Location not set",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editingIndex = index; serversOpen = false; editorOpen = true }) { Text("Edit") }
                        TextButton(onClick = { model.removeReceiver(index) }, enabled = settings.receivers.size > 1) { Text("Remove") }
                    }
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { editingIndex = null; serversOpen = false; editorOpen = true },
            enabled = settings.receivers.size < 50) { Text("Add") } },
        dismissButton = { TextButton(onClick = { serversOpen = false }) { Text("Done") } })

    if (editorOpen) settings.receivers.getOrNull(editingIndex ?: -1).let { receiver ->
        ReceiverEditor(receiver, receiver?.let(settings::locationOf), state.message,
            lookup = model::lookupReceiverLocation, onDismiss = { editorOpen = false },
            onSave = { name, url, latitude, longitude ->
                if (model.saveReceiver(editingIndex, name, url, latitude, longitude)) { editorOpen = false; serversOpen = true }
            })
    }

    if (passbandOpen) PassbandEditor(settings.tuning.mode, settings.tuning.passband, state.message,
        onDismiss = { passbandOpen = false }, onSave = model::setPassband)

    if (stationsOpen) stations.db?.let { db ->
        MwStationPicker(db, settings.tuning, settings.mwVisibility, settings.favorites, settings.receiverLocation, now, heardStations(log),
            MwPickerActions(model::setMwIncludePermits, model::setMwIncludeSilent, model::setMwIncludeUnverified,
                { model.toggleFavorite(it) }, { model.toggleHidden(it) }, onLog = { station ->
                    val latest = latestStationLog(log, station)
                    editing = if (latest != null) latest to false else model.draftLog().forStation(station) to true
                }, logged = log.mapNotNull { it.station?.let { s ->
                    s.facilityId?.let { id -> s.khz?.let { khz -> id to khz } }
                } }.toSet()),
            onDismiss = { stationsOpen = false }) { station ->
            if (model.tuneToStation(station.khz)) stationsOpen = false
        }
    }

    if (logbookOpen) LogbookDialog(log, onDismiss = { logbookOpen = false }, onEdit = { editing = it to false },
        onTune = { e -> if (model.tuneTo(e.frequencyHz, e.mode)) logbookOpen = false },
        onExport = { export.launch("kiwidroid-log-${DateTimeFormatter.BASIC_ISO_DATE.withZone(ZoneOffset.UTC).format(Instant.now())}.csv") })

    editing?.let { (entry, isNew) ->
        LogEditor(entry, isNew, logCandidates(stations.db, entry, settings.mwVisibility, entry.utc),
            onDismiss = { editing = null }, onSave = { model.saveLog(it); editing = null },
            onDelete = { model.deleteLog(entry.id); editing = null })
    }

    if (soundOpen) SoundEditor(settings.sound,
        state.metrics?.takeUnless { it.stale }?.rssiDbm, onDismiss = { soundOpen = false }, onSave = model::setSound)

    if (stepOpen) StepEditor(settings.stepHz, state.message, onDismiss = { stepOpen = false }) {
        if (model.setStep(it)) stepOpen = false
    }
}

@Composable
internal fun Choice(label: String, selected: String, options: List<String>, enabled: Boolean,
                   modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $selected ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, value -> DropdownMenuItem(text = { Text(value) },
                onClick = { open = false; onSelect(index) }) }
        }
    }
}

@Composable
private fun ReceiverEditor(receiver: Receiver?, location: GeoPoint?, error: String?, lookup: suspend (String) -> GeoPoint,
                           onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(receiver?.name ?: "") }
    var url by rememberSaveable { mutableStateOf(receiver?.url ?: "http://") }
    var latitude by rememberSaveable { mutableStateOf(location?.latitude?.let(::formatDegrees) ?: "") }
    var longitude by rememberSaveable { mutableStateOf(location?.longitude?.let(::formatDegrees) ?: "") }
    var lookupStatus by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (receiver == null) "Add receiver" else "Edit receiver") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplacementTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            ReplacementTextField(url, { url = it }, label = { Text("HTTP/HTTPS base URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Text("Example: http://10.0.0.41:8073", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplacementTextField(latitude, { latitude = it }, label = { Text("Latitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.weight(1f).testTag("receiverLatitude"))
                ReplacementTextField(longitude, { longitude = it }, label = { Text("Longitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.weight(1f).testTag("receiverLongitude"))
            }
            Text("Antenna location in decimal degrees (west negative), used to rank MW stations. Leave blank if unknown.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !looking, onClick = {
                looking = true
                lookupStatus = "Asking receiver…"
                scope.launch {
                    lookupStatus = try {
                        val point = lookup(url)
                        latitude = formatDegrees(point.latitude); longitude = formatDegrees(point.longitude)
                        "Filled from receiver GPS; Save to keep it."
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        "Lookup failed: ${e.message ?: e.javaClass.simpleName}"
                    } finally { looking = false }
                }
            }) { Text("From receiver") }
            lookupStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { onSave(name, url, latitude, longitude) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun StepEditor(current: Long, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tuning step") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplacementTextField(text, { text = it }, label = { Text("Step (whole Hz)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(10, 100, 1000, 5000).forEach { value -> TextButton(onClick = { onSave(value.toString()) }) { Text("$value Hz") } }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun formatDegrees(value: Double) = String.format(Locale.ROOT, "%.5f", value).trimEnd('0').trimEnd('.')

private fun formatLocation(point: GeoPoint) = "${formatDegrees(point.latitude)}, ${formatDegrees(point.longitude)}"
