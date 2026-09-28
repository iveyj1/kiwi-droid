package org.kiwidroid.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.kiwidroid.core.Mode
import org.kiwidroid.core.Passband

@Composable
internal fun PassbandEditor(mode: Mode, band: Passband, error: String?, onDismiss: () -> Unit,
                            onSave: (Passband) -> Boolean) {
    var low by rememberSaveable(mode) { mutableStateOf(band.lowHz.toString()) }
    var high by rememberSaveable(mode) { mutableStateOf(band.highHz.toString()) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${mode.name} passband") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Signed audio/baseband limits in Hz, relative to the receiver carrier. " +
                "Use negative cuts for LSB and positive cuts for USB. Limits: −6000 to +6000 Hz.")
            OutlinedTextField(low, { low = it }, label = { Text("Lower limit (Hz)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.testTag("passbandLow"))
            OutlinedTextField(high, { high = it }, label = { Text("Upper limit (Hz)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.testTag("passbandHigh"))
            if (mode.centerTuning) Text("CW/CWN: displayed frequency stays at the passband center. " +
                "Changing the cuts also adjusts the receiver frequency offset.", style = MaterialTheme.typography.bodySmall)
            Text("Remembered independently for each mode. Save applies immediately when connected.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                low = mode.defaultPassband.lowHz.toString()
                high = mode.defaultPassband.highHz.toString()
                localError = null
            }) { Text("Mode defaults") }
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("passbandError")) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try {
                val lower = requireNotNull(low.trim().replace('−', '-').toIntOrNull()) { "Lower limit must be a whole number of Hz" }
                val upper = requireNotNull(high.trim().replace('−', '-').toIntOrNull()) { "Upper limit must be a whole number of Hz" }
                if (onSave(Passband(lower, upper))) onDismiss() else localError = null
            } catch (e: IllegalArgumentException) { localError = e.message }
        }, modifier = Modifier.testTag("savePassband")) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
