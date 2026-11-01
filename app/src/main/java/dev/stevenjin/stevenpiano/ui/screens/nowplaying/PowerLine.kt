// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.power.RailMessage
import dev.stevenjin.stevenpiano.ui.PowerCopy
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import kotlinx.coroutines.delay

/**
 * v1.39 — M76: under the transport, one quiet line with a plug paired: "12.3 W now · peak 145.0 W this piece" (the peak
 * from each piece's start), "— W" without a reading under 3 s old. Paper and ink, no animation; amber only while a power
 * watch has tripped (its words are the rail's banner's), or "Power reading lost" while the plug's adverts are silent.
 */
@Composable
internal fun PowerLine(modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val plug by graph.plugSettings.collectAsStateWithLifecycle()
    if (plug?.pairing == null) return
    val power by graph.piecePower.state.collectAsStateWithLifecycle()
    val lost by graph.rail.readingLost.collectAsStateWithLifecycle()
    val message by graph.rail.message.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)   // the reading goes "— W" once it is 3 s old
            now = SystemClock.elapsedRealtime()
        }
    }
    val tripped = message.let {
        it is RailMessage.BoardOn || it is RailMessage.CoilOn || it is RailMessage.BoardLost || it is RailMessage.AfterPiece ||
            it is RailMessage.KeysOn || it is RailMessage.Overdraw || it is RailMessage.AboveUsual
    }
    Text(
        if (lost) PowerCopy.READING_LOST else PowerCopy.nowLine(power.fresh(now), power.peak),
        modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (lost || tripped) LocalAttention.current else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

private const val TICK_MS = 1_000L
