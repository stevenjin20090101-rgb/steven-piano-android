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

/** ARM, CUT and the switch's status, as the keeper asks them of the plug ([SwitchBotRail] over Bluetooth; a fake in tests). */
interface RailPlug {
    /**
     * ARM: the plug on; true when it took it. [seconds] is how long a plug with a timer of its own would hold it; the
     * SwitchBot (v1.36 — M72) has none, so the 24 V stays on until a CUT.
     */
    suspend fun arm(seconds: Int): Boolean

    /** CUT: the plug off; true when it took it. */
    suspend fun cut(): Boolean

    /** The plug's switch and the supply's draw; null when the plug can't be asked. */
    suspend fun status(): PlugStatus?

    /**
     * v1.39 — M76: the supply's draw alone with when the plug said it ([PlugReading.at], the keeper's clock), null unread:
     * the SwitchBot's latest advert (under 3 s old), with no connection to the plug, so the power watches can read it every
     * second, the plug's adverts keep flowing, and a reading the plug sent before a moment (a piece's last notes, an ARM)
     * is never taken for one after it.
     */
    suspend fun reading(): PlugReading?

    /**
     * The plug watch (v1.29 — M66): its settings read, and what differs from the tablet's put back. True when something was
     * put back that should be said, false when nothing differed (or only quietly), null when the plug couldn't be read. The
     * SwitchBot (v1.36 — M72) keeps none of the tablet's: always false.
     */
    suspend fun putBack(): Boolean? = false
}

/**
 * The plug's switch as it says: [output] on, the seconds its timer was set for ([timerS]; null when none runs, always with
 * the SwitchBot), and the 24 V supply's draw in watts ([watts]: what proves the rail is really up; the SwitchBot's from its
 * latest advert, null when unread).
 */
data class PlugStatus(val output: Boolean, val timerS: Double? = null, val watts: Double? = null)

/** v1.39 — M76: the supply's draw, W, as the plug's advert said it, and when that advert came ([at], the keeper's clock, ms). */
data class PlugReading(val watts: Double, val at: Long)

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
    /** Off: nothing wants it, or (v1.27 — M64) the piano restarts with its wants kept, the 24 V cut meanwhile. */
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

    /**
     * v1.38 — M75: the piano was off (its controller is on the plug too) and its link didn't come up within 30 s of a
     * confirmed ARM: the 24 V CUT, "The piano didn't start. Check its power."
     */
    data object PianoDidntStart : RailMessage

    /** "Only [answered] of [total] power boards answered" (a trunk fault), and "— 24 V cut" when [cut]. */
    data class TrunkFault(val answered: Int, val total: Int, val cut: Boolean) : RailMessage

    /** The plug watch (v1.29 — M66): found on with nothing wanting the rail, then CUT. */
    data object SwitchedOn : RailMessage

    /** The plug watch (v1.29 — M66): its settings had been changed, and were put back. */
    data object SettingsPutBack : RailMessage


    /** The power watch (v1.30 — M67): the supply drew [watts] W at rail-up with nothing sent; the 24 V cut, the check due. */
    data class Overdraw(val watts: Int) : RailMessage

    /**
     * The power watch (v1.31 — M68): at rail-up, with nothing sent, the supply drew [watts] W more than the plug's usual
     * idle; the 24 V cut, the check due.
     */
    data class AboveUsual(val watts: Int) : RailMessage

    // v1.39 — M76: the power trips, each with its reason and its numbers (Steven's next report says which rule fired).
    // The floor watch's "a key stayed on" mid-piece (M67) no longer cuts: it is logged only.

    /**
     * [BoardWatch]: a board's worth on that nothing explains: [above] W more than the piano's [idle] W with nothing
     * [playing], or more than the notes explain while playing ([idle] null then). CUT at once.
     */
    data class BoardOn(val above: Int, val idle: Int?, val playing: Boolean) : RailMessage

    /** [BoardWatch]: with nothing playing, [above] W more than the piano's [idle] W for [seconds] s: a key on. CUT at once. */
    data class CoilOn(val above: Int, val idle: Int, val seconds: Int) : RailMessage

    /** The piano's `!i2cfails` went up from [before] to [after]: it lost contact with a board, mid-piece, idle or [afterPiece]. */
    data class BoardLost(val before: Long, val after: Long, val afterPiece: Boolean = false) : RailMessage

    /** The check after the piece: three fresh readings all at least [above] W more than the piano's [idle] W. */
    data class AfterPiece(val above: Int, val idle: Int) : RailMessage

    /** The check after the piece: the piano says [keys] coils are still on (`!active`). */
    data class KeysOn(val keys: Int) : RailMessage

    /** Whether it needs attention (amber, until dismissed or the next rail-up) rather than passing by itself. */
    val attention: Boolean get() = this !is Waking && this !is Off
}

