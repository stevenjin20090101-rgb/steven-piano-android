// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular

/**
 * The engraved-camera-label style: 12 sp Medium, tracked +1.4 sp, tertiary, uppercase, with
 * tabular figures. Row meta lines use it in sentence case ([uppercase] false) and secondary.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalTertiary.current,
    uppercase: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = if (uppercase) text.uppercase() else text,
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.labelSmall.merge(Tabular),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
