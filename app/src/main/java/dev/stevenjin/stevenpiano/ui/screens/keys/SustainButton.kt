// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.components.GlassSurface
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * The Keys screen's pedal: a latching glass pill (DESIGN.md › v1.9), 48 dp tall, floating just above
 * the keyboard's top edge. Down sends CC64 = 127 and reads *Sustain on* (its ring brightens to the
 * content colour); up sends CC64 = 0 and reads *Sustain*. [on] is what the piano was last told, so a
 * stop that lifts the pedal shows here. Nothing moves beneath it (the keys never do), so it is glass
 * without a blur: the surface, its ring in the action outline's grey and the specular line along its
 * top; with transparency reduced, the solid surface and the ring, as before.
 */
@Composable
fun SustainButton(on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    GlassSurface(
        modifier,
        shape = CircleShape,
        blur = false,
        outline = if (on) ink else LocalTertiary.current,
    ) {
        Box(
            Modifier
                .heightIn(min = PillHeight)
                .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
                .semantics { contentDescription = "Sustain" }
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (on) "Sustain on" else "Sustain",
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.labelLarge,
                color = ink,
            )
        }
    }
}

/** The Keys pills' height: the 48 dp target. */
internal val PillHeight = 48.dp
