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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.record.RecordingPieces
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.KioskGateSheet
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.rememberKioskGate
import kotlinx.coroutines.launch

/**
 * Keep or Discard (DESIGN.md › v1.7 — M23, M24): on Now playing and the tablet's now-playing panel, where
 * playback's problems show, once a piece Studio made has been heard (15 seconds of it, or all of it):
 * "Keep this piece?", what it is (a composition's line says what it is in the manner of, read from its
 * sheet's own line; a transcription's that it came from a recording), then **Keep** (it stays, and is not asked about again) and
 * **Discard** (the piano is silenced, the player lets go of it, and it leaves the library). Discard
 * changes the library, so in kiosk mode it waits for the kiosk PIN; Keep does not, but for a recording made
 * on the tablet (v1.11 — M29: "Recorded here. Discard deletes it."), which waits for someone with the PIN
 * either way. Nothing when no piece is waiting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioReviewBanner(modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val review = graph.studioReview
    val asking by review.asking.collectAsStateWithLifecycle()
    val pieceId = asking ?: return
    val gate = rememberKioskGate()
    val sheet by remember(pieceId) { graph.artwork.artwork(ArtworkEntity.forPiece(pieceId)) }.collectAsStateWithLifecycle(null)
    OutlinedBanner(StudioCopy.REVIEW_TITLE, modifier) {
        Text(StudioCopy.reviewLine(sheet?.description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow {
            // A recording made here waits for someone with the PIN in kiosk mode, Keep as Discard (v1.11 — M29).
            val keep = { graph.appScope.launch { review.keep(pieceId) }; Unit }
            TextButton(onClick = { if (RecordingPieces.isRecording(sheet?.description)) gate.run(keep) else keep() }) { Text("Keep") }
            TextButton(onClick = { gate.run { graph.appScope.launch { review.discard(pieceId) } } }) { Text("Discard") }
        }
    }
    KioskGateSheet(gate)
}
