// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ui.theme.LocalDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * The app's chip on a sheet (the schedule editor, v1.6.2 — M19; the compose sheet, v1.7 — M24): the
 * chosen one carries a check (as the Piano tab's chip rows). On the sheet, which is the elevated tone
 * the chips are chosen in elsewhere, the chosen one takes the surface's tone inside a tertiary
 * hairline, as the web panel's chips do. TalkBack reads [description]. Pressed, it scales to 0.97 (v1.14 — motion).
 */
@Composable
fun SheetChip(label: String, chosen: Boolean, description: String = label, enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    FilterChip(
        selected = chosen,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.pressScale(press).semantics { contentDescription = description },
        interactionSource = press,
        enabled = enabled,
        leadingIcon = if (chosen) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.surface,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledLeadingIconColor = LocalDisabledGlyph.current,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = chosen,
            selectedBorderColor = LocalTertiary.current,
            selectedBorderWidth = 1.dp,
            disabledBorderColor = LocalHairline.current,
        ),
    )
}
