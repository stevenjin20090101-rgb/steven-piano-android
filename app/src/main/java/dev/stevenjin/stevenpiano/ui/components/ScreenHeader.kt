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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.Provenance
import dev.stevenjin.stevenpiano.ui.HeldByline
import dev.stevenjin.stevenpiano.ui.LocalBylineHold

/**
 * A tab's title in the Title style with the [byline] under it in the eyebrow style (PLAYER
 * PIANO · BY STEVEN JIN on every tab, DESIGN.md › v1.2 › Byline), and the tab's actions at the
 * end. The byline is simply there: no divider, no animation. In kiosk mode it is also the hidden
 * way out: a three-second hold opens the kiosk PIN ([LocalBylineHold], DESIGN.md › v1.6.1 — M20).
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    byline: String = Provenance.byline,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val hold = LocalBylineHold.current
            if (hold == null) Eyebrow(byline) else HeldByline(byline, hold)
        }
        actions()
    }
}
