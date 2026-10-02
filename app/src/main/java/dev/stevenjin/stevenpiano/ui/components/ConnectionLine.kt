// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * "● Sent to piano" with the live dot (breathing while a piece plays), or "○ Not connected" —
 * or [notConnected], where a screen needs to say more — which opens the Piano tab. [sent] is the
 * connected line's words, where a screen needs other ones, and [live] whether its dot is the live one
 * (v1.11 — M29: the Keys tab's "○ Keyboard connected. Live is off.", whose keyboard is not sent: red
 * means sent to the piano, nothing else).
 */
@Composable
fun ConnectionLine(
    connected: Boolean,
    playing: Boolean,
    onOpenPiano: () -> Unit,
    modifier: Modifier = Modifier,
    notConnected: String = "Not connected",
    sent: String = "Sent to piano",
    live: Boolean = connected,
) {
    val tap = if (connected) Modifier else Modifier.clickable(onClickLabel = "Open the Piano tab", role = Role.Button, onClick = onOpenPiano)
    Row(
        modifier
            .then(tap)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(live = connected && live, breathing = playing)
        Spacer(Modifier.width(8.dp))
        Text(
            if (connected) sent else notConnected,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
