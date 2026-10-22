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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** ARM, CUT and the switch's status, as the keeper asks them of the plug ([ShellyRail] over Bluetooth; a fake in tests). */
interface RailPlug {
    /** ARM: `Switch.Set {id:0, on:true, toggle_after:[seconds]}`; true when the plug took it. */
    suspend fun arm(seconds: Int): Boolean

    /** CUT: `Switch.Set {id:0, on:false}`; true when the plug took it. */
    suspend fun cut(): Boolean

    /** `Switch.GetStatus {id:0}`; null when the plug can't be asked. */
    suspend fun status(): PlugStatus?
}

/**
 * The plug's switch as `Switch.GetStatus {id:0}` says: [output] on, the seconds its timer was set for ([timerS], null when
 * none runs), and the 24 V supply's draw in watts ([watts], `apower`: what proves the rail is really up).
 */
data class PlugStatus(val output: Boolean, val timerS: Double? = null, val watts: Double? = null)

/** The piano's link as guest play needs it (DESIGN.md › v1.26 — M60): let go, connect again, end guest play on the piano. */
interface GuestLink {
    /** The tablet lets go of the piano, so another device (the iPad) can connect: the piano takes one at a time. */
    fun letGo()

    /** Connects to the piano again: true once connected and accepted (15 s at most). */
    suspend fun reconnect(): Boolean

    /** `lock guest 0`, from the connection [reconnect] made. */
    fun endOnPiano()

    companion object {
        /** No piano to let go or reconnect (tests). */
        val None: GuestLink = object : GuestLink {
            override fun letGo() = Unit

            override suspend fun reconnect(): Boolean = false

            override fun endOnPiano() = Unit
        }
    }
}

/** Where the rail stands (DESIGN.md › v1.26 — M60). */
enum class RailPhase {
    /** Off: nothing wants it (or it is left to the plug's timer). */
    Off,

    /** Coming up: a good CHECK, ARM, then the boards awaited for the baseline. Notes wait. */
    Waking,

    /** Up, kept by the CHECK-then-ARM every 10 s. */
    Up,

    /** Up without the CHECK: a firmware update until its outcome (3 min at most), or guest play this tablet started. */
    Kept,

    /** The stop sequence went: CUT a second later, unless something wants the rail again first. */
    Stopping,
}

/** What the tablet says about the rail (the words: `ui/PowerCopy.kt`). */
sealed interface RailMessage {
    /** "Waking the piano…" while the rail comes up. */
    data object Waking : RailMessage

    /** "24 V off": calm, for a few seconds after the rail went off as it should. */
    data object Off : RailMessage

    /** "The piano stopped answering — 24 V cut". */
    data object PianoSilent : RailMessage

    /** "Lost the power plug": ARM failed twice in a row. */
    data object PlugLost : RailMessage

    /** "Only [answered] of [total] power boards answered" (a trunk fault), and "— 24 V cut" when [cut]. */
    data class TrunkFault(val answered: Int, val total: Int, val cut: Boolean) : RailMessage

    /** Whether it needs attention (amber, until dismissed or the next rail-up) rather than passing by itself. */
    val attention: Boolean get() = this !is Waking && this !is Off
}

/**
 * The tablet as the 24 V rail's dead-man's switch (v1.26 — M60; `firmware/docs/SMART_PLUG.md`, "The app's routine"). The
 * solenoid supply is on a smart plug that switches itself off 30 s after the last ARM; this keeps the rail up exactly while
 * something wants it (the reasons: a piece playing, the Keys tab, Test every key, the safety check, a firmware update,
 * guest play) and the piano answers with its boards:
 *
 * - **Rail-up:** a good CHECK (the piano's `get boards` answered within 2 s), ARM, then the CHECK every 500 ms until every
 *   board reads OK, 10 s at most. The boards that answered are the **baseline**; fewer than seven is a trunk fault, said;
 *   none ends it. Notes wait meanwhile ([notesHeld], which [RailGate] applies). Boards that answer with the rail off (the
 *   trunk fed from the controller) prove nothing: then the wait is a fixed 1.5 s after ARM.
 * - **While wanted:** every 10 s the CHECK, then ARM only when the piano answered and every baseline board still reads OK.
 *   **Nothing ARMs without a good CHECK just before it**, but the two exceptions: a firmware update from BEGIN until its
 *   outcome (3 minutes at most) and guest play this tablet started (60 minutes at most), which ARM every 10 s unchecked.
 *   A failed CHECK or ARM is tried again 2 s later. Two failed CHECKs in a row stop the music, CUT and say "The piano
 *   stopped answering — 24 V cut" (or which boards went); two failed ARMs stop it and say "Lost the power plug".
 * - **Stop:** when nothing wants the rail, the stop sequence (CC64 = 0, CC123), a second, then CUT; "24 V off" for a few
 *   seconds. A want back within that second keeps the rail up.
 * - **A failure ends what wanted the rail:** the next Play, key or start brings it up again, through a fresh CHECK.
 * - **Without the plug** ([available] false: none paired, or its switch off) nothing here calls the plug or the piano, and
 *   [notesHeld] stays false: the app behaves exactly as before.
 *
 * Everything runs on [scope]'s thread (the main thread in the app); its inputs are called there too. [now] is a monotonic
 * clock in ms (`SystemClock.elapsedRealtime`; the test scheduler's in tests), the one [delay] waits on.
 */
