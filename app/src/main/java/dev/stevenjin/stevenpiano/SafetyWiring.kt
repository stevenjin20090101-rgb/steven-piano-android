// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.app.Application
import android.os.SystemClock
import dev.stevenjin.stevenpiano.AppGraph.Companion.RAIL_AWAIT_MS
import dev.stevenjin.stevenpiano.audio.KeyTestState
import dev.stevenjin.stevenpiano.audio.ListenAnswer
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Played
import dev.stevenjin.stevenpiano.player.RepeatMode
import dev.stevenjin.stevenpiano.player.StartGate
import dev.stevenjin.stevenpiano.power.RailKeeper
import dev.stevenjin.stevenpiano.power.RailPhase
import dev.stevenjin.stevenpiano.power.RailPower
import dev.stevenjin.stevenpiano.safety.DataStoreSafetyStore
import dev.stevenjin.stevenpiano.safety.Deferral
import dev.stevenjin.stevenpiano.safety.SafetyCheck
import dev.stevenjin.stevenpiano.safety.SafetyHost
import dev.stevenjin.stevenpiano.safety.SafetyRunner
import dev.stevenjin.stevenpiano.safety.safetyDataStore
import dev.stevenjin.stevenpiano.ui.PowerCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The safety check's part of [AppGraph] (M61): the check, the start gate it keeps, and what it works with. The graph makes it
 * once and everything else reaches it through the graph's members; what it holds is lazy or asked for, and what starts when
 * stays in [AppGraph.start].
 */
internal class SafetyWiring(private val graph: AppGraph, private val app: Application) {
    private val appScope = graph.appScope

    // ==== v1.26 — M61: the safety check (one block; M60's RailPower is connected here by the integrator) ===============

    /**
     * The safety check (v1.26 — M61; DESIGN.md › v1.26 — M61): after every 6th piece the tablet plays on Steven Piano, the
     * release, the coils and the boards read, the 84 keys swept while the microphone listens, the restart and repower
     * where keys stay silent, and Play locked when they still do. Its record in its own small DataStore.
     *
     * For the integrator, after M60 merges: `repower = { rail.repower() }`, `cutNow = { rail.cutNow() }`,
     * `plugged = { rail.available.value }`, `wantRail = { on -> rail.wanted("safety", on) }` and `awaitRail` (the rail up,
     * its notes no longer held, within 10 s) with M60's `RailPower`; and [keyTestRail] for Test every key.
     */
    val safety: SafetyRunner by lazy {
        SafetyRunner(
            scope = appScope,
            store = DataStoreSafetyStore(app.safetyDataStore, log = LinkLog::warn),
            host = safetyHost,
            listens = { graph.settings.value.safetyCheckListens && graph.remote.microphoneAllowed() },
            repower = { graph.railPower.repower() },
            cutNow = { graph.railPower.cutNow() },
            plugged = { graph.railPower.available.value },
            wantRail = { on ->
                graph.railPower.wanted(SAFETY_RAIL, on)
                // v1.26 — M62: the check's end lets go of its repower's own hold too (60 s from the repower), so the 24 V
                // goes off a second after the check, as after a piece: nothing is sent once it ended.
                if (!on) graph.railPower.wanted(RailKeeper.REPOWER, false)
            },
            awaitRail = { railUpWithin(RAIL_AWAIT_MS) },
            log = LinkLog::warn,
            heardAll = graph.powerWiring::railHeardAll,   // v1.31 — M68
            every = { graph.settings.value.safetyCheckEvery },   // v1.39 — M76: off by default
            repowerRestarts = { graph.railPower.available.value },   // v1.39 — M76: the controller is on the plug too
            cooling = graph::coolLeft,   // v1.40 — M78
        )
    }

    /**
     * Test every key's wait before its first strike (v1.26 — M61), whoever started it (the System page, the panel, the
     * safety check): the 24 V up. The integrator connects M60's `RailPower` here (want the rail, then wait for it, 10 s at
     * most, false when it doesn't come up); until then the rail is taken as up.
     */
    internal val keyTestRail: suspend () -> Boolean = {
        if (!graph.railPower.available.value) {
            true
        } else {
            graph.railPower.wanted(RailKeeper.KEY_TEST, true)   // the key test's own running state keeps or drops it from here
            railUpWithin(RAIL_AWAIT_MS).also { up -> if (!up) graph.railPower.wanted(RailKeeper.KEY_TEST, false) }
        }
    }

    /**
     * The 24 V up and its notes no longer held, within [ms] (v1.26: the safety check's and Test every key's wait before a
     * sweep); true at once without the plug feature, false when it doesn't come up in time. Wants nothing by itself.
     */
    internal suspend fun railUpWithin(ms: Long): Boolean {
        if (!graph.railPower.available.value) return true
        return withTimeoutOrNull(ms) {
            graph.rail.phase.first { it == RailPhase.Up || it == RailPhase.Kept }
            graph.rail.notesHeld.first { held -> !held }
            true
        } ?: false
    }

    /** The player's and the channels' start gate: the safety check's, asked as each start comes (the two never make each other). */
    internal val safetyGate: StartGate by lazy {
        object : StartGate {
            override val open: Boolean get() = safety.open

            override fun admit(pieceId: Long?, start: () -> Unit): Boolean = safety.admit(pieceId, start)
        }
    }

