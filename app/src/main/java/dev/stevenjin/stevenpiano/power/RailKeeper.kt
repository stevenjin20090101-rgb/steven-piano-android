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
import kotlin.math.roundToInt

/** ARM, CUT and the switch's status, as the keeper asks them of the plug ([ShellyRail] over Bluetooth; a fake in tests). */
interface RailPlug {
    /** ARM: `Switch.Set {id:0, on:true, toggle_after:[seconds]}`; true when the plug took it. */
    suspend fun arm(seconds: Int): Boolean

    /** CUT: `Switch.Set {id:0, on:false}`; true when the plug took it. */
    suspend fun cut(): Boolean

    /** `Switch.GetStatus {id:0}`; null when the plug can't be asked. */
    suspend fun status(): PlugStatus?

    /**
     * The plug watch (v1.29 — M66): its settings read, and what differs from the tablet's put back (the switch's, the cloud
     * off, the network and access point closed; v1.32 — M70, Matter off, with the restart it asks for waited out before this
     * returns, and quietly the first time). True when something was put back that should be said, false when nothing differed
     * (or only quietly), null when the plug couldn't be read.
     */
    suspend fun putBack(): Boolean? = false
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

/**
 * What the power watch reads beyond the plug (v1.30 — M67; [PowerWatch]): the router's keys and pedal over time
 * ([HeldKeys], the piano's longest hold applied), the piano's facts and its Full power, and the safety check it makes due.
 */
interface PowerHost {
    /** The keys the router held over [from]..[to] ([RailKeeper]'s clock, ms), time-averaged. */
    fun held(from: Long, to: Long): Double

    /** Whether the router's sustain pedal was down at any moment of [from]..[to]. */
    fun pedalDown(from: Long, to: Long): Boolean

    /** Whether the router struck a key at or after [at]. */
    fun struckSince(at: Long): Boolean

    /** `get !<fact>` on the piano's console, answered within 2 s; null without an answer. */
    suspend fun ask(fact: String): String?

    /** The piano's Full power; true when it doesn't say (its default). */
    val fullPower: Boolean

    /** A safety check before the next Play (the 24 V was cut for a key on). */
    fun checkDue()

    /**
     * The plug's usual idle (v1.31 — M68): its last rail-up readings that didn't trip, W, oldest first
     * ([PowerWatch.USUAL_KEPT] at most, kept with its pairing; none for a plug forgotten or paired again).
     */
    fun usualIdle(): List<Double>

    /** A rail-up that didn't trip read [watts] with nothing sent: kept among [usualIdle]. */
    fun keepIdle(watts: Double)

    /**
     * A rail-up tripped above [usualIdle]: the next safety check that passes with its microphone sweep starts it again, a
     * new normal (a shorted channel holds its key down, so its strike can't sound and that sweep fails).
     */
    fun aboveUsual()
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

    /** The plug watch (v1.29 — M66): found on with nothing wanting the rail, then CUT. */
    data object SwitchedOn : RailMessage

    /** The plug watch (v1.29 — M66): its settings had been changed, and were put back. */
    data object SettingsPutBack : RailMessage

    /**
     * The power watch (v1.30 — M67): a key stayed on, seen in the supply's draw while a piece played, or [afterPiece] in
     * the check after it; the 24 V cut, and Play waits for a safety check.
     */
    data class StuckKey(val afterPiece: Boolean) : RailMessage

    /** The power watch (v1.30 — M67): the supply drew [watts] W at rail-up with nothing sent; the 24 V cut, the check due. */
    data class Overdraw(val watts: Int) : RailMessage

