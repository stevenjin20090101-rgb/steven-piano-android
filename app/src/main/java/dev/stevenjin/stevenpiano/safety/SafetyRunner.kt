// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.safety

import dev.stevenjin.stevenpiano.audio.KeyTestState
import dev.stevenjin.stevenpiano.player.Played
import dev.stevenjin.stevenpiano.player.StartGate
import dev.stevenjin.stevenpiano.ui.ListenCopy
import dev.stevenjin.stevenpiano.ui.SafetyCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Why a safety check can't run now (v1.26 — M61). */
enum class Deferral {
    /** The piano's firmware is being updated (the player is locked). */
    UPDATING,

    /** A quiet time holds and nobody chose Play anyway. */
    QUIET,

    /** The app is not in front: the microphone needs the foreground, and the card must show. */
    AWAY,

    /** Guest play: the piano is another device's for now. */
    GUEST_PLAY,

    /** Steven Piano isn't the instrument, isn't connected, or its lock hasn't accepted this tablet. */
    NO_PIANO,
}

/** What the safety check works with: the player, the piano, the key test (AppGraph's; a stand-in in the tests). Main thread. */
interface SafetyHost {
    /** Each piece that sounded (the player's `played`). */
    val played: Flow<Played>

    /** Why a check can't run now, the first that holds of [Deferral]'s in its order; null when it can. */
    fun deferral(): Deferral?

    /** Steven Piano is the instrument, connected, and its lock has accepted this tablet. */
    fun pianoReady(): Boolean

    /** A piece plays, or loads. */
    fun playerBusy(): Boolean

    /** A piece playing pauses (the check needs the piano to itself). */
    fun pausePlayer()

    /** The player's stop sequence (CC64 = 0, CC123) at once, the Keys screen's keys let go: the release. */
    fun release()

    /** Everything stops: the player (its stop sequence), the Keys screen's keys, and the piano's own `off`. */
    fun stopEverything()

    /** The piano's facts as its dump gave them on this connection (waiting a few seconds for them); none without a console. */
    suspend fun facts(): Map<String, String>

    /** `get !<fact>`, its answer awaited (2 s); null without one. */
    suspend fun ask(fact: String): String?

    /** `expectRestart()` on the link, then the piano's `restart`: its reply, or null. */
    suspend fun restart(): String?

    /** The key test's sweep from the tablet: null when it began, else why not (its words). */
    fun startSweep(): String?

    /** The key test as it goes. */
    val sweep: StateFlow<KeyTestState>

    /** The key test stops (with the player's stop sequence). */
    fun stopSweep()

    /** The piece that plays at the next Play: the one waiting at its start, or the one after the piece now ending. */
    fun nextPiece(): Long?

    /** A piece's title, for the card. */
    suspend fun titleOf(pieceId: Long): String?
}

/**
 * The safety check now, for the card, the System page and the web panel: whether the [store]'s record is [loaded]; a
 * check [running] and how far it is ([check]); the [next] piece's title; how the last check just [ended] (a passed or
 * unfinished one shows on the card for a moment); the [lock] (Play refused until a check passes); a check [pending]
 * before the next Play; the pieces counted since the last check; the [last] check; Play refused while locked, counted
 * ([refused]: the card comes back at each); whether the locked card is put aside ([aside]).
 */
data class SafetyState(
    val loaded: Boolean = false,
    val running: Boolean = false,
    val check: CheckState? = null,
    val next: String? = null,
    val ended: SafetyOutcome? = null,
    val lock: SafetyLock? = null,
    val pending: Boolean = false,
    val count: Int = 0,
    val last: LastCheck? = null,
    val refused: Int = 0,
    val aside: Boolean = false,
) {
    /** The full-screen card shows: a check running, one that just ended, or Play locked (unless put aside). */
    val card: Boolean get() = running || ended != null || (lock != null && !aside)
}

