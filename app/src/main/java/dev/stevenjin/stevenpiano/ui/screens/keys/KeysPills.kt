// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ui.components.GlassSurface
import dev.stevenjin.stevenpiano.ui.theme.LocalDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/** Where the keyboard scrolls, the ‹ › octave buttons at the pills' two ends. */
class OctavePills(val canGoDown: Boolean, val canGoUp: Boolean, val onShift: (Int) -> Unit)

/**
 * The glass pills floating just above the keyboard's top edge (DESIGN.md › v1.9): the latching
 * [SustainButton], and where the keyboard scrolls ([octaves]) the ‹ octave down at the start and
 * the › octave up at the end, 48 dp each. They never cover the keys (whose tops are the soft end
 * of every key): the keyboard is never under glass.
 */
@Composable
fun KeysPills(sustain: Boolean, onSustain: (Boolean) -> Unit, octaves: OctavePills?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (octaves != null) OctavePill(R.drawable.ic_chevron_left, "Octave down", octaves.canGoDown) { octaves.onShift(-1) }
        SustainButton(sustain, onSustain)
        Spacer(Modifier.weight(1f))
        if (octaves != null) OctavePill(R.drawable.ic_chevron_right, "Octave up", octaves.canGoUp) { octaves.onShift(1) }
    }
}

/**
 * An octave button: a 48 dp glass circle, its glyph in the content colour and its ring in the action
 * outline's grey; disabled, the disabled glyph and the hairline. Glass without a blur, as the pedal.
 */
@Composable
private fun OctavePill(@DrawableRes glyph: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    GlassSurface(
        Modifier.size(PillHeight),
        shape = CircleShape,
        blur = false,
        outline = if (enabled) LocalTertiary.current else LocalHairline.current,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(glyph),
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else LocalDisabledGlyph.current,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
