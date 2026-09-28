package org.kiwidroid.app

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Select on entry, not on subsequent taps or keystrokes while already focused. */
@Composable
internal fun ReplacementTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var editing by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val displayed = if (editing.text == value) editing else editing.copy(text = value, selection = TextRange(value.length))
    SideEffect { if (editing != displayed) editing = displayed }
    LaunchedEffect(focused) {
        if (focused) {
            // Let the initial tap place its cursor before replacing that selection.
            withFrameNanos { }
            editing = editing.copy(text = currentValue, selection = TextRange(0, currentValue.length))
        }
    }
    OutlinedTextField(displayed, { next ->
        editing = next
        if (next.text != value) onValueChange(next.text)
    }, modifier = modifier.onFocusChanged { focused = it.isFocused }, enabled = enabled,
        label = label, singleLine = singleLine, minLines = minLines,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
}
