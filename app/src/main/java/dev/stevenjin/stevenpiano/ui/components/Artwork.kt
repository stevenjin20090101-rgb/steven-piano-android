// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline

/**
 * Art for a playlist or a composer that has none (yet): the initial of [name] in the Display
 * style on the elevated surface, inside a 1 dp hairline outline like every art surface
 * (DESIGN.md › v1.2 › Artwork). Square; [modifier] sets its size. The letter is art, not text:
 * it keeps its size at any font scale, and screen readers skip it (the name is said beside it).
 */
@Composable
fun MonogramTile(name: String, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.medium
    val density = LocalDensity.current
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(Hairline, LocalHairline.current, shape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
            Text(
                Format.initial(name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