/**
 * The safety check (v1.26 — M61; DESIGN.md › v1.26 — M61): it counts the pieces the tablet plays on Steven Piano (to
 * their end, or stopped after they began: the player's `played`; guest play, an iPad and the Keys tab never pass through
 * it), and right after the 6th since the last check that passed ([EVERY]) it runs [SafetyCheck]'s steps. One that can't
 * run then ([SafetyHost.deferral]: an update, a quiet time, the app not in front, guest play, no piano) is due
 * ([SafetyState.pending]) and runs before the next Play, which waits for it.
 *
 * - **The gate.** The player asks [admit] at every person's start: it goes; or, with a check due and able to run, it
 *   waits while the check runs and goes once it passes; or, while Play is locked, it is refused (the card and the web
 *   panel say why). A start with a check due while the piano can't hear it (not connected, guest play) goes, and the
 *   check waits for the next; one asked while the app is away is refused (the microphone needs the foreground).
 * - **The outcome.** Passed: the count starts again, the card says so for a moment and closes, and a start that waited
 *   goes. A coil on: [cutNow] (the power plug's CUT, wired by the integrator), everything stopped, Play locked. Keys not
 *   heard after the restart and the repower: Play locked. Unfinished: due again before the next Play. Only a check that
 *   passes unlocks Play ([runNow], the card's Run the safety check); the lock and the count survive a restart of the app
 *   ([store]).
 * - **The power plug** (M60's `RailPower`, connected by the integrator): [wantRail] says when the check wants the 24 V up
 *   (from its start to its end, and again before each sweep); [awaitRail] waits for it before each sweep (10 s at most;
 *   false: no key is judged, "the 24 V didn't come up", and the repower follows); [repower] (CUT, 3 s, ARM, the rail-up
 *   CHECK; true when every board answers) and [cutNow]; [plugged] whether a plug is there, for the card's words. Without
 *   them: no-ops, the rail taken as up.
 *
 * [listens]: the switch "Automatic safety check (uses the microphone)" on and Android's permission; off, the check runs
 * without its sweep, so no key can lock Play. Main thread ([scope]'s).
 */
