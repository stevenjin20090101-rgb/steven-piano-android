// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.piano.BoardAddress
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.ui.BoardAddressCopy
import dev.stevenjin.stevenpiano.ui.KioskGateSheet
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.rememberKioskGate
import kotlinx.coroutines.launch

/**
 * Under the power boards (M85, piano firmware 2.1.6): a board moved to another address on this piano, as a tick that's
 * on (untick: back to its own address), and, when [BoardAddress.suggest] finds one, a board answering at the wrong
 * address, as a tick that's off. Each says it belongs to this piano alone: the piano keeps it, not the tablet. Nothing on
 * a piano without `!boardaddrproto=1`. A tick asks for the kiosk PIN while the settings are locked, sends its one line,
 * waits for the piano's answer, then reads the piano again; a refusal shows in the piano's words.
 */
@Composable
internal fun BoardAddressRows(ready: PianoState.Ready?) {
    if (ready == null || !BoardAddress.supported(ready.facts)) return
    val repo = LocalContext.current.graph.pianoSettings
    val scope = rememberCoroutineScope()
    val gate = rememberKioskGate()
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val addresses = BoardAddress.addresses(ready.values[BoardAddress.KEY])
    val writable = ready.lock.writable && !busy

    fun send(line: String) = gate.run {
        busy = true
        said = null
        scope.launch {
            val reply = repo.converse(line)
            said = when (val read = reply?.let(BoardAddress::read)) {
                null -> BoardAddressCopy.NO_ANSWER
                is BoardAddress.Reply.Refused -> read.words
                is BoardAddress.Reply.Done -> if (read.answering) null else BoardAddressCopy.NOTHING_THERE
            }
            repo.reread()
            busy = false
        }
    }

    for ((board, address) in BoardAddress.moved(addresses)) {
        SwitchRow(
            label = BoardAddressCopy.moved(board, address),
            checked = true,
            onChange = { on -> if (!on) send(BoardAddress.reset(board)) },
            enabled = writable,
            note = BoardAddressCopy.movedNote(board),
            locked = gate.locked,
        )
        HairlineDivider(startInset = 16.dp)
    }
    val suggestion = addresses?.let {
        BoardAddress.suggest(BoardAddress.missing(ready.facts["boards"]), BoardAddress.strays(ready.facts[BoardAddress.STRAYS]), it)
    }
    if (suggestion != null && addresses != null) {
        val (board, address) = suggestion
        SwitchRow(
            label = BoardAddressCopy.offer(board, address, lookingAt = addresses[board]),
            checked = false,
            // Back at its own address (the switch mended): put the piano back to it, rather than a move that equals it.
            onChange = { on ->
                if (on) send(if (address == BoardAddress.default(board)) BoardAddress.reset(board) else BoardAddress.move(board, address))
            },
            enabled = writable,
            note = BoardAddressCopy.OFFER_NOTE,
            locked = gate.locked,
        )
        HairlineDivider(startInset = 16.dp)
    }
    said?.let {
        Text(
            it,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    KioskGateSheet(gate)
}