/**
 * The tablet as the 24 V rail's keeper (v1.26 — M60; `firmware/docs/SMART_PLUG.md`, "The app's routine"). The solenoid
 * supply is on a smart plug; this keeps the rail up exactly while something wants it (the reasons: a piece playing, the
 * Keys tab, Test every key, the safety check, a firmware update, guest play) and the piano answers with its boards, and
 * off otherwise. Since v1.36 (M72) the plug is a SwitchBot Plug Mini, which has **no timer of its own**: an ARM holds the
 * 24 V on until a CUT, and it comes on by itself after a power cut. So the tablet CUTs explicitly wherever the Shelly's
 * 30 s timer used to bound the rail, CUTs at app start and at every new connection to the plug unless something wants
 * the rail ([appStarted], [plugConnected]), and CUTs at once when an advert says the plug is on with nothing wanting it
 * ([advert]). An outlet timer is the hardware backstop if the tablet stops.
 *
 * - **Rail-up:** a good CHECK (the piano's `get boards` answered within 2 s), ARM, then the CHECK every 500 ms until every
 *   board reads OK, 10 s at most. The boards that answered are the **baseline**; fewer than seven is a trunk fault, said;
 *   none ends it. Notes wait meanwhile ([notesHeld], which [RailGate] applies). Boards that answer with the rail off (the
 *   trunk fed from the controller) prove nothing: then the wait is a fixed 1.5 s after ARM.
 * - **Waking the piano from off** (v1.38 — M75): the piano's controller is on the plug with the 24 V, so with no link the
 *   piano is off and there is nothing to CHECK. A want then ARMs first (confirmed on: the reply's switch, then a status;
 *   3 tries a second apart), asks the link to connect at once ([wakeLink]), waits 30 s at most for it, then the 1.5 s
 *   settle and the CHECK; notes wait throughout. No link in time: CUT, "The piano didn't start. Check its power."
 * - **While wanted:** every 10 s the CHECK, then ARM only when the piano answered and every baseline board still reads OK.
 *   **Nothing ARMs without a good CHECK just before it**, but the two exceptions: a firmware update from BEGIN until its
 *   outcome (3 minutes at most) and guest play this tablet started (60 minutes at most), which ARM every 10 s unchecked.
 *   A failed CHECK or ARM is tried again 2 s later. Two failed CHECKs in a row stop the music, CUT and say "The piano
 *   stopped answering — 24 V cut" (or which boards went); two failed ARMs stop it and say "Lost the power plug".
 * - **Stop:** when nothing wants the rail, the stop sequence (CC64 = 0, CC123), a second, then CUT; "24 V off" for a few
 *   seconds. A want back within that second keeps the rail up.
 * - **A failure ends what wanted the rail:** the next Play, key or start brings it up again, through a fresh CHECK.
 * - **The safety check's restart** ([expectRestart], v1.27 — M64) is no failure: the piano gone meanwhile, the rail is
 *   CUT quietly with its wants kept (v1.36 — M72: no plug timer to bound it), and comes up again with the piano.
 * - **The power watch** ([power], v1.30 — M67; SMART_PLUG.md › rule 11; [PowerWatch]), from each checked rail-up to the
 *   rail's end: the rail-up's reading is the idle draw (above 30 W twice, a second apart, with nothing sent: CUT, "The 24 V
 *   draws N W with nothing playing — 24 V cut"); then `!i2cfails`; then, while a piece plays, `apower` about every 2 s for
 *   the floor watch (a key on that the music isn't holding: the piece stops, CUT, "A key stayed on — 24 V cut"). With keys
 *   struck since the rail-up, the stop's second holds the check after the piece: a reading, `!active` and `!i2cfails`, a
 *   second reading a second later, then CUT (a key on: "A key stayed on after the piece — 24 V cut"). Each makes a safety
 *   check due before the next Play ([PowerHost.checkDue]). The rail-up's reading is also weighed against the plug's usual
 *   idle (v1.31 — M68; [PowerHost.usualIdle]): more than T above it twice, a second apart, CUT, "The 24 V draws N W more
 *   than usual with nothing playing — 24 V cut", the check due; a rail-up that didn't trip is kept, one that did never is.
 * - **v1.39 — M76.** The On hours' standing want ([HOURS]) keeps the rail up all day; outside them a person at the tablet
 *   wakes it, and a piece's check that passes keeps it [graceMs] for the next ([GRACE]). While up, beside the power
 *   watch, the stuck-board watch ([BoardWatch]: a board's worth drawn that nothing explains, idle or playing) and the
 *   piano's `!i2cfails` (every 5 s during a piece, every 30 s idle) CUT at once ([cutAtOnce]); the plug's status every 5 s
 *   during a piece, and any "off" advert, re-ARM it with the notes held ([plugCheck], [rewake]); silent adverts say
 *   [readingLost]. The plug's draw comes from its adverts ([RailPlug.watts]) with no connection.
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
    /**
     * v1.38 — M75: asks Steven Piano's link to connect now (the piano's controller is on the plug too, so it is off until
     * an ARM): called once the plug confirms on, and again every [WAKE_ASK_EVERY_MS] until the link is up.
     */
    private val wakeLink: () -> Unit = {},
    /**
     * v1.39 — M76: the grace between pieces, ms: outside the On hours (a person at the tablet), the rail kept this long
     * after a piece whose check passed, for the next; 0 within them, or with no On hours.
     */
    private val graceMs: () -> Long = { 0L },
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

    /**
     * When the plug last took an ARM ([now]'s clock); null after a CUT it took. With no plug timer (v1.36 — M72) the rail
     * may be up for as long as it isn't null.
     */
    val armedAt: StateFlow<Long?> = _armedAt.asStateFlow()

    private val _guestUntil = MutableStateFlow<Long?>(null)

    /** When guest play this tablet started ends ([now]'s clock); null with none. */
    val guestUntil: StateFlow<Long?> = _guestUntil.asStateFlow()

    private val _manualUntil = MutableStateFlow<Long?>(null)

    /**
     * v1.38 — M75: until when ([now]'s clock) Piano › Power plug's **Turn the piano on** keeps the rail wanted ([manual]);
     * null while it doesn't. Past it, the want has ended by itself.
     */
    val manualUntil: StateFlow<Long?> = _manualUntil.asStateFlow()

    private val _welded = MutableStateFlow(false)

    /**
     * The supply still draws after a CUT the plug took: a relay stuck closed. The draw is the proof (SMART_PLUG.md › rule 9;
     * `output` is only the commanded state, which a welded contact still reads as off): above half the supply's idle draw
     * with the piano silent, at least [WELDED_WATTS] ([weldedAbove]). Since v1.36 (M72) also: [ADVERT_CUTS] CUTs in a row
     * that didn't take, the plug's adverts still saying on (the same amber words: the tablet can't cut the 24 V).
     */
    val welded: StateFlow<Boolean> = _welded.asStateFlow()

    /** What [welded] is made of: the draw after a CUT, and CUTs that don't take (v1.36 — M72). */
    private var weldSeen = false
    private var cutsDontTake = false

    /** v1.36 — M72: when the last CUT went ([now]'s clock; adverts sent before it may still say on), and CUTs since an "off". */
    private var cutAt: Long? = null
    private var advertCuts = 0

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

    private val _readingLost = MutableStateFlow(false)

    /**
     * v1.39 — M76: the rail is up and the plug's adverts have said nothing for over [PlugAdverts.SILENT_MS]: the power
     * watches can't read the draw (logged once; a small amber "Power reading lost"). Never a CUT by itself.
     */
    val readingLost: StateFlow<Boolean> = _readingLost.asStateFlow()

    /** v1.39 — M76: this rail-up's stuck-board watch ([BoardWatch]), beside [powerWatch]. */
    private var boardWatch: BoardWatch? = null

    /** v1.39 — M76: `!i2cfails` as last read this rail-up (null: unread), and when it is read next ([now]'s clock). */
    private var failsSeen: Long? = null
    private var failsAt = Long.MAX_VALUE

    /** v1.39 — M76: when the floor watch takes its next reading, and the last moment the draw read. */
    private var floorAt = 0L
    private var wattsAt = 0L

    /** v1.39 — M76: when the piano's link last came up, and whether this rail-up gave the usual idle its reading. */
    private var linkUpAt = 0L
    private var idleKept = false

    /** v1.39 — M76: whether a piece played at the last look (its end calls for the check after it). */
    private var wasPlaying = false

    /** v1.39 — M76: the plug's 5 s check while playing: when next, unanswered in a row, and one asked now (an "off" advert). */
    private var plugCheckAt = Long.MAX_VALUE
    private var plugMisses = 0
    private var plugCheckNow = false

    /**
     * v1.39 — M76, the grace between pieces: given since the last want (so its own end CUTs), refused by Turn the piano
     * off, and whether what wants the rail began within the On hours (a piece playing at 22:00 ends with the normal CUT).
     */
    private var graceGiven = false
    private var noGrace = false
    private var startedInHours = false

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
                if (it) linkUpAt = now()
                refreshHeld()
                changed()
            }
        }
        loop = scope.launch { run() }
    }

    // ---- The inputs (main thread) ------------------------------------------------------------------------------

    override fun wanted(reason: String, on: Boolean) {
        if (on) put(reason) else remove(reason)
        playedNow()
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
        playedNow()
    }

    /**
     * v1.38 — M75: Piano › Power plug's **Turn the piano on** ([on]) or **Turn the piano off**. On, it wants the rail as any
     * feature does (the same rail-up: ARM, the piano's link, the CHECK), until [MANUAL_IDLE_MS] pass without notes: a
     * piece starting or ending, a key, or any other want coming or going starts the 30 minutes again. Off, the want goes,
     * and the usual stop follows (the stop sequence, a second, CUT) unless something else wants the rail.
     */
    fun manual(on: Boolean) {
        if (on) {
            val until = now() + MANUAL_IDLE_MS
            put(MANUAL, until = until)
            _manualUntil.value = until
        } else {
            remove(MANUAL)
            _manualUntil.value = null
            noGrace = true   // v1.39 — M76: turned off is off, with no grace between pieces
        }
    }

    /**
     * v1.39 — M76: [reason] wants the rail for [ms] from now, then no longer by itself (the firmware updater waking the
     * piano before its transfer, whose own want takes over from BEGIN).
     */
    fun wantFor(reason: String, ms: Long) {
        put(reason, until = now() + ms)
        playedNow()
    }

    /** Something played, or wanted the rail, or stopped wanting it (v1.38 — M75): a manual want's 30 minutes start again. */
    private fun playedNow() {
        val want = wants[MANUAL] ?: return
        if (!live(want, now())) return
        manual(true)
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
     * rail goes quietly to Off with every want kept (no stop, nothing said), and the rail-up with its CHECK follows the piano
     * back. Outside it, nothing changes. Before v1.36 the plug's own 30 s timer bounded the rail meanwhile; the SwitchBot
     * has none (M72), so it is CUT at once, quietly: the piano is restarting, and the rail-up after it ARMs afresh.
     */
    fun expectRestart(withinMs: Long) {
        restartUntil = now() + withinMs
        log("Rail: the piano restarts; its link lost in the next ${withinMs / MS_PER_S} s is no failure")
    }

    /**
     * An advert of the paired plug (v1.36 — M72; [PlugAdverts], on [scope]'s thread): its switch [on]. Nothing wanting the
     * rail and the rail off (not [holding]; never while it stops: the check after the piece reads it; since v1.38 — M75
     * never while anything wants the rail, one waiting for the piano included), "on" is CUT at once (logged): the plug on at
     * power-up, someone with the SwitchBot app, a CUT that was missed. Adverts sent before a CUT may still say on for
     * [ADVERT_SETTLE_MS]; one after that is another CUT, and [ADVERT_CUTS] in a row that didn't take show the amber
     * [welded]. "Off", or something wanting the rail, starts the count again.
     */
    fun advert(on: Boolean) {
        if (!available.value) return
        if (!on && _phase.value == RailPhase.Up && wantedNow() && _armedAt.value?.let { now() - it >= ADVERT_SETTLE_MS } != false) {
            // v1.39 — M76: "off" while the rail should be up (the plug pressed, or its power gone): the plug's own status
            // decides at once ([plugCheck]), as its 5 s check while playing does; a stale advert from before an ARM, no.
            if (!plugCheckNow) {
                plugCheckNow = true
                changed()
            }
        }
        if (!on || holding()) {
            advertCuts = 0
            if (!on && cutsDontTake) {
                cutsDontTake = false
                showWelded()
            }
            return
        }
        val t = now()
        if (cutAt?.let { t - it < ADVERT_SETTLE_MS } == true) return
        if (advertCuts >= ADVERT_CUTS && !cutsDontTake) {
            cutsDontTake = true
            showWelded()
            log("Plug: $advertCuts CUTs in a row and its adverts still say on: the tablet can't switch the 24 V off")
        }
        advertCuts++
        cutAt = t
        log("Plug: an advert says on with nothing wanting the 24 V; CUT")
        cutIfIdle()
    }

    /** At app start (v1.36 — M72): CUT unless the keeper holds the rail (the plug may have come on by itself at power-up). */
    fun appStarted() {
        if (available.value) cutIfIdle("the app started")
    }

    /**
     * A new connection to the paired plug (v1.36 — M72; [PlugClient]): CUT unless the keeper holds the rail. Not within
     * [CONNECT_CUT_GAP_MS] of the last CUT, whose own connection it may be.
     */
    fun plugConnected() {
        if (!available.value || cutAt?.let { now() - it < CONNECT_CUT_GAP_MS } == true) return
        cutIfIdle("the plug connected")
    }

    /** A CUT in the routine's place, when the keeper isn't holding the rail ([holding]; looked at again as it runs); [why] logged. */
    private fun cutIfIdle(why: String? = null) {
        if (!available.value || holding()) return
        scope.launch {
            command {
                if (!available.value || holding()) return@command
                why?.let { log("Rail: $it; CUT, as nothing holds the 24 V on") }
                cutRail()
            }
        }
    }

    /**
     * Whether the keeper holds the rail on (v1.36 — M72): coming up, up, kept or stopping, or wanted at all. v1.38 — M75:
     * the piano's controller is on the plug too, so a want waiting for the piano's link is exactly when the plug must come
     * on (M72 CUT a plug on then, with no ARM of the keeper's: the deadlock that played a piece into an unpowered piano).
     * Only a plug on with nothing wanting the rail is CUT.
     */
    private fun holding(): Boolean = _phase.value != RailPhase.Off || wantedNow()

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

    /**
     * The switch going off, or the plug being forgotten: what plays stops, the stop sequence and CUT when the rail may be up;
     * else (v1.36 — M72) a CUT alone, as the SwitchBot may be on without an ARM (at power-up, or from its app).
     */
    suspend fun release() {
        if (!available.value) return
        command {
            if (_phase.value != RailPhase.Off || _armedAt.value != null) end() else cutRail()
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
                    // v1.38 — M75: a want brings the rail up whether the piano's link is up or not: the controller is on
                    // the plug too, so without the link the piano is off, and the rail-up wakes it ([wake]). Meanwhile the
                    // plug watch looks once a minute (v1.29 — M66).
                    while (!waitFor(now() + WATCH_EVERY_MS) { wantedNow() }) watch()
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
        powerWatch = null
        setPhase(RailPhase.Waking)
        _message.value = RailMessage.Waking
        if (!wantedNow()) return quietly(mayBeUp)
        // v1.38 — M75: no link, the piano is off (its controller is on the plug too): ARM first, then its link, then the
        // CHECK ([wake]). "A good CHECK before ARM" holds only while the piano is already connected.
        val woke = !connected.value
        var before: Boards? = if (woke) wake() ?: return false else null
        // The CHECK before ARM: never ARM without a good one. Two unanswered in a row, or two refused ARMs, end it.
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
        // Woken, the boards came up with the controller: their 500 ms looks count from the link, not the ARM long before.
        val armedAt = if (woke) now() else _armedAt.value ?: now()
        val baseline: Set<Int> = when {
            // Woken: the controller and the 24 V came on together, so every board answering is the rail up.
            woke && before.all -> before.okSet
            // Every board answered with the rail off: the trunk side is fed from the controller, so the boards prove
            // nothing; the supply gets a fixed 1.5 s instead (SMART_PLUG.md › rule 2). (With the rail perhaps still up
            // from an ARM no CUT took since, the first CHECK below finds them all and the wait is 500 ms.)
            !woke && !mayBeUp && before.all -> {
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
            // v1.39 — M76: not kept here: the reading at rail-up has the controller booting in it (see [look]).
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
     * v1.38 — M75: the piano woken from off. Its controller is on the plug with the 24 V, so with no link there is nothing
     * to CHECK: ARM first, confirmed on ([armConfirmed]), then its link asked for at once ([wakeLink], again every
     * [WAKE_ASK_EVERY_MS]), [WAKE_LINK_MS] at most; the supply's 1.5 s settle after the ARM; then the CHECK (twice, 500 ms
     * apart, when the first goes unanswered). The first CHECK's boards; null when it ended: failed and said (the plug never
     * on: "Lost the power plug"; no link in time: CUT, "The piano didn't start. Check its power."), or no longer wanted.
     */
    private suspend fun wake(): Boards? {
        log("Rail: the piano is off; ARM first, then its link")
        when (armConfirmed()) {
            null -> return null.also { quietly(true) }
            false -> return null.also { fail(RailMessage.PlugLost) }
            true -> Unit
        }
        val by = now() + WAKE_LINK_MS
        while (!connected.value) {
            wakeLink()
            val t = now()
            if (t >= by) {
                log("Rail: the piano's link didn't come up within ${WAKE_LINK_MS / MS_PER_S} s of the ARM; CUT")
                return null.also { fail(RailMessage.PianoDidntStart) }
            }
            waitFor(minOf(by, t + WAKE_ASK_EVERY_MS)) { connected.value || !wantedNow() }
            if (!wantedNow()) return null.also { quietly(true) }
        }
        log("Rail: the piano's link is up after the ARM")
        val armed = _armedAt.value ?: now()
        if (waitFor(armed + CONTROLLER_FED_MS) { !wantedNow() }) return null.also { quietly(true) }
        val read = checkTwice()
        if (!wantedNow()) return null.also { quietly(true) }
        if (read == null) return null.also { fail(RailMessage.PianoSilent) }
        _boards.value = read
        return read
    }

    /**
     * ARM until the plug confirms on (v1.38 — M75): its reply's switch on ([RailPlug.arm]), then a status read that doesn't
     * say off; [ARM_TRIES] tries [ARM_AGAIN_MS] apart. True on; false when it never confirmed; null when no longer wanted.
     */
    private suspend fun armConfirmed(): Boolean? {
        repeat(ARM_TRIES) { i ->
            if (i > 0 && waitFor(now() + ARM_AGAIN_MS) { !wantedNow() }) return null
            if (arm()) {
                if (plug.status()?.output != false) return true
                log("ARM: the plug answered, but its status says off")
            }
            if (!wantedNow()) return null
        }
        return false
    }

    /**
     * While wanted: every 10 s after the last ARM, the CHECK, then ARM only on a good one (unchecked for the update or guest
     * play); a failure is tried again 2 s later, and two in a row end it. Returns when the rail went off, or came back on
     * after a stop with something wanting it again ([run] dispatches on the phase).
     */
    private suspend fun keep() {
        var next = (_armedAt.value ?: now()) + KEEP_EVERY_MS
        while (true) {
            waitFor(minOf(next, lookAt)) { !wantedNow() || plugCheckNow }
            if (!available.value) {
                abandon()
                return
            }
            if (!wantedNow()) {
                stopRail()
                return
            }
            if (plugCheckNow) {
                // v1.39 — M76: an "off" advert while up: the plug's status decides.
                plugCheckNow = false
                if (_phase.value == RailPhase.Up && plugCheck()) return
                if (_phase.value != RailPhase.Up && _phase.value != RailPhase.Kept) return
                continue
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
                // The restart the safety check asked for (v1.27 — M64): the wants stay, and once the piano is back the rail-up
                // with its CHECK restores the baseline. Wanted no more before, the usual stop. The Shelly's timer used to bound
                // the rail meanwhile; the SwitchBot has none (v1.36 — M72), so it is CUT now, quietly.
                log("CHECK: no answer while the piano restarts, as the safety check asked; CUT until it is back")
                cutRail()
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
        var passed = false
        var back = if (checking == null) {
            waitFor(stopped + STOP_TO_CUT_MS) { wantedNow() }
        } else {
            val after = afterPiece(checking, stopped) { wantedNow() }
            when {
                after == null -> true
                after.cut != null -> {
                    fail(after.cut)
                    power?.checkDue()
                    return false
                }
                else -> wantedNow().also { passed = true }
            }
        }
        val grace = graceMs()
        if (!back && passed && grace > 0 && !graceGiven && !noGrace && !startedInHours) {
            // v1.39 — M76: outside the On hours a person at the tablet woke the piano: kept for the next piece, the CUT
            // after [grace] (its own end comes back here with nothing struck since: no check, no second grace).
            log("Rail: the check after the piece passed outside the On hours; kept ${grace / MS_PER_S} s for the next piece")
            put(GRACE, until = now() + grace)
            graceGiven = true
            watchSince = now()
            back = true
        }
        noGrace = false
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

    /** The supply's draw now, W (since v1.36 — M72 the SwitchBot's latest advert, under 3 s old); null unread. */
    private suspend fun idleReading(): Double? = plug.reading()?.watts?.takeIf { it.isFinite() && it >= 0.0 }

    /** This rail-up's power watch, from its [idle] reading; its first look, at once, asks `!i2cfails`. */
    private fun startWatch(idle: Double?) {
        powerWatch = PowerWatch(idle)
        boardWatch = BoardWatch(idle)
        watchSince = now()
        looks = 0
        failsAsked = false
        failsSeen = null
        failsAt = Long.MAX_VALUE
        wasPlaying = false
        plugMisses = 0
        plugCheckAt = Long.MAX_VALUE
        floorAt = now()
        wattsAt = now()
        idleKept = false
        lookAt = now()
        log("Power watch: idle ${PowerWatch.watts(idle)} W at rail-up")
    }

    /** A piece plays (its want holds): the floor watch reads only then. */
    private fun pieceNow(): Boolean = wants[PLAY]?.let { live(it, now()) } == true

    /**
     * The power watch's look while the rail is up (v1.30 — M67; every second since v1.39 — M76): the rail-up's `!i2cfails`
     * first (what the check after the piece compares). Then (v1.39 — M76) `!i2cfails` again every [FAILS_PLAYING_MS] during
     * a piece and every [FAILS_IDLE_MS] idle (one small console line, which the link sends only between MIDI packets: the
     * notes keep their pacing), any rise a board that lost contact; during a piece the plug's own status every
     * [PLUG_CHECK_MS] ([plugCheck]); and the draw from the plug's adverts: during a piece for the stuck-board watch
     * ([BoardWatch.playing]) and, at its own 1.5–2.5 s, the floor watch ([PowerWatch.reading]); idle (no note for
     * [IDLE_AFTER_MS], no key held, the pedal up) for the idle watch ([BoardWatch.idleReading]). A piece that ended with
     * the rail staying up (the On hours) gets the check after it here. True when the rail ended: a fault found (CUT, said,
     * the check due), or the plug lost.
     */
    private suspend fun look(): Boolean {
        val watch = powerWatch
        val board = boardWatch
        val host = power
        if (watch == null || board == null || host == null) {
            lookAt = Long.MAX_VALUE
            return false
        }
        val t = now()
        // On a steady 1 s grid: a look the keeper's own CHECK and ARM delayed keeps its slot, so the watches' runs are
        // timed by the second (the reading is the plug's latest advert either way).
        val slot = if (lookAt <= t && t - lookAt < BoardWatch.READ_EVERY_MS) lookAt else t
        lookAt = slot + BoardWatch.READ_EVERY_MS
        if (!failsAsked) {
            failsAsked = true
            val fails = host.ask(I2C_FAILS)?.trim()?.toLongOrNull()
            watch.failsAtRailUp = fails
            failsSeen = fails
            failsAt = now() + FAILS_IDLE_MS
            return false
        }
        val playing = pieceNow()
        if (playing != wasPlaying) {
            wasPlaying = playing
            board.pause()
            if (playing) {
                plugCheckAt = t + PLUG_CHECK_MS
                failsAt = minOf(failsAt, t + FAILS_PLAYING_MS)
            } else {
                watch.pause()
                plugCheckAt = Long.MAX_VALUE
                if (host.struckSince(watchSince) && afterPieceUp(watch)) return true
                if (powerWatch !== watch) return false
            }
        }
        if (now() >= failsAt) {
            failsAt = now() + if (playing) FAILS_PLAYING_MS else FAILS_IDLE_MS
            val fails = host.ask(I2C_FAILS)?.trim()?.toLongOrNull()
            if (powerWatch !== watch || !wantedNow()) return false
            val before = failsSeen
            if (fails != null && before != null && fails > before) {
                log("Power watch: !i2cfails $before → $fails with the rail up" + if (pieceNow()) " mid-piece" else " and idle")
                return cutAtOnce(RailMessage.BoardLost(before, fails))
            }
            if (fails != null) {
                failsSeen = fails   // fewer: the controller started again, its count with it
                watch.failsAtRailUp = fails
            }
        }
        if (playing && now() >= plugCheckAt && plugCheck()) return true
        if (powerWatch !== watch || !wantedNow()) return false
        val reading = plug.reading()?.takeIf { it.watts.isFinite() }
        if (powerWatch !== watch || !wantedNow()) return false
        if (reading == null || reading.at <= wattsAt) {
            // No advert since the last reading: nothing new to weigh (only fresh adverts count).
            if (!_readingLost.value && slot - wattsAt > PlugAdverts.SILENT_MS) {
                _readingLost.value = true
                log("Power watch: no reading from the plug for ${(slot - wattsAt) / MS_PER_S} s with the rail up; the 24 V stays on, the piano's own checks go on")
            }
            return false
        }
        wattsAt = reading.at
        _readingLost.value = false
        val asked = reading.at   // when the plug said it: the keys and pedal are weighed against that moment
        val watts = reading.watts
        val fullPower = host.fullPower
        if (pieceNow()) {
            // The stuck-board rule: the music's draw over the 3 s before the advert (it can trail the switch by seconds),
            // so a passage starting or ending never looks unexplained.
            val wide = PowerWatch.sample(asked, now(), watts, host::held, host::pedalDown, fullPower, lagMs = BoardWatch.LAG_MS)
            when (val verdict = board.playing(wide)) {
                is PowerVerdict.BoardOn -> {
                    log("Power watch: a board stayed on mid-piece — ${verdict.why}")
                    return cutAtOnce(RailMessage.BoardOn(verdict.above.roundToInt(), null, playing = true))
                }
                else -> Unit
            }
            if (asked < floorAt) return false
            floorAt = asked + PowerWatch.lookIn(looks++)
            val sample = PowerWatch.sample(asked, now(), watts, host::held, host::pedalDown, fullPower)
            val stuck = watch.reading(sample) as? PowerVerdict.Stuck ?: return false
            // v1.39 — M76: logged only, never a CUT (a false trip on the real piano: the adverts' timing and scale are
            // the bench's to prove). While playing only the board rule and `!i2cfails` cut.
            log("Power watch (log only, no CUT): the floor watch would have said a key stayed on mid-piece — ${stuck.why}")
            watch.pause()
            return false
        }
        val idle = _phase.value == RailPhase.Up && !host.struckSince(asked - IDLE_AFTER_MS) &&
            host.held(asked - IDLE_AFTER_MS, asked) <= 0.0 && !host.pedalDown(asked - PowerWatch.PEDAL_MS, asked)
        if (!idle) {
            board.notIdle()
            return false
        }
        return when (val verdict = board.idleReading(asked, watts, fullPower)) {
            is PowerVerdict.BoardOn -> {
                log("Power watch: a board stayed on — ${verdict.why}")
                cutAtOnce(RailMessage.BoardOn(verdict.above.roundToInt(), verdict.idle?.roundToInt(), playing = false))
            }
            is PowerVerdict.CoilOn -> {
                log("Power watch: a key stayed on — ${verdict.why}")
                cutAtOnce(RailMessage.CoilOn(verdict.above.roundToInt(), verdict.idle.roundToInt(), (verdict.ms / MS_PER_S).toInt()))
            }
            else -> {
                // v1.31 — M68's usual idle, learned (v1.39 — M76) only from an idle reading at least 5 s after the
                // piano's link came up: the controller now boots on the plug too.
                if (!idleKept && asked >= maxOf(linkUpAt, watchSince) + IDLE_LEARN_AFTER_MS && verdict == PowerVerdict.Fine && board.learned(watts)) {
                    idleKept = true
                    host.keepIdle(watts)
                }
                false
            }
        }
    }

    /**
     * v1.39 — M76: the check after a piece while the rail stays up (the On hours, or another want): as at the stop
     * ([afterPiece]), without its CUT; a new piece starting first ends it. True when it found a key on: the stop, CUT,
     * said, the check due.
     */
    private suspend fun afterPieceUp(watch: PowerWatch): Boolean {
        val after = afterPiece(watch, now()) { pieceNow() || !wantedNow() } ?: return false
        watchSince = now()
        val cut = after.cut ?: return false
        fail(cut)
        power?.checkDue()
        return true
    }

    /**
     * v1.39 — M76: a fault the draw or the piano's I²C count proves (a board on, a coil on at idle, a board lost): the 24 V
     * CUT first, at once, then what plays stopped and the stop sequence; [why] said, the safety check due. Always true.
     */
    private suspend fun cutAtOnce(why: RailMessage): Boolean {
        log("Rail: $why; CUT at once")
        consumedThrough = nextSeq - 1
        _manualUntil.value = null
        cutRail()
        stopPlaying()
        stopSequence()
        _baseline.value = null
        needsBaseline = false
        _message.value = why
        setPhase(RailPhase.Off)
        power?.checkDue()
        return true
    }

    /**
     * v1.39 — M76, the plug's check while the rail is up (every [PLUG_CHECK_MS] during a piece, and at once on an "off"
     * advert): its status over a short GATT connection. On, nothing. Off: [rewake]. Unanswered [PLUG_MISSES] times in a
     * row: the music stops, CUT (best effort), "Lost the power plug". True when the rail ended.
     */
    private suspend fun plugCheck(): Boolean {
        plugCheckAt = now() + PLUG_CHECK_MS
        val status = plug.status()
        if (!wantedNow() || !available.value || _phase.value != RailPhase.Up) return false
        if (status == null) {
            if (++plugMisses < PLUG_MISSES) return false
            log("Plug: $PLUG_MISSES checks in a row unanswered with the 24 V wanted")
            fail(RailMessage.PlugLost)
            return true
        }
        plugMisses = 0
        if (status.output) return false
        log("Plug: it says off while the 24 V should be up; notes held, ARM again")
        return !rewake()
    }

    /**
     * v1.39 — M76: the plug found off with the rail wanted up (and the piano's controller off with it). Notes held (a piece
     * pauses where it is, [RailHold]), ARM until the plug confirms on, the piano's link awaited as at a wake (30 s; its
     * old connection may not have noticed the drop yet, so a CHECK unanswered waits for the new one), the CHECK, and the
     * baseline's boards; then up again and the piece resumes. True when back up; false when it ended (failed and said, or
     * no longer wanted).
     */
    private suspend fun rewake(): Boolean {
        val previous = _baseline.value
        setPhase(RailPhase.Waking)
        _message.value = RailMessage.Waking
        when (armConfirmed()) {
            null -> return quietly(true)
            false -> return fail(RailMessage.PlugLost)
            true -> Unit
        }
        val by = now() + WAKE_LINK_MS
        val armed = _armedAt.value ?: now()
        if (waitFor(armed + CONTROLLER_FED_MS) { !wantedNow() }) return quietly(true)
        var read: Boards? = null
        while (read == null) {
            if (!connected.value) {
                wakeLink()
                waitFor(minOf(by, now() + WAKE_ASK_EVERY_MS)) { connected.value || !wantedNow() }
            }
            if (!wantedNow()) return quietly(true)
            if (connected.value) read = check()
            if (!wantedNow()) return quietly(true)
            if (read != null) break
            if (now() >= by) {
                log("Rail: the piano didn't answer within ${WAKE_LINK_MS / MS_PER_S} s of the ARM; CUT")
                return fail(RailMessage.PianoDidntStart)
            }
            if (connected.value && waitFor(now() + RAIL_UP_EVERY_MS) { !wantedNow() }) return quietly(true)
        }
        val boards = read ?: return false
        _boards.value = boards
        if (previous != null && !boards.holds(previous)) return fail(RailMessage.TrunkFault(boards.answered, boards.total, cut = true))
        // The controller started again: its I²C count with it, and the draw's runs.
        failsAsked = false
        powerWatch?.pause()
        boardWatch?.pause()
        wasPlaying = false
        log("Rail: up again after the plug was found off")
        setPhase(RailPhase.Up)
        return true
    }

    /** The check after the piece's verdict: [cut], the trip's words, or null when it passed. */
    private class AfterCheck(val cut: RailMessage?)

    /**
     * The check after the piece (SMART_PLUG.md › rule 11 b; v1.39 — M76 after a false trip on the real piano), from the stop
     * sequence (or the piece's end, the rail staying up) at [stopped]: [AFTER_FRESH_MS] later the piano's `!active` and
     * `!i2cfails` (a coil on, or more failures than at the rail-up: a trip at once, the direct signals); then three of the
     * plug's adverts about a second apart, each one that came at least [AFTER_FRESH_MS] after the stop (never one from the
     * piece's last notes: the adverts can trail the switch by seconds), [AFTER_LONGEST_MS] at most. On the draw only when all
     * three are at least [BoardWatch.BOARD_IDLE_WATTS] above the idle (the board-trip rule); fewer read, the piano's facts
     * decide. Logged with the readings and the idle used. Null when [again] (wanted again, or the next piece) came first.
     */
    private suspend fun afterPiece(watch: PowerWatch, stopped: Long, again: () -> Boolean): AfterCheck? {
        val host = power ?: return AfterCheck(null)
        if (waitFor(stopped + AFTER_FRESH_MS, again)) return null
        val active = host.ask(ACTIVE)?.trim()?.toIntOrNull()
        val fails = host.ask(I2C_FAILS)?.trim()?.toLongOrNull()
        val idle = boardWatch?.idle ?: watch.idle
        val readings = ArrayList<Double>(AFTER_READINGS)
        var direct = watch.afterPiece(emptyList(), active, fails, idle)
        if (direct == null) {
            var since = stopped + AFTER_FRESH_MS
            while (readings.size < AFTER_READINGS && now() < stopped + AFTER_LONGEST_MS) {
                val reading = plug.reading()
                if (reading != null && reading.at >= since) {
                    readings += reading.watts
                    since = reading.at + AFTER_APART_MS - AFTER_POLL_MS
                }
                if (readings.size < AFTER_READINGS && waitFor(now() + AFTER_POLL_MS, again)) return null
            }
            direct = watch.afterPiece(readings, active, fails, idle)
        }
        log(
            "Power watch after the piece: ${readings.joinToString(", ") { "${PowerWatch.watts(it)} W" }.ifEmpty { "no fresh reading" }} " +
                "(idle ${PowerWatch.watts(idle)} W; a trip at ${PowerWatch.watts(BoardWatch.BOARD_IDLE_WATTS)} W above it on all " +
                "$AFTER_READINGS), !active ${active ?: "—"}, !i2cfails ${fails ?: "—"} (${watch.failsAtRailUp ?: "—"} at rail-up); " +
                (direct?.let { "24 V cut: $it" } ?: "fine"),
        )
        return AfterCheck(direct)
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

    /** Ends everything: the wants so far (Turn the piano on's too), what plays, then the stop sequence and CUT. */
    private suspend fun end() {
        consumedThrough = nextSeq - 1
        _manualUntil.value = null
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
     * One look at the plug while nothing wants the rail (v1.29 — M66; every [WATCH_EVERY_MS]): its status, one small read
     * (the SwitchBot's GATT status since v1.36 — M72). Found on: its settings put back first, then CUT with its weld look,
     * and "switched on by something else" said. Its settings are looked at too the first time, after the plug was out of
     * reach, and every [SETTINGS_EVERY_MS]; changed ones are put back and said (the SwitchBot keeps none: nothing to put
     * back). Never while the rail is wanted (the keeper talks to the plug every 10 s then) or a firmware update runs; a plug
     * out of reach says nothing new. The adverts ([advert]) usually CUT a plug found on long before this look.
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
     * CUT, best effort. Then, a few seconds on and unless the rail was armed again meanwhile, whether the relay really let
     * go: the supply still drawing more than [weldedAbove] is a relay stuck closed ([welded]); the plug's `output` reads off
     * either way. Before v1.36 a CUT that failed was bounded by the plug's 30 s timer; the SwitchBot has none (M72), so the
     * 24 V may stay on: the plug's next "on" advert ([advert]) or the idle look ([watch]) CUTs again.
     */
    private suspend fun cutRail(): Boolean {
        cutAt = now()
        if (plug.cut()) {
            cutAt = now()
            _armedAt.value = null
            weldJob?.cancel()
            weldJob = scope.launch {
                delay(WELD_CHECK_MS)
                if (!available.value || _armedAt.value != null) return@launch
                val status = plug.status() ?: return@launch
                if (_armedAt.value != null) return@launch
                val above = weldedAbove()
                weldSeen = (status.watts ?: 0.0) > above
                showWelded()
                if (weldSeen) log("After a CUT the supply still draws ${status.watts} W (above $above W): the plug's relay may be stuck")
            }
            return true
        }
        cutAt = now()
        log("CUT: the plug didn't answer; the 24 V may still be on (the plug has no timer): its next \"on\" advert CUTs again")
        return false
    }

    /** [welded] from its two causes (v1.36 — M72). */
    private fun showWelded() {
        _welded.value = weldSeen || cutsDontTake
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

    /**
     * Whether the rail may be up from an ARM: one taken with no CUT taken since. (Before v1.36 only within the plug's 30 s
     * timer; the SwitchBot has none, M72, so an ARM holds until a CUT.)
     */
    private fun mayBeUp(): Boolean = _armedAt.value != null

    // ---- The wants ---------------------------------------------------------------------------------------------------

    private fun put(reason: String, until: Long = Long.MAX_VALUE, unchecked: Long = Long.MIN_VALUE) {
        if (reason != GRACE) {
            // v1.39 — M76: something new wants the rail: its end may earn a grace, unless it began within the On hours.
            graceGiven = false
            noGrace = false
            startedInHours = reason == HOURS || wants[HOURS]?.let { live(it, now()) } == true
            if (reason != HOURS) wants.remove(GRACE)   // the next piece came: its own end earns the next grace
        }
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
            boardWatch = null
            lookAt = Long.MAX_VALUE
            plugCheckNow = false
            _readingLost.value = false
        }
        refreshHeld()
        if (phase != RailPhase.Waking && _message.value == RailMessage.Waking) _message.value = null
    }

    /**
     * Notes wait while the plug switches the rail and it isn't up. v1.38 — M75: with no piano connected too (its controller
     * is on the plug, so it is off): a piece started then is paused at its start ([RailHold]) while the rail-up wakes it.
     */
    private fun refreshHeld() {
        val phase = _phase.value
        _held.value = available.value && phase != RailPhase.Up && phase != RailPhase.Kept
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
        /**
         * The seconds each ARM asks for: a plug with its own timer would switch itself off this long after the last ARM
         * (the Shelly did, before v1.36). The SwitchBot has none (M72): it is sent, and ignored.
         */
        const val TOGGLE_AFTER_S = 30
        const val KEEP_EVERY_MS = 10_000L
        const val RAIL_UP_EVERY_MS = 500L
        const val RAIL_UP_MS = 10_000L
        const val RETRY_MS = 2_000L
        const val STOP_TO_CUT_MS = 1_000L
        const val CONTROLLER_FED_MS = 1_500L
        const val REPOWER_OFF_MS = 3_000L

        /**
         * v1.38 — M75: waking the piano from off. Its link awaited this long after a confirmed ARM, and asked for again this
         * often meanwhile (a scan that ended before the controller advertised); an ARM tried this many times, this far apart,
         * until the plug confirms on.
         */
        const val WAKE_LINK_MS = 30_000L
        const val WAKE_ASK_EVERY_MS = 5_000L
        const val ARM_TRIES = 3
        const val ARM_AGAIN_MS = 1_000L

        /** v1.38 — M75: Turn the piano on's want ends by itself after this long without notes. */
        const val MANUAL_IDLE_MS = 30 * 60_000L

        /**
         * v1.39 — M76: the piano's `!i2cfails` read this often during a piece and idle; the plug's own status this often
         * during a piece, and this many unanswered in a row are the plug lost; idle is this long without a note.
         */
        const val FAILS_PLAYING_MS = 5_000L
        const val FAILS_IDLE_MS = 30_000L
        const val PLUG_CHECK_MS = 5_000L
        const val PLUG_MISSES = 3
        const val IDLE_AFTER_MS = 2_000L

        /** v1.39 — M76: the grace between pieces outside the On hours. */
        const val GRACE_MS = 2 * 60_000L

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
         * The advert watch (v1.36 — M72): adverts this soon after a CUT may have been sent before it; this many CUTs in a row
         * with the plug still saying on show [welded]; a new connection this soon after a CUT is that CUT's own.
         */
        const val ADVERT_SETTLE_MS = 1_500L
        const val ADVERT_CUTS = 3
        const val CONNECT_CUT_GAP_MS = 5_000L

        /**
         * The power watch (v1.30 — M67): a reading above the cap at rail-up is looked at again this much later; the check
         * after the piece takes its readings this far apart (its first a second after the stop sequence, as the CUT was).
         */
        const val CAP_AGAIN_MS = 1_000L
        const val AFTER_APART_MS = 1_000L

        /**
         * v1.39 — M76, the check after the piece: only adverts that came this long after the stop count; this many, about
         * [AFTER_APART_MS] apart, looked for every [AFTER_POLL_MS], until this long after the stop at most.
         */
        const val AFTER_FRESH_MS = 1_500L
        const val AFTER_READINGS = 3
        const val AFTER_POLL_MS = 250L
        const val AFTER_LONGEST_MS = 7_000L

        /** v1.39 — M76: the usual idle learned only from a reading this long after the piano's link came up. */
        const val IDLE_LEARN_AFTER_MS = 5_000L

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
        const val MANUAL = "manual"

        /** v1.39 — M76: the On hours' standing want, the grace between pieces, the firmware updater waking the piano. */
        const val HOURS = "hours"
        const val GRACE = "grace"
        const val UPDATE_WAKE = "updatewake"

        private const val MINUTE_MS = 60_000L
        private const val MS_PER_S = 1_000L
    }
}
