// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline

/** The app's thinnest rule: dividers, outlines, tracks. */
val Hairline: Dp = 1.dp

/** A 1 dp rule in the hairline token. [startInset] lines it up with the text above it. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier, startInset: Dp = 0.dp) {
    HorizontalDivider(modifier.padding(start = startInset), thickness = Hairline, color = LocalHairline.current)
}
