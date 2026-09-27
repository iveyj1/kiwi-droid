package org.kiwidroid.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import org.kiwidroid.core.*

@Composable
internal fun SoundSwitch(label: String, checked: Boolean, enabled: Boolean = true, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun SoundEditor(initial: SoundSettings, currentRssi: Double?, onDismiss: () -> Unit,
                         onSave: (SoundSettings) -> Boolean) {
    var radioEnabled by rememberSaveable { mutableStateOf(initial.radioEnabled) }
    var tonesEnabled by rememberSaveable { mutableStateOf(initial.tones.enabled) }
    var points by rememberSaveable { mutableStateOf(formatPitchPoints(initial.tones.points)) }
    var scale by rememberSaveable { mutableStateOf(initial.tones.scale) }
    var root by rememberSaveable { mutableIntStateOf(initial.tones.root) }
    var offsets by rememberSaveable { mutableStateOf(initial.tones.customOffsets.joinToString(", ")) }
    var radioVolume by rememberSaveable { mutableIntStateOf(initial.radioVolumePercent) }
    var toneVolume by rememberSaveable { mutableIntStateOf(initial.tones.volumePercent) }
    var duration by rememberSaveable { mutableStateOf(initial.tones.durationMs.toString()) }
    var interval by rememberSaveable { mutableStateOf(initial.tones.intervalMs.toString()) }
    var fade by rememberSaveable { mutableStateOf(initial.tones.fadeMs.toString()) }
    var smoothing by rememberSaveable { mutableStateOf(initial.tones.smoothingMs.toString()) }
    var hysteresis by rememberSaveable { mutableStateOf(initial.tones.hysteresisDb.toString()) }
    var waveform by rememberSaveable { mutableStateOf(initial.tones.waveform) }
    var latchMode by rememberSaveable { mutableStateOf(initial.tones.latchMode) }
    var latchHold by rememberSaveable { mutableStateOf(initial.tones.latchHoldMs.toString()) }
    var latchThreshold by rememberSaveable { mutableStateOf(initial.tones.latchThresholdDb.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun save() {
        try {
            fun integer(text: String, label: String): Int = requireNotNull(text.trim().toIntOrNull()) { "$label must be a whole number" }
            val config = SoundSettings(radioEnabled, radioVolume, RssiToneSettings(
                enabled = tonesEnabled, points = parsePitchPoints(points), scale = scale, root = root,
                customOffsets = offsets.trim().split(Regex("[,\\s]+")).map { integer(it, "Scale offset") },
                volumePercent = toneVolume, durationMs = integer(duration, "Beep length"),
                intervalMs = integer(interval, "Repeat interval"), fadeMs = integer(fade, "Fade"),
                smoothingMs = integer(smoothing, "Smoothing"),
                hysteresisDb = requireNotNull(hysteresis.trim().toDoubleOrNull()) { "Enter hysteresis in dB" },
                waveform = waveform, latchMode = latchMode, latchHoldMs = integer(latchHold, "Latch hold"),
                latchThresholdDb = requireNotNull(latchThreshold.trim().toDoubleOrNull()) { "Enter latch threshold in dB" },
            ))
            if (onSave(config)) onDismiss()
        } catch (e: IllegalArgumentException) { error = e.message ?: "Invalid sound setting" }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sound settings", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { save() }, modifier = Modifier.testTag("saveSound")) { Text("Save") }
                }
                error?.let { Text(it, Modifier.padding(horizontal = 16.dp).testTag("soundError"), color = MaterialTheme.colorScheme.error) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoundSwitch("Receiver audio", radioEnabled, tag = "editRadioAudio") { radioEnabled = it }
                    SoundSwitch("RSSI beeps", tonesEnabled, tag = "editRssiTones") { tonesEnabled = it }
                    Text("Mute receiver audio to hear only RSSI beeps. The SND connection remains active; data use is unchanged.",
                        style = MaterialTheme.typography.bodySmall)
                    VolumeControl("Receiver volume", radioVolume) { radioVolume = it }
                    VolumeControl("Beep volume", toneVolume) { toneVolume = it }
                    Text("Start with a low beep volume. Both use the phone’s media volume.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Pitch mapping", style = MaterialTheme.typography.titleMedium)
                    Text("Default: +10 dB = +1 octave (12 semitones). Stronger signals sound higher. " +
                        "Custom points interpolate in semitones; their spacing sets sensitivity. Pitch clamps at the endpoints.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(points, { points = it }, label = { Text("dBm=note, weakest first") }, minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("pitchPoints"))
                    Text("Examples: -100=C4, -90=C5. Sharps (F#4), flats (Bb3), or MIDI 36–108 are accepted.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { points = formatPitchPoints(defaultPitchPoints()); error = null }) { Text("10 dB/octave") }
                        OutlinedButton(onClick = {
                            currentRssi?.let {
                                // Keep the preset within valid mapping limits even for unusual receiver reports.
                                points = formatPitchPoints(defaultPitchPoints(it.coerceIn(-160.0, 10.0)))
                                error = null
                            }
                        }, enabled = currentRssi != null && currentRssi in -160.0..10.0) { Text("RSSI → C5") }
                    }
                    Text("RSSI → C5 centers the 10 dB/octave preset on the current reading, useful before flexing a cable.",
                        style = MaterialTheme.typography.bodySmall)
                    Choice("Scale", scale.label, MusicalScale.entries.map { it.label }, true, Modifier.fillMaxWidth()) {
                        scale = MusicalScale.entries[it]
                    }
                    if (scale != MusicalScale.CONTINUOUS && scale != MusicalScale.CHROMATIC) {
                        Choice("Scale root", NOTE_NAMES[root], NOTE_NAMES, true, Modifier.fillMaxWidth()) { root = it }
                    }
                    if (scale == MusicalScale.CUSTOM) OutlinedTextField(offsets, { offsets = it },
                        label = { Text("Semitones from root (0–11)") }, modifier = Modifier.fillMaxWidth())
                    Text("Chromatic steps are about 0.83 dB with the default map. Continuous pitch reveals changes smaller than a semitone.",
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Dip/peak latch", style = MaterialTheme.typography.titleMedium)
                    Choice("Latch", latchMode.label, RssiLatchMode.entries.map { it.label }, true, Modifier.fillMaxWidth()) {
                        latchMode = RssiLatchMode.entries[it]
                    }
                    SoundNumber("Latch hold (ms, from first beep)", latchHold, Modifier.testTag("latchHold")) { latchHold = it }
                    SoundNumber("Latch threshold (dB)", latchThreshold, Modifier.testTag("latchThreshold"), decimal = true) { latchThreshold = it }
                    Text("Captures raw one-sample RSSI dips/peaks before smoothing, relative to the preceding smoothed level. " +
                        "Extremes survive until sounded at least once; hold time starts then. Both directions alternate beeps. " +
                        "Pitch still follows the selected scale and range. Stale RSSI or changing tone settings clears the latch.",
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Beep timing and stability", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoundNumber("Length (ms)", duration, Modifier.weight(1f)) { duration = it }
                        SoundNumber("Repeat (ms)", interval, Modifier.weight(1f)) { interval = it }
                    }
                    SoundNumber("Attack/release fade (ms)", fade) { fade = it }
                    SoundNumber("RSSI smoothing (ms, 0 = none)", smoothing) { smoothing = it }
                    SoundNumber("Note hysteresis (dB)", hysteresis, decimal = true) { hysteresis = it }
                    Text("Pitch is held for each beep. Lower repeat/smoothing values respond faster. " +
                        "Hysteresis prevents note chatter near a boundary; latched extremes bypass smoothing/hysteresis. " +
                        "Beeps stop after two seconds without fresh RSSI, and on Stop/disconnect.",
                        style = MaterialTheme.typography.bodySmall)
                    Choice("Waveform", waveform.name.lowercase(), ToneWaveform.entries.map { it.name.lowercase() }, true,
                        Modifier.fillMaxWidth()) { waveform = ToneWaveform.entries[it] }
                }
            }
        }
    }
}

@Composable
private fun VolumeControl(label: String, value: Int, onChange: (Int) -> Unit) {
    Text("$label: $value%")
    Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SoundNumber(label: String, value: String, modifier: Modifier = Modifier, decimal: Boolean = false,
                        onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        modifier = modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}
