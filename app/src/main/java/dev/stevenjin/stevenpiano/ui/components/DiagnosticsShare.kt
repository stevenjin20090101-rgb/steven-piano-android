// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import android.content.Context
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.diag.Diagnostics
import dev.stevenjin.stevenpiano.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Under Share diagnostics: what the file is (DESIGN.md › v1.4 › Diagnostics). */
const val DIAGNOSTICS_NOTE = "A small file with the app's logs. Nothing personal."

/** The Library's banner on the launch after a crash. */
const val CRASH_NOTICE = "The app crashed last time. Share diagnostics?"

/** When the zip can't be written (a full disk) or nothing can take it. */
const val DIAGNOSTICS_FAILED = "The diagnostics couldn't be shared."

/**
 * Builds the diagnostics zip off the main thread and opens the system share sheet on it; [busy]
 * while it is being written, [failed] when it could not be written or shared.
 */
@Stable
class DiagnosticsSharer(private val scope: CoroutineScope, private val context: Context, private val graph: AppGraph) {
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    /** [shared] runs once the share sheet is open. */
    fun share(shared: () -> Unit = {}) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            val zip = withContext(Dispatchers.IO) { runCatching { graph.diagnostics.export() }.getOrNull() }
            val opened = zip != null && Diagnostics.share(context, zip)
            busy = false
            failed = !opened
            if (opened) shared()
        }
    }
}

@Composable
fun rememberDiagnosticsSharer(): DiagnosticsSharer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) { DiagnosticsSharer(scope, context, context.graph) }
}

/**
 * The Piano tab's hub, last row of APP: the outlined Share diagnostics button with the eyebrow line
 * saying what it sends, and under it, when the file couldn't be written or shared, a line saying
 * so. Always available, whether or not the piano is connected; unavailable only while the file is
 * being written.
 */
@Composable
fun ShareDiagnosticsRow(modifier: Modifier = Modifier) {
    val sharer = rememberDiagnosticsSharer()
    val failure: (@Composable () -> Unit)? = if (sharer.failed) {
        {
            Text(
                DIAGNOSTICS_FAILED,
                Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        null
    }
    ActionRow(modifier, note = DIAGNOSTICS_NOTE, below = failure) {
        ActionButton("Share diagnostics", onClick = { sharer.share() }, enabled = !sharer.busy)
    }
}

/**
 * The launch after a crash: "The app crashed last time. Share diagnostics?" in an outlined banner
 * (never red), with Share diagnostics and Dismiss; either one answers it ([onAnswered]) until the
 * next crash.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashBanner(onAnswered: () -> Unit, modifier: Modifier = Modifier) {
    val sharer = rememberDiagnosticsSharer()
    OutlinedBanner(if (sharer.failed) "$CRASH_NOTICE $DIAGNOSTICS_FAILED" else CRASH_NOTICE, modifier) {
        FlowRow {
            TextButton(onClick = { sharer.share(onAnswered) }, enabled = !sharer.busy) { Text("Share diagnostics") }
            TextButton(onClick = onAnswered) { Text("Dismiss") }
        }
    }
}