class RailKeeper(
    private val scope: CoroutineScope,
    private val plug: RailPlug,
    /** The CHECK: the piano's boards from `get boards`, or null when it didn't answer within 2 s (or isn't connected). */
    private val check: suspend () -> Boards?,
    /**
     * Whether Steven Piano's own link is connected now: a CHECK can be asked. Without it the rail waits (but for the
     * update and guest play, which need none), and notes aren't held: they go nowhere anyway, as before the plug.
     */
    private val connected: StateFlow<Boolean>,
    /** The stop sequence (CC64 = 0, CC123) to the piano, written before it returns (300 ms at most). */
    private val stopSequence: suspend () -> Unit,
    /** Stops what plays: the piece, Test every key, the Keys tab's keys (a failure, or a cut). */
    private val stopPlaying: () -> Unit,
    override val available: StateFlow<Boolean>,
    private val now: () -> Long,
    private val guest: GuestLink = GuestLink.None,
    private val log: (String) -> Unit = {},
) : RailPower {
    private val context = scope.coroutineContext.minusKey(Job)

    private val _phase = MutableStateFlow(RailPhase.Off)

    /** Where the rail stands. */
    val phase: StateFlow<RailPhase> = _phase.asStateFlow()

    private val _held = MutableStateFlow(false)

    /**
     * Notes wait ([RailGate]: only what releases goes): the plug switches the rail, and it isn't up with its baseline, or
     * kept; with no piano connected and the rail off, nothing is held (it would go nowhere anyway).
     */
    val notesHeld: StateFlow<Boolean> = _held.asStateFlow()

    private val _message = MutableStateFlow<RailMessage?>(null)

    /** What the tablet says now ([RailMessage]); null for nothing. */
    val message: StateFlow<RailMessage?> = _message.asStateFlow()

    private val _boards = MutableStateFlow<Boards?>(null)

    /** The last good CHECK's boards; null before one. */
    val boards: StateFlow<Boards?> = _boards.asStateFlow()

    private val _baseline = MutableStateFlow<Set<Int>?>(null)

    /** This rail-up's baseline: the boards that answered once the rail was up; null while it isn't. */
    val baseline: StateFlow<Set<Int>?> = _baseline.asStateFlow()

    private val _armedAt = MutableStateFlow<Long?>(null)

    /** When the plug last took an ARM ([now]'s clock); null after a CUT it took. The plug's timer runs 30 s from it. */
    val armedAt: StateFlow<Long?> = _armedAt.asStateFlow()

    private val _guestUntil = MutableStateFlow<Long?>(null)

    /** When guest play this tablet started ends ([now]'s clock); null with none. */
    val guestUntil: StateFlow<Long?> = _guestUntil.asStateFlow()

    private val _welded = MutableStateFlow(false)

    /**
     * The supply still draws after a CUT the plug took: a relay stuck closed. `apower` is the proof (SMART_PLUG.md › rule 9;
     * `output` is only the commanded state, which a welded contact still reads as off): above half the supply's idle draw
     * with the piano silent, at least [WELDED_WATTS] ([weldedAbove]).
     */
    val welded: StateFlow<Boolean> = _welded.asStateFlow()

    /**
     * The supply's draw with the piano silent (v1.26 — M62), read at each rail-up once its boards are up and before the
     * first note: what a CUT is weighed against. Null before a reading.
     */
    private var idleWatts: Double? = null

    /** One want: since when ([seq] orders them), until when it lasts, and until when it may ARM without a CHECK. */
    private class Want(val seq: Long, val until: Long, val uncheckedUntil: Long)

    private val wants = HashMap<String, Want>()
    private var nextSeq = 1L

    /** Wants up to this one were ended by a failure or a cut: what wants the rail again must say so anew. */
    private var consumedThrough = 0L

    /** Bumped at every input, so a wait looks again. */
    private val changes = MutableStateFlow(0L)

    private var keysShown = false
    private var needsBaseline = false
    private var failedChecks = 0
    private var failedArms = 0
    private var loop: Job? = null
    private var guestJob: Job? = null
    private var messageJob: Job? = null
    private var weldJob: Job? = null
    private val commands = Mutex()

    /** Starts following the wants (once). */
    fun start() {
        if (loop != null) return
        scope.launch {
            available.collect {
                refreshHeld()
                changed()
            }
        }
        scope.launch {
            connected.collect {
                refreshHeld()
                changed()
            }
        }
        loop = scope.launch { run() }
    }

    // ---- The inputs (main thread) ------------------------------------------------------------------------------

    override fun wanted(reason: String, on: Boolean) {
        if (on) put(reason) else remove(reason)
    }

    /** The Keys tab came on screen ([on]) or left it: while on it wants the rail, until 10 minutes pass without a key. */
    fun keysOnScreen(on: Boolean) {
        if (on == keysShown) return
        keysShown = on
        if (on) put(KEYS, until = now() + KEYS_IDLE_MS) else remove(KEYS)
    }

    /** A key on the Keys tab (the screen's or a keyboard's through Live): the idle cut starts over, and a rail cut for idling comes up. */
    fun keyPressed() {
        if (keysShown) put(KEYS, until = now() + KEYS_IDLE_MS)
    }

    /**
     * A firmware update runs ([on]: from BEGIN) or has its outcome: from BEGIN the rail is armed every 10 s without the
     * CHECK, 3 minutes at most (the piano restarts and can't answer; the new image confirms itself only with a board
     * online); after that, until the outcome, the CHECK rules again.
     */
    fun updateRunning(on: Boolean) {
        val running = wants[UPDATE]?.let { it.seq > consumedThrough } == true
        if (on && !running) put(UPDATE, unchecked = now() + UPDATE_CAP_MS) else if (!on && UPDATE in wants) remove(UPDATE)
    }

    /**
     * Guest play this tablet started, for [minutes] (60 at most while the plug switches the rail): armed without the CHECK
     * until it ends, and the tablet lets go of the piano so another device can connect. At its end (or [endGuest]) the
     * tablet connects again, ends it on the piano, sends the stop sequence and cuts.
     */
    fun startGuest(minutes: Int) {
        if (!available.value) return
        val until = now() + minutes.coerceIn(1, GUEST_MAX_MIN) * MINUTE_MS
        put(GUEST, until = until, unchecked = until)
        _guestUntil.value = until
        watchGuest(until)
        guest.letGo()
    }

    /** Guest play this tablet started is still running after the app started again: until [until] ([now]'s clock). */
    fun restoreGuest(until: Long) {
        if (until <= now() || GUEST in wants) return
        put(GUEST, until = until, unchecked = until)
        _guestUntil.value = until
        watchGuest(until)
    }

    /** The message is read (its OK): it goes. */
    fun dismissMessage() {
        _message.value = null
    }

    // ---- RailPower ------------------------------------------------------------------------------------------------

    override suspend fun repower(): Boolean {
        if (!available.value) return false
        return command {
            val previous = _baseline.value
            stopSequence()
            cutRail()
            _baseline.value = null
            setPhase(RailPhase.Off)
            delay(REPOWER_OFF_MS)
            put(REPOWER, until = now() + REPOWER_HOLD_MS)
            railUp() && _boards.value?.holds(previous ?: (0 until Boards.TOTAL).toSet()) == true
        }
    }

    override suspend fun cutNow() {
        if (!available.value) return
        command {
            log("Cut now: the stop sequence, then CUT")
            end()
            setPhase(RailPhase.Off)
        }
    }

    /**
     * End guest play this tablet started (its **End guest play**): connect to the piano again, `lock guest 0` there, the
     * stop sequence, then CUT a second later (unless something else wants the rail, which then keeps it under the CHECK).
     */
    suspend fun endGuest() {
        guestJob?.cancel()
        guestEnds()
    }

    /** The switch going off, or the plug being forgotten: what plays stops, the stop sequence, CUT (only when the rail may be up). */
    suspend fun release() {
        if (!available.value) return
        command {
            if (_phase.value != RailPhase.Off || _armedAt.value != null) end()
            setPhase(RailPhase.Off)
        }
    }

    // ---- The routine ----------------------------------------------------------------------------------------------

    private suspend fun run() {
        while (true) {
            when (_phase.value) {
                RailPhase.Up, RailPhase.Kept -> keep()
                else -> {
                    setPhase(RailPhase.Off)
                    // The CHECK needs the piano's link: without it, what wants the rail waits for it (the update and
                    // guest play need none).
                    waitFor { wantedNow() && (uncheckedNow() || connected.value) }
                    session()
                }
            }
        }
    }

    /** The rail comes up: unchecked for the update or guest play, else the rail-up with its CHECK. [keep] follows. */
    private suspend fun session() {
        failedChecks = 0
        failedArms = 0
        if (!uncheckedNow()) {
            railUp()
            return
        }
        setPhase(RailPhase.Kept)
        needsBaseline = true
        while (!arm()) {
            if (++failedArms >= 2) {
                fail(RailMessage.PlugLost)
                return
            }
            if (waitFor(now() + RETRY_MS) { !wantedNow() }) return
        }
        failedArms = 0
    }

    /**
     * Rail-up: the CHECK before ARM, ARM, then the CHECK every 500 ms until every board reads OK, 10 s at most; true once
     * up with its baseline. False when it ended otherwise: failed (said), or no longer wanted (the rail off again).
     */
    private suspend fun railUp(): Boolean {
        val mayBeUp = mayBeUp()
        if (!connected.value) return quietly(mayBeUp)   // gone meanwhile: the loop waits for it again
        setPhase(RailPhase.Waking)
        _message.value = RailMessage.Waking
        if (!wantedNow()) return quietly(mayBeUp)
        // The CHECK before ARM: never ARM without a good one. Two unanswered in a row, or two refused ARMs, end it.
        var before: Boards? = null
        var misses = 0
        var refused = 0
        while (before == null) {
            val read = check()
            if (!wantedNow()) return quietly(mayBeUp)
            if (read == null) {
                if (++misses >= 2) return fail(RailMessage.PianoSilent)
                if (waitFor(now() + RAIL_UP_EVERY_MS) { !wantedNow() }) return quietly(mayBeUp)
                continue
            }
            misses = 0
            _boards.value = read
            if (arm()) {
                before = read
            } else {
                if (++refused >= 2) return fail(RailMessage.PlugLost)
                if (waitFor(now() + RETRY_MS) { !wantedNow() }) return quietly(true)
            }
        }
        val armedAt = _armedAt.value ?: now()
        val baseline: Set<Int> = when {
            // Every board answered with the rail off: the trunk side is fed from the controller, so the boards prove
            // nothing; the supply gets a fixed 1.5 s instead (SMART_PLUG.md › rule 2). (With the rail perhaps still up
            // from an ARM within 30 s, the first CHECK below finds them all and the wait is 500 ms.)
            !mayBeUp && before.all -> {
                if (waitFor(armedAt + CONTROLLER_FED_MS) { !wantedNow() }) return stopRail()
                val after = checkTwice() ?: return if (wantedNow()) fail(RailMessage.PianoSilent) else stopRail()
                after.okSet
            }
            else -> {
                var last: Boards? = null
                var step = 1
                misses = 0
                while (true) {
                    val due = armedAt + step++ * RAIL_UP_EVERY_MS
                    if (due > armedAt + RAIL_UP_MS) break
                    if (waitFor(due) { !wantedNow() }) return stopRail()
                    val read = check()
                    if (read == null) {
                        if (++misses >= 2) return fail(RailMessage.PianoSilent)
                        continue
                    }
                    misses = 0
                    last = read
                    _boards.value = read
                    if (read.all) break
                }
                last?.okSet ?: emptySet()
            }
        }
        if (baseline.isEmpty()) return fail(RailMessage.TrunkFault(0, before.total, cut = true))
        // The supply's idle draw, its boards up and the piano silent: notes are still held (v1.26 — M62).
        plug.status()?.watts?.takeIf { it.isFinite() && it >= 0.0 }?.let { idleWatts = it }
        _baseline.value = baseline
        needsBaseline = false
        val total = _boards.value?.total ?: Boards.TOTAL
        _message.value = if (baseline.size < total) RailMessage.TrunkFault(baseline.size, total, cut = false) else null
        log("Rail up: ${baseline.size} of $total boards")
        setPhase(RailPhase.Up)
        return true
    }

    /**
     * While wanted: every 10 s after the last ARM, the CHECK, then ARM only on a good one (unchecked for the update or guest
     * play); a failure is tried again 2 s later, and two in a row end it. Returns when the rail went off, or came back on
     * after a stop with something wanting it again ([run] dispatches on the phase).
     */
    private suspend fun keep() {
        var next = (_armedAt.value ?: now()) + KEEP_EVERY_MS
        while (true) {
            val stop = waitFor(next) { !wantedNow() }
            if (!available.value) {
                abandon()
                return
            }
            if (stop) {
                stopRail()
                return
            }
            if (uncheckedNow()) {
                setPhase(RailPhase.Kept)
                needsBaseline = true
                if (arm()) {
                    failedArms = 0
                    next = (_armedAt.value ?: now()) + KEEP_EVERY_MS
                } else {
                    if (++failedArms >= 2) {
                        fail(RailMessage.PlugLost)
                        return
                    }
                    next = now() + RETRY_MS
                }
                continue
            }
            val read = check()
            if (!wantedNow() || !available.value) {
                next = now()
                continue
            }
            val baseline = _baseline.value
            val good = read != null && if (needsBaseline || baseline == null) read.answered > 0 else read.holds(baseline)
            if (!good) {
                log(if (read == null) "CHECK: no answer" else "CHECK: ${read.answered} of ${read.total} boards")
                if (++failedChecks >= 2) {
                    fail(if (read == null) RailMessage.PianoSilent else RailMessage.TrunkFault(read.answered, read.total, cut = true))
                    return
                }
                next = now() + RETRY_MS
                continue
            }
            failedChecks = 0
            _boards.value = read
            if (needsBaseline || baseline == null) {
                // After the update or guest play: this CHECK is the baseline from here.
                _baseline.value = read.okSet
                needsBaseline = false
                if (read.answered < read.total) _message.value = RailMessage.TrunkFault(read.answered, read.total, cut = false)
            }
            setPhase(RailPhase.Up)
            if (arm()) {
                failedArms = 0
                next = (_armedAt.value ?: now()) + KEEP_EVERY_MS
            } else {
                if (++failedArms >= 2) {
                    fail(RailMessage.PlugLost)
                    return
                }
                next = now() + RETRY_MS
            }
        }
    }

    /**
     * Nothing wants the rail: the stop sequence, a second, then CUT and "24 V off". Wanted again within the second, the
     * rail stays: kept unchecked, up with its baseline, or (no baseline yet) a quick rail-up from the next session.
     * Always false (for [railUp]'s returns).
     */
    private suspend fun stopRail(): Boolean {
        setPhase(RailPhase.Stopping)
        stopSequence()
        if (waitFor(now() + STOP_TO_CUT_MS) { wantedNow() }) {
            setPhase(
                when {
                    uncheckedNow() -> RailPhase.Kept
                    _baseline.value != null && !needsBaseline -> RailPhase.Up
                    else -> RailPhase.Off
                },
            )
            return false
        }
        cutRail()
        _baseline.value = null
        setPhase(RailPhase.Off)
        say(RailMessage.Off)
        return false
    }

    /** Nothing wants the rail any more before it came up: off, and a CUT only when an ARM may have put it up. */
    private suspend fun quietly(armed: Boolean): Boolean {
        if (armed) return stopRail()
        _message.value = null
        setPhase(RailPhase.Off)
        return false
    }

    /** A failure: what wanted the rail is ended, what plays stops, the stop sequence, CUT (best effort), and [why] said. */
    private suspend fun fail(why: RailMessage): Boolean {
        log("Rail: $why")
        end()
        _message.value = why
        setPhase(RailPhase.Off)
        return false
    }

    /** The plug's switch went off mid-session: what plays stops, the stop sequence and CUT, then the rail is the person's. */
    private suspend fun abandon() {
        end()
        setPhase(RailPhase.Off)
        say(RailMessage.Off)
    }

    /** Ends everything: the wants so far, what plays, then the stop sequence and CUT. */
    private suspend fun end() {
        consumedThrough = nextSeq - 1
        stopPlaying()
        stopSequence()
        cutRail()
        _baseline.value = null
        needsBaseline = false
    }

    /** End of guest play (its end, or End guest play): connect again, `lock guest 0`, the stop sequence, then CUT. */
    private suspend fun guestEnds() {
        if (GUEST !in wants && _guestUntil.value == null) return
        command {
            wants.remove(GUEST)
            _guestUntil.value = null
            changed()
            log("Guest play ends: reconnecting")
            if (guest.reconnect()) guest.endOnPiano()
            if (!available.value) return@command
            stopSequence()
            delay(STOP_TO_CUT_MS)
            if (wantedNow()) {
                needsBaseline = true   // from here the CHECK rules: its first good one is the baseline
                setPhase(if (uncheckedNow()) RailPhase.Kept else RailPhase.Up)
            } else {
                cutRail()
                _baseline.value = null
                setPhase(RailPhase.Off)
                say(RailMessage.Off)
            }
        }
    }

    private fun watchGuest(until: Long) {
        guestJob?.cancel()
        guestJob = scope.launch {
            delay((until - now()).coerceAtLeast(0))
            guestEnds()
        }
    }

    // ---- The plug, the piano ---------------------------------------------------------------------------------------

    private suspend fun arm(): Boolean {
        weldJob?.cancel()   // the rail is wanted again: what the plug says now isn't about the last CUT
        val ok = plug.arm(TOGGLE_AFTER_S)
        if (ok) _armedAt.value = now() else log("ARM: the plug didn't answer")
        return ok
    }

    /**
     * CUT, best effort: when it fails the plug's timer cuts within 30 s. Then, a few seconds on and unless the rail was armed
     * again meanwhile, whether the relay really let go: the supply still drawing more than [weldedAbove] is a relay stuck
     * closed ([welded]); the plug's `output` reads off either way.
     */
    private suspend fun cutRail() {
        if (plug.cut()) {
            _armedAt.value = null
            weldJob?.cancel()
            weldJob = scope.launch {
                delay(WELD_CHECK_MS)
                if (!available.value || _armedAt.value != null) return@launch
                val status = plug.status() ?: return@launch
                if (_armedAt.value != null) return@launch
                val above = weldedAbove()
                _welded.value = (status.watts ?: 0.0) > above
                if (_welded.value) log("After a CUT the supply still draws ${status.watts} W (above $above W): the plug's relay may be stuck")
            }
        } else {
            log("CUT: the plug didn't answer; its timer cuts within ${TOGGLE_AFTER_S} s")
        }
    }

    /**
     * Above this the supply still draws after a CUT (v1.26 — M62; SMART_PLUG.md › rule 9): half its idle draw with the piano
     * silent ([idleWatts], read at the last rail-up), at least [WELDED_WATTS]; [WELDED_WATTS] before any reading.
     */
    private fun weldedAbove(): Double = maxOf(WELDED_WATTS, (idleWatts ?: 0.0) / 2)

    /** A CHECK, and a second 500 ms later when the first went unanswered: null when neither was (two in a row). */
    private suspend fun checkTwice(): Boards? = check() ?: run {
        delay(RAIL_UP_EVERY_MS)
        check()
    }

    /** Whether the rail may be up from an ARM within the plug's 30 s (no CUT taken since). */
    private fun mayBeUp(): Boolean = _armedAt.value?.let { now() - it < TOGGLE_AFTER_S * MS_PER_S } == true

    // ---- The wants ---------------------------------------------------------------------------------------------------

    private fun put(reason: String, until: Long = Long.MAX_VALUE, unchecked: Long = Long.MIN_VALUE) {
        wants[reason] = Want(nextSeq++, until, unchecked)
        changed()
    }

    private fun remove(reason: String) {
        if (wants.remove(reason) != null) changed()
    }

    private fun changed() {
        changes.value = changes.value + 1
    }

    private fun live(want: Want, t: Long): Boolean = want.seq > consumedThrough && t < want.until

    /** Something wants the rail now, and the plug switches it. */
    private fun wantedNow(t: Long = now()): Boolean = available.value && wants.values.any { live(it, t) }

    /** A want that may ARM without the CHECK (the update, guest play) holds now. */
    private fun uncheckedNow(t: Long = now()): Boolean = available.value && wants.values.any { live(it, t) && t < it.uncheckedUntil }

    /** The next moment a want ends or loses its exception, after [t]; [Long.MAX_VALUE] for none. */
    private fun nextChange(t: Long): Long {
        var next = Long.MAX_VALUE
        for (want in wants.values) {
            if (!live(want, t)) continue
            if (want.until in (t + 1) until next) next = want.until
            if (want.uncheckedUntil in (t + 1) until next) next = want.uncheckedUntil
        }
        return next
    }

    /**
     * Waits until [condition] holds (true) or [deadline] passes ([now]'s clock; false), looking again at every input and
     * whenever a want ends by itself.
     */
    private suspend fun waitFor(deadline: Long = Long.MAX_VALUE, condition: () -> Boolean): Boolean {
        while (true) {
            if (condition()) return true
            val t = now()
            if (t >= deadline) return false
            val wake = minOf(deadline, nextChange(t))
            val seen = changes.value
            if (wake == Long.MAX_VALUE) changes.first { it != seen } else withTimeoutOrNull(wake - t) { changes.first { it != seen } }
        }
    }

    // ---- Shown ------------------------------------------------------------------------------------------------------

    private fun setPhase(phase: RailPhase) {
        _phase.value = phase
        refreshHeld()
        if (phase != RailPhase.Waking && _message.value == RailMessage.Waking) _message.value = null
    }

    /** Notes wait while the plug switches the rail and it isn't up; with no piano connected and the rail off, they go nowhere anyway. */
    private fun refreshHeld() {
        val phase = _phase.value
        _held.value = available.value && phase != RailPhase.Up && phase != RailPhase.Kept && (connected.value || phase != RailPhase.Off)
    }

    /** [message] for a few seconds (the calm "24 V off"), unless another comes meanwhile. */
    private fun say(message: RailMessage) {
        if (_message.value?.attention == true) return   // a failure's words stay until read
        _message.value = message
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(CALM_MS)
            if (_message.value == message) _message.value = null
        }
    }

    /** [block] in the routine's place: the routine stops, [block] runs on [scope]'s thread, and the routine starts again. */
    private suspend fun <T> command(block: suspend () -> T): T = withContext(context) {
        commands.withLock {
            loop?.cancelAndJoin()
            try {
                block()
            } finally {
                loop = scope.launch { run() }
            }
        }
    }

    companion object {
        /** ARM's `toggle_after`: the plug switches itself off this long after the last ARM. */
        const val TOGGLE_AFTER_S = 30
        const val KEEP_EVERY_MS = 10_000L
        const val RAIL_UP_EVERY_MS = 500L
        const val RAIL_UP_MS = 10_000L
        const val RETRY_MS = 2_000L
        const val STOP_TO_CUT_MS = 1_000L
        const val CONTROLLER_FED_MS = 1_500L
        const val REPOWER_OFF_MS = 3_000L

        /** How long [repower]'s own want keeps the rail up after it: the safety check's sweep is about 39 s. */
        const val REPOWER_HOLD_MS = 60_000L
        const val UPDATE_CAP_MS = 3 * 60_000L
        const val KEYS_IDLE_MS = 10 * 60_000L
        const val GUEST_MAX_MIN = 60
        const val CALM_MS = 4_000L
        const val WELD_CHECK_MS = 3_000L

        /** The least drawn after a CUT that means the supply is still powered; half its idle draw when that is more (M62). */
        const val WELDED_WATTS = 2.0

        /** The reasons the app's own features want the rail by. */
        const val PLAY = "play"
        const val KEYS = "keys"
        const val KEY_TEST = "keytest"
        const val UPDATE = "update"
        const val GUEST = "guest"
        const val REPOWER = "repower"

        private const val MINUTE_MS = 60_000L
        private const val MS_PER_S = 1_000L
    }
}
