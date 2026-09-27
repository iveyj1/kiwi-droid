package org.kiwidroid.app

import android.media.AudioManager
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
import java.util.Locale
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
                RadioScreen(state, model)
            }
        }
    }
}

@Composable
private fun RadioScreen(state: RadioUiState, model: RadioViewModel) {
    val settings = state.settings
    var frequency by rememberSaveable(settings.tuning.frequencyHz) { mutableStateOf(formatKhz(settings.tuning.frequencyHz)) }
    var serversOpen by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var stepOpen by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = frequency, onValueChange = { frequency = it },
                    label = { Text("Frequency (kHz)") }, singleLine = true,
                    enabled = state.loaded,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { tune() }),
                    modifier = Modifier.weight(1f).testTag("frequency"))
                Button(onClick = { tune() }, enabled = state.loaded, modifier = Modifier.padding(top = 8.dp)) { Text("Tune") }
            }
            Text("Tuned: ${formatKhz(settings.tuning.frequencyHz)} kHz", Modifier.testTag("tuned"))
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
                "Source %.2f Hz · missing frames %d · ADC overloads %d",
                metrics.sourceRate, metrics.missingFrames, metrics.adcOverflows), style = MaterialTheme.typography.bodySmall)
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error")) }
            HorizontalDivider()
            Text("Playback continues in the background and with the screen off. Stop here or in the media notification. Use the phone’s media volume.",
                style = MaterialTheme.typography.bodySmall)
            Text("Uncompressed audio uses about 86 MB/hour, plus overhead." +
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

    if (editorOpen) ReceiverEditor(settings.receivers.getOrNull(editingIndex ?: -1), state.message,
        onDismiss = { editorOpen = false }, onSave = { name, url ->
            if (model.saveReceiver(editingIndex, name, url)) { editorOpen = false; serversOpen = true }
        })

    if (stepOpen) StepEditor(settings.stepHz, state.message, onDismiss = { stepOpen = false }) {
        if (model.setStep(it)) stepOpen = false
    }
}

@Composable
private fun Choice(label: String, selected: String, options: List<String>, enabled: Boolean,
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
private fun ReceiverEditor(receiver: Receiver?, error: String?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(receiver?.name ?: "") }
    var url by rememberSaveable { mutableStateOf(receiver?.url ?: "http://") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (receiver == null) "Add receiver" else "Edit receiver") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("HTTP/HTTPS base URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Text("Example: http://10.0.0.41:8073", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { onSave(name, url) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun StepEditor(current: Long, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tuning step") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, label = { Text("Step (whole Hz)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(10, 100, 1000, 5000).forEach { value -> TextButton(onClick = { onSave(value.toString()) }) { Text("$value Hz") } }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
