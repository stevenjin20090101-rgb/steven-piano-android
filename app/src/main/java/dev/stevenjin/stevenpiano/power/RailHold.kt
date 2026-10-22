// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The player as the rail's hold reads it: whether a piece plays, whether it is stopped, and which piece is loaded. */
data class PlayerNow(val playing: Boolean, val stopped: Boolean, val pieceId: Long?)

/**
 * A piece waits for the rail (v1.26 — M60): a piece playing wants the rail ([want], the player's reason), and while the
 * rail comes up its notes may not go ([held], [RailKeeper.notesHeld]). The pause before each piece (2 s by default) usually
 * covers the rail-up; when it doesn't, the piece is paused just before its first note ([MARGIN_US]: a pause inside the
 * pause before a piece holds its start) and resumed as soon as the rail is up, so nothing of it is lost. A Play while it is
 * held is held again; a stop, or another piece, ends the hold. Main thread ([scope]'s); the player's own calls only.
 */
class RailHold(
    private val scope: CoroutineScope,
    private val player: Flow<PlayerNow>,
    private val held: StateFlow<Boolean>,
    /** The piece's position now, µs; below zero during the pause before it. */
    private val positionMicros: () -> Long,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    /** Whether the player wants the rail: a piece playing, or one held for it. */
    private val want: (Boolean) -> Unit,
) {
    private var holding = false
    private var heldPiece: Long? = null
    private var wanting: Boolean? = null
    private var watch: Job? = null

    /** Resumed for the rail, until the player says it plays (the rail stays wanted meanwhile; [RESUME_MS] at most). */
    private var resuming: Job? = null
    private var last: Pair<PlayerNow, Boolean>? = null

    /** Whether a piece is paused now waiting for the rail. */
    val holdingNow: Boolean get() = holding

    fun start() {
        scope.launch { combine(player, held) { now, rail -> now to rail }.collect { (now, rail) -> follow(now, rail) } }
    }

    private fun follow(now: PlayerNow, railHeld: Boolean) {
        last = now to railHeld
        if (holding && (now.stopped || now.pieceId != heldPiece)) holding = false
        if (holding && !railHeld) {
            holding = false
            if (!now.playing) {
                resume()
                resuming?.cancel()
                resuming = scope.launch {
                    delay(RESUME_MS)
                    resuming = null
                    last?.let { (n, r) -> follow(n, r) }
                }
            }
        }
        if (now.playing || now.stopped || now.pieceId != heldPiece) {
            resuming?.cancel()
            resuming = null
        }
        val wanted = now.playing || holding || resuming != null
        if (wanted != wanting) {
            wanting = wanted
            want(wanted)
        }
        when {
            now.playing && railHeld && holding -> pause()   // a Play while held: held again
            now.playing && railHeld -> watch(now.pieceId)
            else -> {
                watch?.cancel()
                watch = null
            }
        }
    }

    /** While the piece plays and the rail is held: pause it just before its first note goes. */
    private fun watch(piece: Long?) {
        if (watch?.isActive == true) return
        watch = scope.launch {
            while (positionMicros() < -MARGIN_US) delay(POLL_MS)
            holding = true
            heldPiece = piece
            pause()
        }
    }

    companion object {
        /** How long before its first note a piece waiting for the rail is paused, µs. */
        const val MARGIN_US = 150_000L

        /** How often the position is looked at while the rail comes up, ms. */
        const val POLL_MS = 40L

        /** How long a piece resumed for the rail keeps it wanted before the player says it plays, ms. */
        const val RESUME_MS = 3_000L
    }
}