    /**
     * The power watch (v1.31 — M68): at rail-up, with nothing sent, the supply drew [watts] W more than the plug's usual
     * idle; the 24 V cut, the check due.
     */
    data class AboveUsual(val watts: Int) : RailMessage

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
 * - **The safety check's restart** ([expectRestart], v1.27 — M64) is no failure: the piano gone meanwhile, the rail goes
 *   quietly off with its wants kept, and comes up again with the piano.
 * - **The power watch** ([power], v1.30 — M67; SMART_PLUG.md › rule 11; [PowerWatch]), from each checked rail-up to the
 *   rail's end: the rail-up's reading is the idle draw (above 30 W twice, a second apart, with nothing sent: CUT, "The 24 V
 *   draws N W with nothing playing — 24 V cut"); then `!i2cfails`; then, while a piece plays, `apower` about every 2 s for
 *   the floor watch (a key on that the music isn't holding: the piece stops, CUT, "A key stayed on — 24 V cut"). With keys
 *   struck since the rail-up, the stop's second holds the check after the piece: a reading, `!active` and `!i2cfails`, a
 *   second reading a second later, then CUT (a key on: "A key stayed on after the piece — 24 V cut"). Each makes a safety
 *   check due before the next Play ([PowerHost.checkDue]). The rail-up's reading is also weighed against the plug's usual
 *   idle (v1.31 — M68; [PowerHost.usualIdle]): more than T above it twice, a second apart, CUT, "The 24 V draws N W more
 *   than usual with nothing playing — 24 V cut", the check due; a rail-up that didn't trip is kept, one that did never is.
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
    /** The power watch's other readings (v1.30 — M67); null: no power watch, as before it. */
    private val power: PowerHost? = null,
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

    /** Until when ([now]'s clock) a lost link is the restart [expectRestart] announced; ended by the piano connecting again. */
    private var restartUntil = 0L
    private var failedChecks = 0
    private var failedArms = 0
    private var loop: Job? = null

    /** The plug watch (v1.29 — M66): a firmware update's window, from BEGIN until its outcome, whatever its want became. */
    private var updating = false

    /** When the watch last looked at the plug's settings ([now]'s clock; null: not yet), and whether it missed the plug since. */
    private var settingsAt: Long? = null
    private var missedPlug = false
    private var guestJob: Job? = null
    private var messageJob: Job? = null
    private var weldJob: Job? = null
    private val commands = Mutex()

    /** The power watch of this rail-up (v1.30 — M67): from a checked rail-up until the rail is off or kept unchecked. */
    private var powerWatch: PowerWatch? = null

    /** When it began ([now]'s clock): keys struck since then call for the check after the piece. */
    private var watchSince = 0L

    /** When it looks next ([now]'s clock; [Long.MAX_VALUE]: never), how many looks it took, and whether `!i2cfails` was asked. */
    private var lookAt = Long.MAX_VALUE
    private var looks = 0
    private var failsAsked = false

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
                if (it) restartUntil = 0L   // back from a restart: a later loss is a failure again
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
        updating = on
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

    /**
     * The safety check's `restart` (v1.27 — M64; SMART_PLUG.md rule 10): the piano lets go of the link at once and is back
     * a few seconds later. For [withinMs], or until it is connected again, a CHECK that finds it gone is no failure: the
     * rail goes quietly to Off with every want kept (no stop, no CUT, nothing said; the plug's own timer bounds the rail,
     * as for a rebooting controller), and the rail-up with its CHECK follows the piano back. Outside it, nothing changes.
     */
    fun expectRestart(withinMs: Long) {
        restartUntil = now() + withinMs
        log("Rail: the piano restarts; its link lost in the next ${withinMs / MS_PER_S} s is no failure")
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
                    // guest play need none). Meanwhile the plug watch looks once a minute (v1.29 — M66).
                    while (!waitFor(now() + WATCH_EVERY_MS) { wantedNow() && (uncheckedNow() || connected.value) }) watch()
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
        powerWatch = null
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
        var idle = idleReading()
        if (power != null && PowerWatch.railUp(idle) is PowerVerdict.Overdraw) {
            // v1.30 — M67: above the cap with nothing sent, a channel may be on. One more look a second later (a supply still
            // waking is no fault), notes held meanwhile; still above it (or unread), CUT, and Play waits for a safety check.
            log("Power watch: ${PowerWatch.watts(idle)} W at rail-up with nothing sent (the cap: ${PowerWatch.watts(PowerWatch.CAP_WATTS)} W); looking again")
            if (waitFor(now() + CAP_AGAIN_MS) { !wantedNow() }) return stopRail()
            val again = idleReading()
            if (again == null || PowerWatch.railUp(again) is PowerVerdict.Overdraw) {
                val watts = again ?: idle ?: PowerWatch.CAP_WATTS
                log("Power watch: ${PowerWatch.watts(watts)} W at rail-up with nothing sent: a channel may be on")
                fail(RailMessage.Overdraw(watts.roundToInt()))
                power.checkDue()
                return false
            }
            idle = again
        }
        if (power != null) {
            // v1.31 — M68: the plug's usual idle, the median of its last rail-ups that didn't trip. More than T above it with
            // nothing sent, a channel may have been on since before the rail came up (a shorted MOSFET): this rail-up would
            // learn it as idle, and one channel stays under the cap. As the cap: one more look a second later, notes held;
            // still above it (or unread), CUT, said, the check due. Every checked rail-up, the safety check's own too: while
            // the draw stays up, the check's sweep can't run either.
            val usual = power.usualIdle()
            val fullPower = power.fullPower
            val first = PowerWatch.againstUsual(idle, usual, fullPower)
            if (first is PowerVerdict.AboveUsual) {
                log(
                    "Power watch: ${PowerWatch.watts(first.watts)} W at rail-up with nothing sent, above the usual " +
                        "${PowerWatch.watts(first.usual)} W (T ${PowerWatch.watts(PowerWatch.threshold(fullPower))} W, ${usual.size} kept); looking again",
                )
                if (waitFor(now() + CAP_AGAIN_MS) { !wantedNow() }) return stopRail()
                val again = idleReading()
                val still = PowerWatch.againstUsual(again, usual, fullPower) as? PowerVerdict.AboveUsual ?: first.takeIf { again == null }
                if (still != null) {
                    // Never kept: a check that passes with its sweep, every key heard, starts the usual again (a new normal).
                    // One without the sweep (the microphone off) keeps it, so the trip repeats at each rail-up. Annoying, but safe.
                    log("Power watch: ${PowerWatch.watts(still.watts)} W at rail-up with nothing sent, above the usual: a channel may be on")
                    power.aboveUsual()
                    fail(RailMessage.AboveUsual((still.watts - still.usual).roundToInt()))
                    power.checkDue()
                    return false
                }
                idle = again
            }
            idle?.let(power::keepIdle)
        }
        idle?.let { idleWatts = it }
        if (power != null) startWatch(idle)
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
            val stop = waitFor(minOf(next, lookAt)) { !wantedNow() }
            if (!available.value) {
                abandon()
                return
            }
            if (stop) {
                stopRail()
                return
            }
            if (now() < next) {
                if (look()) return   // the power watch's turn between the keeper's own (v1.30 — M67)
                continue
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
            if (read == null && !connected.value && now() < restartUntil) {
                // The restart the safety check asked for (v1.27 — M64): the wants stay and the plug's timer bounds the rail;
                // once the piano is back the rail-up with its CHECK restores the baseline. Wanted no more before, the usual stop.
                log("CHECK: no answer while the piano restarts, as the safety check asked")
                _baseline.value = null
                setPhase(RailPhase.Off)
                waitFor { connected.value || !wantedNow() }
                when {
                    !available.value -> abandon()
                    !wantedNow() -> stopRail()
                }
                return
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
     * With keys struck since the rail-up the check after the piece comes first ([afterPiece], v1.30 — M67), and the CUT a
     * second later; a key on: CUT whatever wants the rail by then, and said. Always false (for [railUp]'s returns).
     */
    private suspend fun stopRail(): Boolean {
        setPhase(RailPhase.Stopping)
        stopSequence()
        val stopped = now()
        val checking = powerWatch?.takeIf { power?.struckSince(watchSince) == true }
        val back = if (checking == null) {
            waitFor(stopped + STOP_TO_CUT_MS) { wantedNow() }
        } else {
            when (afterPiece(checking, stopped)) {
                null -> true
                is PowerVerdict.Stuck -> {
                    fail(RailMessage.StuckKey(afterPiece = true))
                    power?.checkDue()
                    return false
                }
                else -> wantedNow()
            }
        }
        if (back) {
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

    // ---- The power watch (v1.30 — M67) --------------------------------------------------------------------------------

    /** The plug's `apower` now, W; null unread. */
    private suspend fun idleReading(): Double? = plug.status()?.watts?.takeIf { it.isFinite() && it >= 0.0 }

    /** This rail-up's power watch, from its [idle] reading; its first look, at once, asks `!i2cfails`. */
    private fun startWatch(idle: Double?) {
        powerWatch = PowerWatch(idle)
        watchSince = now()
        looks = 0
        failsAsked = false
        lookAt = now()
        log("Power watch: idle ${PowerWatch.watts(idle)} W at rail-up")
    }

    /** A piece plays (its want holds): the floor watch reads only then. */
    private fun pieceNow(): Boolean = wants[PLAY]?.let { live(it, now()) } == true

    /**
     * The power watch's look, about every 2 s while the rail is up: the rail-up's `!i2cfails` first (what the check after the
     * piece compares); then, while a piece plays, one `apower` reading for the floor watch (none plays: its run starts
     * again). True when it found a key on that the music isn't holding: the piece stopped, CUT, said, the check due.
     */
    private suspend fun look(): Boolean {
        val watch = powerWatch
        val host = power
        if (watch == null || host == null) {
            lookAt = Long.MAX_VALUE
            return false
        }
        lookAt = now() + PowerWatch.lookIn(looks++)
        if (!failsAsked) {
            failsAsked = true
            watch.failsAtRailUp = host.ask(I2C_FAILS)?.trim()?.toLongOrNull()
            return false
        }
        if (!pieceNow()) {
            watch.pause()
            return false
        }
        val asked = now()
        val watts = plug.status()?.watts?.takeIf { it.isFinite() } ?: return false
        if (powerWatch !== watch || !pieceNow()) return false
        val sample = PowerWatch.sample(asked, now(), watts, host::held, host::pedalDown, host.fullPower)
        val stuck = watch.reading(sample) as? PowerVerdict.Stuck ?: return false
        log("Power watch: a key stayed on mid-piece — ${stuck.why}; the piece stops, CUT")
        fail(RailMessage.StuckKey(afterPiece = false))
        host.checkDue()
        return true
    }

    /**
     * The check after the piece (SMART_PLUG.md › rule 11 b), between the stop sequence at [stopped] and the CUT: an `apower`
     * reading a second after it, the piano's `!active` and `!i2cfails`, and a second reading a second after the first. Its
     * verdict, logged; null when something wanted the rail again before it was done (it stays up, as within the second).
     */
    private suspend fun afterPiece(watch: PowerWatch, stopped: Long): PowerVerdict? {
        val host = power ?: return PowerVerdict.Fine
        if (waitFor(stopped + STOP_TO_CUT_MS) { wantedNow() }) return null
        val firstAt = now()
        val first = plug.status()?.watts?.takeIf { it.isFinite() }
        val active = host.ask(ACTIVE)?.trim()?.toIntOrNull()
        val fails = host.ask(I2C_FAILS)?.trim()?.toLongOrNull()
        if (waitFor(firstAt + AFTER_APART_MS) { wantedNow() }) return null
        val second = if (first == null) null else plug.status()?.watts?.takeIf { it.isFinite() }
        val fullPower = host.fullPower
        val verdict = watch.afterPiece(first, second, active, fails, fullPower)
        log(
            "Power watch after the piece: ${PowerWatch.watts(first)} W, ${PowerWatch.watts(second)} W (idle " +
                "${PowerWatch.watts(watch.idle)} W, T ${PowerWatch.watts(PowerWatch.threshold(fullPower))} W), !active ${active ?: "—"}, " +
                "!i2cfails ${fails ?: "—"} (${watch.failsAtRailUp ?: "—"} at rail-up); the floor watch: ${watch.readings} readings, " +
                "the highest ${PowerWatch.watts(watch.highest)} W above the music; " +
                if (verdict is PowerVerdict.Stuck) "a key stayed on — ${verdict.why}" else "fine",
        )
        return verdict
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

    // ---- The plug watch (v1.29 — M66) ----------------------------------------------------------------------------------

    /**
     * One look at the plug while nothing wants the rail (v1.29 — M66; every [WATCH_EVERY_MS]): `Switch.GetStatus`, one small
     * read. Found on: its settings put back first (an `auto_on` must not bring it back), then CUT with its weld look, and
     * "switched on by something else" said. Its settings are looked at too the first time, after the plug was out of reach,
     * and every [SETTINGS_EVERY_MS]; changed ones are put back and said. Never while the rail is wanted (the keeper talks to
     * the plug every 10 s then) or a firmware update runs; a plug out of reach says nothing new. Matter off (v1.32 — M70) may
     * restart the plug: [RailPlug.putBack] returns once it is back, so the restart is no missed read, and a plug found on is
     * still CUT (and said, its weld look after) once it answers again; it comes back off, its power-up state the tablet's.
     */
    private suspend fun watch() {
        if (!available.value || wantedNow() || updating) return
        val status = plug.status()
        if (status == null) {
            missedPlug = true
            return
        }
        if (!available.value || wantedNow() || updating) return
        val t = now()
        val settingsDue = status.output || missedPlug || settingsAt?.let { t - it >= SETTINGS_EVERY_MS } != false
        if (settingsDue) {
            when (plug.putBack()) {
                true -> {
                    settingsAt = t
                    missedPlug = false
                    log("Plug watch: the plug's settings had been changed; put back")
                    if (!status.output && _message.value?.attention != true) _message.value = RailMessage.SettingsPutBack
                }
                false -> {
                    settingsAt = t
                    missedPlug = false
                }
                null -> missedPlug = true
            }
        }
        if (status.output && !wantedNow()) {
            log("Plug watch: the plug was on with nothing wanting the 24 V; CUT")
            if (cutRail()) _message.value = RailMessage.SwitchedOn
            _baseline.value = null
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
    private suspend fun cutRail(): Boolean {
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
            return true
        }
        log("CUT: the plug didn't answer; its timer cuts within ${TOGGLE_AFTER_S} s")
        return false
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
        if (phase == RailPhase.Off || phase == RailPhase.Kept) {
            // The power watch (v1.30 — M67) runs from a checked rail-up until the rail is off or kept unchecked.
            powerWatch = null
            lookAt = Long.MAX_VALUE
        }
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

        /** The plug watch (v1.29 — M66): one look a minute while nothing wants the rail, its settings every 10 minutes. */
        const val WATCH_EVERY_MS = 60_000L
        const val SETTINGS_EVERY_MS = 10 * 60_000L

        /** The least drawn after a CUT that means the supply is still powered; half its idle draw when that is more (M62). */
        const val WELDED_WATTS = 2.0

        /**
         * The power watch (v1.30 — M67): a reading above the cap at rail-up is looked at again this much later; the check
         * after the piece takes its readings this far apart (its first a second after the stop sequence, as the CUT was).
         */
        const val CAP_AGAIN_MS = 1_000L
        const val AFTER_APART_MS = 1_000L

        /** The piano's facts the power watch reads: its coils believed on, and its I²C failures since it started. */
        const val ACTIVE = "active"
        const val I2C_FAILS = "i2cfails"

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