class SafetyRunner(
    private val scope: CoroutineScope,
    private val store: SafetyStore,
    private val host: SafetyHost,
    private val listens: () -> Boolean,
    private val repower: suspend () -> Boolean = { false },
    private val cutNow: suspend () -> Unit = {},
    private val plugged: () -> Boolean = { false },
    private val wantRail: (Boolean) -> Unit = {},
    private val awaitRail: suspend () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : StartGate {
    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    private var record = SafetyRecord()
    private var loaded = false
    private var started = false

    /** Pieces that played before the record was read: counted once it is. */
    private var early = 0
    private var job: Job? = null
    private var shownJob: Job? = null

    /** The start that waits for the check, and the piece it plays (for the card's "Next"). */
    private var waiting: (() -> Unit)? = null
    private var waitingPiece: Long? = null

    override val open: Boolean get() = loaded && record.lock == null && job == null && !record.pending

    /** Reads the record and follows the player from now on. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            record = store.read()
            loaded = true
            if (early > 0) {
                record = record.copy(count = record.count + early)
                early = 0
                save()
            }
            publish()
            log("Safety check: ${record.count} pieces since the last" + (if (record.pending) ", one due" else "") + (if (record.lock != null) ", Play locked" else ""))
            val held = waiting
            waiting = null
            waitingPiece = null
            held?.invoke()   // a start asked for before the record was read: its path asks again
            if (record.count >= EVERY && record.lock == null && !record.pending) due()
        }
        scope.launch { host.played.collect(::onPlayed) }
    }

    override fun admit(pieceId: Long?, start: () -> Unit): Boolean {
        if (!loaded) {
            hold(pieceId, start)
            return false
        }
        val lock = record.lock
        if (lock != null) {
            _state.update { it.copy(refused = it.refused + 1, aside = false) }
            log("Play refused: ${SafetyCopy.locked(lock)}")
            return false
        }
        if (job != null) {
            hold(pieceId, start)
            return false
        }
        if (!record.pending) return true
        return when (host.deferral()) {
            null -> {
                hold(pieceId, start)
                begin()
                false
            }
            // Nothing reaches the piano's coils now: the start goes, and the check waits for the next Play.
            Deferral.NO_PIANO, Deferral.GUEST_PLAY -> true
            // The app away (the microphone needs the foreground), a quiet time, an update: not now.
            else -> false
        }
    }

    /** Why Play is refused on the web panel now (409 `safety`): the lock's words, or a check due while the app is away; else null. */
    fun refusal(): String? {
        record.lock?.let { return SafetyCopy.locked(it) }
        if (loaded && job == null && record.pending && host.deferral() == Deferral.AWAY) return SafetyCopy.deferral(Deferral.AWAY)
        return null
    }

    /**
     * The card's Run the safety check (behind the kiosk PIN in kiosk mode; the web panel can't): a check now, and null; or
     * why it can't run now. Only a check that passes unlocks Play.
     */
    fun runNow(): Deferral? {
        if (job != null) return null
        host.deferral()?.let { return it }
        begin()
        return null
    }

    /** The locked card put aside (Not now, behind the kiosk PIN): a capsule stays; a refused Play brings the card back. */
    fun putAside() {
        if (record.lock != null) _state.update { it.copy(aside = true) }
    }

    /** The locked card shown again (the capsule's Show). */
    fun showCard() = _state.update { it.copy(aside = false) }

    /** A passed or unfinished check's moment on the card ends now (its OK). */
    fun dismiss() {
        shownJob?.cancel()
        shownJob = null
        _state.update { it.copy(ended = null, check = if (it.running) it.check else null) }
    }

    private fun onPlayed(piece: Played) {
        if (!host.pianoReady()) return   // the piano didn't play it (no piano, a MIDI piano, guest play): not counted
        if (!loaded) {
            early++
            return
        }
        record = record.copy(count = record.count + 1)
        save()
        publish()
        log("Safety check: piece ${piece.pieceId} played, ${record.count} since the last check")
        if (record.count >= EVERY && job == null && record.lock == null) due()
    }

    /** The 6th piece has ended: the check now, or due before the next Play. */
    private fun due() {
        val why = host.deferral()
        if (why == null) {
            begin()
            return
        }
        log("Safety check due, waiting for the next Play: ${why.name.lowercase()}")
        record = record.copy(pending = true)
        save()
        publish()
    }

    private fun hold(pieceId: Long?, start: () -> Unit) {
        waiting = start
        waitingPiece = pieceId
        if (job != null && pieceId != null) titleNext(pieceId)
    }

    private fun begin() {
        if (job != null) return
        shownJob?.cancel()
        shownJob = null
        // Under way it is due: a check the app never finished (killed meanwhile) runs before the next Play.
        record = record.copy(pending = true)
        save()
        wantRail(true)
        val next = waitingPiece ?: host.nextPiece()
        _state.update { it.copy(running = true, check = null, ended = null, next = null, aside = false) }
        publish()
        log("Safety check: begins")
        job = scope.launch { run(next) }
    }

    private suspend fun run(next: Long?) {
        if (next != null) titleNext(next)
        var check: CheckState? = null
        val outcome = try {
            conduct { check = it }
        } catch (e: CancellationException) {
            host.stopSweep()
            wantRail(false)
            throw e
        } catch (e: Exception) {
            // Nothing a step does may leave the check hanging: it ends unfinished, and runs before the next Play.
            log("Safety check stopped: ${e.javaClass.simpleName}")
            if (host.sweep.value.running) host.stopSweep()
            SafetyOutcome.Unfinished(Why.STOPPED, null)
        }
        finish(outcome, check)
    }

    /** The check from its start to its outcome; [seen] gets each state as it changes (the card shows it too). */
    private suspend fun conduct(seen: (CheckState) -> Unit): SafetyOutcome {
        host.pausePlayer()
        interruption()?.let { return SafetyOutcome.Unfinished(Why.STOPPED, SafetyCopy.interrupted(it)) }
        // The piano connected and its lock deciding, or (with the power plug) the 24 V coming up: a while at most.
        if (!host.pianoReady() && withTimeoutOrNull(PIANO_WAIT_MS) { while (!host.pianoReady()) delay(TICK_MS) } == null) {
            return SafetyOutcome.Unfinished(Why.NO_PIANO, SafetyCopy.NOT_CONNECTED)
        }
        val facts = host.facts()
        var step = SafetyCheck.start(
            listens = listens(),
            coils = SafetyCheck.ACTIVE in facts,
            boards = SafetyCheck.BOARDS in facts,
            restart = facts[SafetyCheck.RESTART_FACT]?.trim() == "1",
        )
        while (true) {
            show(step.state)
            seen(step.state)
            val command = step.command
            if (command is Command.Finish) return command.outcome
            val event = interruption()?.let { Event.Interrupted(SafetyCopy.interrupted(it)) } ?: perform(command, step.state, seen)
            step = SafetyCheck.on(step.state, event)
        }
    }

    private suspend fun perform(command: Command, state: CheckState, seen: (CheckState) -> Unit): Event = when (command) {
        Command.Release -> {
            host.release()
            delay(RELEASE_MS)
            Event.Released
        }
        is Command.Ask -> Event.Answer(host.ask(command.fact))
        Command.Sweep -> {
            // The keys sound only with the 24 V up: with the power plug it comes up a second or so after it is wanted.
            wantRail(true)
            if (attempt(false) { awaitRail() }) sweep(state, seen) else Event.RailDown
        }
        Command.Restart -> Event.Restarted(host.restart())
        Command.AwaitBack -> Event.Back(awaitBack(state.boards))
        Command.Repower -> Event.Repowered(attempt(false) { repower() }, plugged())
        is Command.Finish -> error("A finished check does nothing more")
    }

    /** The key test's sweep, its keys on the card as they go. */
    private suspend fun sweep(state: CheckState, seen: (CheckState) -> Unit): Event {
        // What follows the piece that ended loads to wait at its start: the key test would take it for one playing.
        withTimeoutOrNull(SETTLE_MS) { while (host.playerBusy()) delay(TICK_MS) }
        host.startSweep()?.let { return Event.SweepStopped(it) }
        try {
            var shown = -1
            val end = host.sweep.first { test ->
                if (test.running && test.done != shown) {
                    shown = test.done
                    val struck = SafetyCheck.struck(state, test.done, test.total)
                    show(struck)
                    seen(struck)
                }
                !test.running
            }
            val results = end.results
            return when {
                results != null -> Event.Swept(results)
                // The key test's own wait for the 24 V (its `before`) found it down: no key struck, as with [awaitRail].
                end.stopped == ListenCopy.STOPPED_RAIL -> Event.RailDown
                else -> Event.SweepStopped(end.stopped ?: SafetyCopy.STOPPED)
            }
        } finally {
            if (host.sweep.value.running) host.stopSweep()   // stopped meanwhile: the test ends, with the stop sequence
        }
    }

    /**
     * After `restarting` the piano lets go of the link at once (about 200 ms) and comes back a few seconds later: the
     * link gone, then connected again with every board answering ([boards]: where the dump reports them); 60 s at most.
     */
    private suspend fun awaitBack(boards: Boolean): Boolean = withTimeoutOrNull(BACK_MS) {
        withTimeoutOrNull(DROP_MS) { while (host.pianoReady()) delay(TICK_MS) }
        while (true) {
            if (interruption() != null) return@withTimeoutOrNull false
            if (host.pianoReady()) {
                if (!boards) return@withTimeoutOrNull true
                val read = SafetyCheck.boards(host.ask(SafetyCheck.BOARDS))
                if (read != null && read.first == read.second) return@withTimeoutOrNull true
            }
            delay(BACK_TICK_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        false
    } ?: false

    /** Why a check under way must stop now (not the piano's connection: the steps see that themselves). */
    private fun interruption(): Deferral? = host.deferral()?.takeIf { it != Deferral.NO_PIANO }

    private suspend fun finish(outcome: SafetyOutcome, check: CheckState?) {
        val at = clock()
        val boards = check?.line(SafetyStep.BOARDS)?.takeIf { it.of > 0 }
        // Which recovery was done (v1.27 — M63), from the card's own marks: the words say only what really happened.
        val restarted = check?.line(SafetyStep.RESTART)?.mark == Mark.DONE
        val repowered = check?.line(SafetyStep.REPOWER)?.mark == Mark.DONE
        when (outcome) {
            is SafetyOutcome.Passed -> record = record.copy(
                count = 0,
                pending = false,
                lock = null,
                last = LastCheck(
                    at, passed = true, keys = outcome.keys, boardsOk = boards?.done, boardsOf = boards?.of, recovered = outcome.recovered,
                    restarted = restarted, repowered = repowered,
                ),
            )
            SafetyOutcome.CoilOn -> {
                log("Safety check failed: a coil is still on; the 24 V is cut and everything stops")
                attempt(Unit) { cutNow() }
                host.stopEverything()
                record = record.copy(pending = false, lock = SafetyLock.CoilOn, last = LastCheck(at, passed = false, coilOn = true))
            }
            is SafetyOutcome.KeysSilent -> {
                log("Safety check failed: ${SafetyCopy.names(outcome.keys)} didn't sound; Play is locked")
                record = record.copy(
                    pending = false,
                    lock = SafetyLock.KeysSilent(outcome.keys),
                    last = LastCheck(
                        at, passed = false, keys = check?.lines?.lastOrNull { it.step == SafetyStep.SWEEP_AGAIN || it.step == SafetyStep.SWEEP }?.done, silent = outcome.keys,
                        boardsOk = boards?.done, boardsOf = boards?.of, recovered = true, restarted = restarted, repowered = repowered,
                    ),
                )
            }
            is SafetyOutcome.Unfinished -> {
                log("Safety check unfinished (${outcome.why.name.lowercase()}): it runs before the next piece")
                record = record.copy(pending = true)
            }
        }
        if (outcome is SafetyOutcome.Passed) log("Safety check passed")
        wantRail(false)
        job = null
        save()
        val start = waiting.takeIf { outcome is SafetyOutcome.Passed }
        waiting = null
        waitingPiece = null
        val moment = outcome is SafetyOutcome.Passed || outcome is SafetyOutcome.Unfinished
        _state.update { it.copy(running = false, check = check, ended = outcome.takeIf { moment }, next = it.next.takeIf { moment }) }
        publish()
        if (moment) {
            shownJob = scope.launch {
                delay(if (outcome is SafetyOutcome.Passed) PASSED_SHOWN_MS else UNFINISHED_SHOWN_MS)
                _state.update { it.copy(ended = null, check = if (it.running) it.check else null) }
            }
        }
        start?.invoke()   // the Play that waited for the check
    }

    private fun titleNext(pieceId: Long) {
        scope.launch {
            val title = attempt(null) { host.titleOf(pieceId) } ?: return@launch
            _state.update { if (it.running) it.copy(next = title) else it }
        }
    }

    /** [work], or [otherwise] when it fails: the power plug's calls and the library's are never the check's undoing. */
    private suspend fun <T> attempt(otherwise: T, work: suspend () -> T): T = try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Safety check: ${e.javaClass.simpleName} (carried on)")
        otherwise
    }

    private fun show(check: CheckState) = _state.update { it.copy(check = check) }

    private fun publish() = _state.update {
        it.copy(loaded = loaded, lock = record.lock, pending = record.pending, count = record.count, last = record.last, aside = it.aside && record.lock != null)
    }

    private fun save() {
        val snapshot = record
        scope.launch { store.write(snapshot) }
    }

    companion object {
        /** A check after every 6th piece. */
        const val EVERY = 6

        /** The release, then this before the coils are read. */
        const val RELEASE_MS = 1_000L

        /** The piano connected (its lock deciding, the 24 V coming up) at most this long after a check begins. */
        const val PIANO_WAIT_MS = 15_000L

        /** A piece loading to wait at its start, at most this long before the sweep. */
        const val SETTLE_MS = 5_000L

        /** After `restart`: the link gone within this, and the piano back within [BACK_MS]. */
        const val DROP_MS = 3_000L
        const val BACK_MS = 60_000L
        const val BACK_TICK_MS = 1_000L
        const val TICK_MS = 100L

        /** A check that passed, or couldn't finish, stays on the card this long. */
        const val PASSED_SHOWN_MS = 3_000L
        const val UNFINISHED_SHOWN_MS = 8_000L
    }
}
