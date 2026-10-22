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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph

/**
 * "● Sent to piano" with the link's dot ([LinkDot]: filled while connected, the live red breathing while a piece
 * [playing] goes to the piano), or "○ Not connected" — or [notConnected], where a screen needs to say more — which
 * opens the Piano tab. [sent] is the connected line's words, where a screen needs other ones, and [live] whether this
 * line's notes go to the piano at all (v1.11 — M29: the Keys tab's "● Keyboard connected. Live is off.", whose keyboard
 * is not sent, so its dot never turns red: red means notes going to the piano, nothing else). Nor while the piano's 24 V
 * comes up (v1.26 — M62: its power plug's rail holds the notes, [dev.stevenjin.stevenpiano.AppGraph.railHoldsNotes]): the
 * connected dot until they reach the piano.
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
    val held by LocalContext.current.graph.railHoldsNotes.collectAsStateWithLifecycle()
    val tap = if (connected) Modifier else Modifier.clickable(onClickLabel = "Open the Piano tab", role = Role.Button, onClick = onOpenPiano)
    Row(
        modifier
            .then(tap)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(LinkDot.of(connected, sending = playing && live && !held))
        Spacer(Modifier.width(8.dp))
        Text(
            if (connected) sent else notConnected,
            style = MaterialTheme.typography.bodyLarge,
            color = secondaryText(),   // the primary colour over the album-colour backdrop (v1.15 — M41)
        )
    }
}
