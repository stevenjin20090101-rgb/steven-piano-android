// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.PianoTheme
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.web.Poster
import dev.stevenjin.stevenpiano.web.QrMatrix
import kotlin.math.floor

/**
 * [text]'s QR code as a small paper card: always the paper scheme's ink on its elevated paper,
 * whatever the app's appearance (a phone's camera reads dark modules on light best, and a code
 * drawn light on dark is one some cameras refuse), with the quiet zone inside the card, a hairline
 * round it like the app's art, and [onClick] to show it large. Modules are whole pixels, so no seam
 * shows between them.
 */
@Composable
fun QrTile(text: String, size: Dp, description: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val matrix = remember(text) { Poster.qr(text) }
    val hairline = LocalHairline.current
    PianoTheme(darkTheme = false) {
        val paper = MaterialTheme.colorScheme.surfaceVariant
        val ink = MaterialTheme.colorScheme.onSurface
        Canvas(
            modifier
                .size(size)
                .border(Hairline, hairline, MaterialTheme.shapes.small)
                .background(paper, MaterialTheme.shapes.small)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show it large", onClick = onClick) else Modifier)
                .semantics { contentDescription = description },
        ) {
            val cells = matrix.size + 2 * QrMatrix.QUIET_ZONE
            val cell = floor(this.size.minDimension / cells)
            val origin = (this.size.minDimension - cell * cells) / 2 + cell * QrMatrix.QUIET_ZONE
            for (row in 0 until matrix.size) {
                for (column in 0 until matrix.size) {
                    if (!matrix.isDark(row, column)) continue
                    drawRect(ink, topLeft = Offset(origin + column * cell, origin + row * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

/**
 * The QR large, for a phone held up to the tablet: a sheet with its drag handle, the code as wide
 * as the sheet allows (at most 480 dp), [url] in the Title style under it in tabular figures, and
 * the [caption] (what scanning does).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrSheet(url: String, caption: String, onDismiss: () -> Unit) {
    GlassSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = min(min(maxWidth - 64.dp, 480.dp), maxHeight - 160.dp).coerceAtLeast(160.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QrTile(url, side, "QR code for $url", Modifier.aspectRatio(1f))
                Spacer(Modifier.height(16.dp))
                Text(
                    url,
                    Modifier
                        .widthIn(max = 480.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleLarge.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Eyebrow(caption, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
            }
        }
    }
}
