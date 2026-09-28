// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R

/**
 * A page of the Piano tab: its [title] in the Title style, no byline (the tab's header has it).
 * On phones, where the page covers the hub, a 48 dp back glyph comes first ([onBack]); beside the
 * hub on wide screens there is no back ([onBack] null) and the title sits level with the hub's own
 * title (the same height as [ScreenHeader], with the byline's line left empty), at every text size.
 */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (onBack != null) {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton(R.drawable.ic_back, "Back to Piano", Modifier.size(48.dp).mirrored(rtl), onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Title(title, Modifier.weight(1f))
        }
    } else {
        val bylineLine = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Title(title)
                Spacer(Modifier.height(bylineLine))
            }
        }
    }
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
