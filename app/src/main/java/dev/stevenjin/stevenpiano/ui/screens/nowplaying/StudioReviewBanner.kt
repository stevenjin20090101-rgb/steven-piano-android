// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.KioskGateSheet
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.rememberKioskGate
import kotlinx.coroutines.launch

/**
 * Keep or Discard (DESIGN.md › v1.7 — M23): on Now playing and the tablet's now-playing panel, where
 * playback's problems show, once a piece Studio made has been heard (15 seconds of it, or all of it):
 * "Keep this piece?", what it is, then **Keep** (it stays, and is not asked about again) and
 * **Discard** (the piano is silenced, the player lets go of it, and it leaves the library). Discard
 * changes the library, so in kiosk mode it waits for the kiosk PIN; Keep never does. Nothing when no
 * piece is waiting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioReviewBanner(modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val review = graph.studio.review
    val asking by review.asking.collectAsStateWithLifecycle()
    val pieceId = asking ?: return
    val gate = rememberKioskGate()
    OutlinedBanner(StudioCopy.REVIEW_TITLE, modifier) {
        Text(StudioCopy.REVIEW_LINE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow {
            TextButton(onClick = { graph.appScope.launch { review.keep(pieceId) } }) { Text("Keep") }
            TextButton(onClick = { gate.run { graph.appScope.launch { review.discard(pieceId) } } }) { Text("Discard") }
        }
    }
    KioskGateSheet(gate)
}