    /** What the safety check works with: the player, Steven Piano's link and console, the key test, the gates. */
    private val safetyHost: SafetyHost by lazy { SafetyCheckHost() }

    private inner class SafetyCheckHost : SafetyHost {
        override val played: Flow<Played> get() = graph.player.played

        override fun deferral(): Deferral? = when {
            graph.coolLeft() != null -> Deferral.COOLING   // v1.40 — M78: a check under way stops too
            graph.player.locked || graph.firmwareUpdater.state.value.busy -> Deferral.UPDATING
            graph.quiet.holds() -> Deferral.QUIET
            !graph.remote.listening.inFront.value -> Deferral.AWAY
            graph.pianoSettings.lock.value.guestLeft(SystemClock.elapsedRealtime()) > 0 -> Deferral.GUEST_PLAY
            // v1.38 — M75: with the power plug the piano is off until the rail wakes it, which the check's own want does.
            !pianoReady() && !graph.pianoWakes() -> Deferral.NO_PIANO
            else -> null
        }

        override fun pianoReady(): Boolean = graph.pianoLink.kind.value == InstrumentKind.StevenPiano && graph.pianoLink.state.value is LinkState.Connected

        override fun playerBusy(): Boolean = graph.player.state.value.let { it.status == PlaybackStatus.Playing || it.loading }

        override fun pausePlayer() {
            if (graph.player.state.value.status == PlaybackStatus.Playing) graph.player.pause()
        }

        override fun release() {
            graph.player.silenceLive()
            graph.player.allKeysOff()
        }

        override fun stopEverything() {
            graph.player.stop()
            graph.player.silenceLive()
            graph.pianoSettings.action(PianoAction.AllKeysOff)   // the piano's own `off`, which goes whatever its lock says
        }

        override suspend fun facts(): Map<String, String>? {
            val read = withTimeoutOrNull(SAFETY_FACTS_WAIT_MS) { graph.pianoSettings.state.first { it !is PianoState.Unknown } }
            if (read is PianoState.Ready) return read.facts
            // v1.27 — M64: none (empty) only for a piano with no console at all, whose facts are left out as before; a console
            // that gave no dump on this connection says nothing of what the piano reports.
            val noConsole = read == PianoState.Unsupported && graph.stevenLink.state.value is LinkState.Connected && graph.stevenLink.console == null
            return if (noConsole) emptyMap() else null
        }

        override fun dumpAgain(): Boolean = graph.pianoSettings.dumpAgain()

        override suspend fun ask(fact: String): String? {
            // v1.26 — M62: the power boards answer only with the 24 V up, which the check wanted at its start. After the
            // 6th piece it is still up; a check that begins with it off (due before the next Play, or run from the lock
            // card) reads them once it is up (10 s at most), not a second after wanting it, which would find them all
            // missing and power the piano off and on for nothing.
            if (fact == SafetyCheck.BOARDS) railUpWithin(RAIL_AWAIT_MS)
            return graph.pianoSettings.ask(fact)
        }

        override suspend fun restart(): String? {
            graph.stevenLink.expectRestart(SAFETY_RESTART_WINDOW_MS)
            return graph.pianoSettings.restart().also { reply ->
                // v1.27 — M64: the drop after `restarting` is the restart asked for, not a dead piano: the 24 V's keeper lets
                // it pass, and brings the rail up again with the piano.
                if (SafetyCheck.restarting(reply)) graph.rail.expectRestart(SAFETY_RESTART_WINDOW_MS)
            }
        }

        override fun startSweep(): String? = (graph.remote.keyTest.start(null) as? ListenAnswer.Refused)?.message

        override val sweep: StateFlow<KeyTestState> get() = graph.remote.keyTest.state

        override fun stopSweep() = graph.remote.keyTest.stop()

        override fun nextPiece(): Long? {
            val queue = graph.player.state.value.queue
            val at = queue.index
            return when {
                at !in queue.ids.indices -> null
                queue.repeat == RepeatMode.ONE -> queue.ids[at]
                at + 1 < queue.ids.size -> queue.ids[at + 1]
                queue.repeat == RepeatMode.ALL -> queue.ids.first()
                else -> null
            }
        }

        override suspend fun titleOf(pieceId: Long): String? = withContext(Dispatchers.IO) { graph.library.summaries(listOf(pieceId))[pieceId]?.title }

        // v1.40 — M78: the quick check's `!i2cfails` and idle draw on a fresh advert, with the plug; a trip is the rail's CUT.
        override suspend fun powerLook(): String? = graph.rail.quickLook()?.let(PowerCopy::message)
    }

    /** From [start]: the safety check reads its record and follows the player. */
    internal fun startSafety() = safety.start()

    // ==== end of the safety check's block ==============================================================================

    private companion object {
        /** The safety check's reason for wanting the 24 V rail ([RailPower.wanted], v1.26). */
        const val SAFETY_RAIL = "safety"

        /** The safety check (v1.26 — M61): the piano's dump at most this long, and the restart's window for the link. */
        const val SAFETY_FACTS_WAIT_MS = 5_000L
        const val SAFETY_RESTART_WINDOW_MS = 60_000L
    }
}
