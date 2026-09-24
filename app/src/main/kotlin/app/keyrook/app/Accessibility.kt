// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A checkbox and its label as one control: clicking the label toggles it, and screen readers announce label and state
 * together. [description] replaces the spoken label where the visible one is ambiguous, such as "Hidden" next to every
 * field. [stacked] puts the label below the box.
 */
@Composable
internal fun LabeledCheckbox(checked: Boolean, label: String, enabled: Boolean = true, description: String? = null,
                             stacked: Boolean = false, onCheckedChange: (Boolean) -> Unit) {
    val control = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
        .then(if (description != null) Modifier.describedAs(description) else Modifier)
    if (stacked) Column(control, horizontalAlignment = Alignment.CenterHorizontally) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    } else Row(control, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(end = 8.dp))
    }
}

/** A radio button and its label as one control; place a group in a `selectableGroup()` container. */
@Composable
internal fun LabeledRadioButton(selected: Boolean, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(end = 8.dp))
    }
}

/** Spoken text that replaces the visible one, for controls whose visible label alone is ambiguous. */
internal fun Modifier.describedAs(description: String): Modifier = semantics { contentDescription = description }

/**
 * Show/hide button of a masked value: names the value it reveals and announces whether it is currently shown. Called
 * during composition so a language switch updates the text.
 */
internal fun Modifier.revealSemantics(label: String, shown: Boolean): Modifier {
    val description = UiText.text(if (shown) "a11y.hideValue" else "a11y.showValue", label)
    val state = UiText.text(if (shown) "a11y.shown" else "a11y.masked")
    return semantics { contentDescription = description; stateDescription = state }
}

/** A masked value is announced as hidden instead of as a row of bullet characters. */
internal fun Modifier.maskedValueSemantics(label: String): Modifier = describedAs(UiText.text("a11y.maskedValue", label))
