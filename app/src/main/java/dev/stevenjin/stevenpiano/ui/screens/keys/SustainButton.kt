// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * The Keys screen's pedal: one outlined toggle that latches. Down sends CC64 = 127 and reads
 * *Sustain on* (its outline brightens to the content colour); up sends CC64 = 0 and reads
 * *Sustain*. [on] is what the piano was last told, so a stop that lifts the pedal shows here.
 */
@Composable
fun SustainButton(on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        checked = on,
        onCheckedChange = onToggle,
        modifier = modifier.semantics {
            role = Role.Switch
            contentDescription = "Sustain"
        },
        shape = ButtonDefaults.outlinedShape,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Hairline, if (on) MaterialTheme.colorScheme.onSurface else LocalTertiary.current),
    ) {
        Box(
            Modifier
                .defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
                .padding(ButtonDefaults.ContentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (on) "Sustain on" else "Sustain",
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
