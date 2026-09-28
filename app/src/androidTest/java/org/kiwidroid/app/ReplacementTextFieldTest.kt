package org.kiwidroid.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class ReplacementTextFieldTest {
    @get:Rule val rule = createComposeRule()

    @Test fun firstTapSelectsAllButSubsequentEditingKeepsCursor() {
        var external by mutableStateOf("5000.000")
        rule.setContent {
            MaterialTheme {
                var other by remember { mutableStateOf("-4500") }
                Column {
                    ReplacementTextField(external, { external = it }, Modifier.testTag("first"))
                    ReplacementTextField(other, { other = it }, Modifier.testTag("other"))
                }
            }
        }
        val first = rule.onNodeWithTag("first")
        val other = rule.onNodeWithTag("other")
        first.performTouchInput { click() }
        first.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 8)))
        first.performTextInput("335") // insert, not test-only replaceText: selection must do the replacement
        first.assertTextEquals("335")
        first.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        first.performTextInputSelection(TextRange(1))
        first.performTextInput("0")
        first.assertTextEquals("3035")
        other.performTouchInput { click() }
        other.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 5)))
        first.performTouchInput { click() }
        first.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 4)))
        // Further taps while focused must be allowed to place a cursor.
        first.performTouchInput { click() }
        first.assert(SemanticsMatcher("Collapsed cursor after second tap") {
            it.config[SemanticsProperties.TextSelectionRange].collapsed
        })
        rule.runOnIdle { external = "123.456" }
        first.assertTextEquals("123.456")
        other.performTouchInput { click() }
        first.performTouchInput { click() }
        first.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 7)))
    }
}
