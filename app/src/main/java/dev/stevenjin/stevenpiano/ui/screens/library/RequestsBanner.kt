// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.web.GuestRequests

/**
 * Guests' requests waiting for approval (Approve requests first on; DESIGN.md › v1.5.1 — M18),
 * on the tablet's Library under the import and crash banners: "2 requests waiting", then the
 * oldest one's piece and composer, with Approve (it joins Up next) and Dismiss. The next one
 * follows as each is answered. Nothing when none waits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestsBanner(pending: List<GuestRequests.Request>, onApprove: (Long) -> Unit, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    val oldest = pending.firstOrNull() ?: return
    OutlinedBanner("${Format.count(pending.size, "request", "requests")} waiting", modifier) {
        Text(
            listOf(oldest.title, oldest.composer).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow {
            TextButton(onClick = { onApprove(oldest.id) }) { Text("Approve") }
            TextButton(onClick = { onDismiss(oldest.id) }) { Text("Dismiss") }
        }
    }
}
