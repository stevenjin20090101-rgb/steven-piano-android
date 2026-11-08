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
 * - **What goes** ([LedRest.wanted]): `ledrest 1` while nothing plays, with Solid colour chosen and the strip in Reactive;
 *   `ledrest 0` as soon as something plays, and whenever Notes only is chosen or the strip leaves Reactive. Always a
 *   temporary write ([PianoSettingsRepository.rest]): it never counts for a save.
 * - **At the connection**: the piano starts every connection at 0 (from boot, or dropped back with the link that set it),
 *   so the right value goes once its first answer shows the fact; a 1 found there is another link's, so 1 goes anyway.
 * - **At most once per change**: a value goes only when the piano holds another and this connection hasn't sent it since
 *   the want last changed, so a refusal is never sent again and again.
 * - **Guest play**: while it holds the piano ([guest]), and for the whole of the next connection after it (the one that
 *   ends it with `lock guest 0`, then cuts the 24 V), the want is 0: the iPad's notes show, and nothing sends 1 over
 *   guest play's own connections.
 *
 * Event-driven only: it follows the piano's state, the choice, what plays and guest play, and runs nothing on a clock.
 */
class LedRestKeeper(
    private val piano: Flow<PianoState>,
    /** The tablet's choice, from its settings. */
    private val choice: Flow<LedRestChoice>,
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

    /**
     * Guest play was on since the last connection began, and didn't end on it: the next connection is guest play's own (the
     * tablet let go of the piano for it, and comes back to end it).
     */
    private var guestSince = false

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(piano, choice.distinctUntilChanged(), playing.distinctUntilChanged(), guest.distinctUntilChanged()) { state, chosen, plays, guesting ->
                Inputs(state, chosen, plays, guesting)
            }.collect(::keep)
        }
    }

    private fun keep(now: Inputs) {
        if (now.guest) guestSince = true
        val ready = now.state as? PianoState.Ready
        if (ready == null || !LedRest.supported(ready.facts)) {
            here = null   // a new connection (or none) starts afresh
            return
        }
        val connection = here ?: Connection(guestOwn = guestSince).also {
            here = it
            guestSince = now.guest
        }
        // Guest play over while this (the tablet's own) connection stayed up, as it does without the power plug: the next
        // connection is the tablet's again.
        if (!now.guest && !connection.guestOwn) guestSince = false
        if (!ready.lock.writable) return   // read only, or the key still being proved: nothing goes until it may
        val mode = ready.values[LedColour.MODE]
        val wanted = LedRest.wanted(true, mode, now.choice, now.playing, now.guest || connection.guestOwn) ?: return
        val holds = ready.values[LedRest.KEY]?.trim()
        if (wanted == connection.sent) return
        if (wanted == LedRest.OFF && holds == LedRest.OFF) {
            connection.sent = wanted   // found held: nothing to send, and a later 1 is a change again
            return
        }
        connection.sent = wanted
        log("The strip between pieces: ${LedRest.KEY} $wanted (${why(now, mode, connection)})")
        send(wanted == LedRest.ON)
    }

    private fun why(now: Inputs, mode: String?, connection: Connection): String = when {
        now.guest || connection.guestOwn -> "guest play"
        mode?.trim() != LedColour.REACTIVE -> "not Reactive"
        now.choice == LedRestChoice.NOTES -> "Notes only"
        now.playing -> "something plays"
        else -> "nothing plays"
    }

    private data class Inputs(val state: PianoState, val choice: LedRestChoice, val playing: Boolean, val guest: Boolean)

    /** One connection's memory: whether it is guest play's own, and the value it last sent (or found held). */
    private class Connection(val guestOwn: Boolean) {
        var sent: String? = null
    }

    private companion object {
        const val TAG = "LedRest"
    }
}
