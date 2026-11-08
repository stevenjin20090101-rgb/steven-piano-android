// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The strip between pieces kept as the tablet chose it (M81), on a piano that says `!ledrestproto=1` and lets this tablet
 * write: nothing at all on any other, so an older piano behaves as it always has.
 *
 * - **What goes** ([LedRest.wanted]): `ledrest 1` while nothing plays, with Solid colour chosen, the strip in Reactive and
 *   its colour Album art or Custom; `ledrest 0` as soon as something plays, and whenever Notes only or Palette is chosen or
 *   the strip leaves Reactive. Always a
 *   temporary write ([PianoSettingsRepository.rest]): it never counts for a save.
 * - **At the connection**: the piano starts every connection at 0 (from boot, or dropped back with the link that set it),
 *   so the right value goes once its first answer shows the fact; a 1 found there is another link's, so 1 goes anyway.
 * - **At most once per change**: a value goes only when the piano holds another and this connection hasn't sent it since
 *   the want last changed, so a refusal is never sent again and again.
 * - **Guest play**: while it holds the piano ([guest]: the tablet let go of it, or the piano's lock says so) the want is 0,
 *   and the firmware drops a 1 with the tablet's link anyway: the iPad's notes show. Once it is over (the tablet back, the
 *   lock at `guest 0`), the tablet's want holds again: with the 24 V kept on within the On hours (v1.46), that connection
 *   may last the rest of the day.
 *
 * Event-driven only: it follows the piano's state, the choice, what plays and guest play, and runs nothing on a clock.
 */
class LedRestKeeper(
    private val piano: Flow<PianoState>,
    /** The tablet's choice, from its settings. */
    private val choice: Flow<LedRestChoice>,
    /** The tablet's colour choice (M80), from its settings: Palette gives no colour to glow in. */
    private val colour: Flow<LedColourSource>,
    /** Something plays notes on the piano now: a piece (paused too), the Keys tab, Test every key, the safety check's sweep. */
    private val playing: Flow<Boolean>,
    /** Guest play holds the piano: the piano's lock says so, or the tablet let go of the piano for it. */
    private val guest: Flow<Boolean>,
    /** Sends `ledrest` [LedRest.ON] (true) or [LedRest.OFF], as a temporary write. */
    private val send: (Boolean) -> Unit,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /** This connection's memory; null before the piano's first answer with the fact on it. */
    private var here: Connection? = null

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                piano,
                choice.distinctUntilChanged(),
                colour.distinctUntilChanged(),
                playing.distinctUntilChanged(),
                guest.distinctUntilChanged(),
            ) { state, chosen, from, plays, guesting -> Inputs(state, chosen, from, plays, guesting) }
                .collect(::keep)
        }
    }

    private fun keep(now: Inputs) {
        val ready = now.state as? PianoState.Ready
        if (ready == null || !LedRest.supported(ready.facts)) {
            here = null   // a new connection (or none) starts afresh
            return
        }
        val connection = here ?: Connection().also { here = it }
        if (!ready.lock.writable) return   // read only, or the key still being proved: nothing goes until it may
        val mode = ready.values[LedColour.MODE]
        val wanted = LedRest.wanted(true, mode, now.choice, now.colour, now.playing, now.guest) ?: return
        val holds = ready.values[LedRest.KEY]?.trim()
        if (wanted == connection.sent) return
        if (wanted == LedRest.OFF && holds == LedRest.OFF) {
            connection.sent = wanted   // found held: nothing to send, and a later 1 is a change again
            return
        }
        connection.sent = wanted
        log("The strip between pieces: ${LedRest.KEY} $wanted (${why(now, mode)})")
        send(wanted == LedRest.ON)
    }

    private fun why(now: Inputs, mode: String?): String = when {
        now.guest -> "guest play"
        mode?.trim() != LedColour.REACTIVE -> "not Reactive"
        now.choice == LedRestChoice.NOTES -> "Notes only"
        now.colour == LedColourSource.PALETTE -> "Palette"
        now.playing -> "something plays"
        else -> "nothing plays"
    }

    private data class Inputs(
        val state: PianoState,
        val choice: LedRestChoice,
        val colour: LedColourSource,
        val playing: Boolean,
        val guest: Boolean,
    )

    /** One connection's memory: the value it last sent (or found held). */
    private class Connection {
        var sent: String? = null
    }

    private companion object {
        const val TAG = "LedRest"
    }
}
