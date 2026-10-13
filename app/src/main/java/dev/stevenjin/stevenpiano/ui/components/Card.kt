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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.stevenjin.stevenpiano.ui.theme.LocalGlassEdge
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline

/**
 * The app's card (DESIGN.md › v1.25 — M59, the web panel's thin edge): content on the elevated surface in the cards'
 * 12 dp corners, a 1 dp hairline round it and, just inside its top edge, the 1 dp specular line (white at 70 % on the
 * paper, 10 % on the ink), straight and as wide as the card. Content, never glass: it neither blurs nor floats. The
 * connection card, the note views on Now playing, the quiet times' week and sections are drawn on it.
 */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .cardEdge(shape, LocalHairline.current, LocalGlassEdge.current),
        content = content,
    )
}

/**
 * The card's edge over what it holds: the specular line one hairline down, then the hairline itself; the surface is
 * clipped to [shape], so a stroke twice as wide as the line shows exactly the line inside it.
 */
private fun Modifier.cardEdge(shape: Shape, hairline: Color, specular: Color): Modifier = drawWithCache {
    val line = Hairline.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawWithContent {
        drawContent()
        drawRect(specular, Offset(0f, line), Size(size.width, line))
        drawOutline(outline, hairline, style = Stroke(line * 2))
    }
}
