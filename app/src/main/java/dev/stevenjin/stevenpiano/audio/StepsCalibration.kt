// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.piano.LoudnessSteps
import dev.stevenjin.stevenpiano.ui.ListenCopy
import dev.stevenjin.stevenpiano.ui.StepsCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a calibration stands: the 24 V coming up, the room heard, the keys struck, the steps written. */
enum class StepsPhase { Rail, Room, Keys, Writing }

/** A calibration that ran to its end: each calibrated key's [rows] (its PWMs), and the keys left [uncalibrated], low to high. */
data class StepsResult(val rows: Map<Int, List<Int>>, val uncalibrated: List<Int>) {
    /** How many keys got each number of steps. */
    val counts: Map<Int, Int> get() = rows.values.groupingBy { it.size }.eachCount()

    /** The longest row: what `velsteps` is set to (shorter rows still work: the bands map onto them). */
    val longest: Int get() = rows.values.maxOfOrNull { it.size } ?: 0
}

/**
 * The calibration now: [running], its [phase], the [key] being struck (MIDI) and its [number] of [total], about how long is
 * [leftMs], the [progress] (0..1); when it ended, its [result] or why it [stopped], and how many keys had changed by then
 * ([written]; and whether Full power had gone off, [fullPowerOff]); [shown] while the card is up (running, or its end not
 * yet dismissed).
 */
data class StepsState(
    val running: Boolean = false,
    val phase: StepsPhase? = null,
    val key: Int? = null,
    val number: Int = 0,
    val total: Int = StepsCalibration.TOTAL,
    val leftMs: Long? = null,
    val progress: Float = 0f,
    val result: StepsResult? = null,
    val stopped: String? = null,
    val written: Int = 0,
    val fullPowerOff: Boolean = false,
    val shown: Boolean = false,
)

/**
 * Find the loudness steps (M83, piano firmware 2.1.5; `firmware/docs/BLE_SETTINGS.md` › "Distinct loudness steps"). Steven
 * chose fixed steps, as many clearly different ones as each key's window holds, found by the tablet's microphone, at least
 * 4 dB apart, as the stock on both pianos. The piano strikes each of its 84 keys ([KEYS], low to high) with `calstrike`
 * while the microphone listens ([StepsTake]), and [StepPicker] chooses each key's steps:
 *
 * - **Before**: refused while a piece plays (pause it first), a quiet time holds, guest play is on, the firmware updates, the
 *   app isn't in front (the microphone needs it), the key test or the safety check has the microphone, or the power plug
 *   can't be trusted; and on a piano without `!stepsproto=1`, or that doesn't let this tablet write. Then [before] (the 24 V
 *   wanted with a reason of its own, awaited up), and the microphone with its banner, the tablet's own piano sound silent.
 * - **The room**: its noise for [ROOM_MS]; louder than [ROOM_TOO_LOUD_DB], it says so and stops.
 * - **Each key**: the coarse look, one strike per level ([StepPicker.COARSE]); then a finer look, two strikes each 100 apart,
 *   below where it first cleared the room and below where it stopped getting louder. One `calstrike` every [STRIKE_EVERY_MS]
 *   (the firmware allows one every 300 ms); on "too soon" it waits and sends it again. About 9 to 10 minutes in all.
 * - **Writing only at the end**: every key done, the uncalibrated keys' old rows cleared, one `steptable` per calibrated
 *   key, then `velsteps N` (the longest row), `fullpower 0` and `save`, each answered before the next; then the piano is
 *   read again. Stopped before, nothing is written.
 * - **It stops at once**, saying why, when the link drops, the piano answers anything not in the spec (any refusal but
 *   "too soon", or nothing within the wait), the 24 V isn't up, a piece starts, a quiet time or guest play begins, the
 *   firmware updates, the app leaves the front, the microphone stops, the plug can't be trusted, or Stop is pressed. Every
 *   end, a stop or the result, comes with the player's stop sequence.
 *
 * Main thread ([scope]'s); [nanos] is `System.nanoTime`'s clock, which the microphone's frames carry.
 */
class StepsCalibration(
    private val scope: CoroutineScope,
    private val piano: Piano,
    private val ears: Ears,
    private val nanos: () -> Long = System::nanoTime,
    private val before: suspend () -> Boolean = { true },
    /** The keys it calibrates, low to high: the piano's 84 ([KEYS]); a few in the tests. */
    private val keys: IntRange = KEYS,
) {
    private val total = keys.count()

    /** The piano as the calibration strikes and writes it (the app's wiring; a stand-in in the tests). Main thread. */
    interface Piano {
        /** Steven Piano is connected. */
        val connected: Boolean

        /** Its dump says `!stepsproto=1`. */
        val steps: Boolean

        /** Its Bluetooth lock lets this tablet write (every step line needs a trusted connection on a claimed piano). */
        val writable: Boolean

        /** A piece plays, or loads to play. */
        val playing: Boolean

        /** While a quiet time holds the piano and nobody lifted it, its words; else null. */
        fun quiet(): String?

        /** Guest play is on (the piano's lock says so, or this tablet let go of the piano for it). */
        val guest: Boolean

        /** The piano's firmware is being updated. */
        val updating: Boolean

        /** The app is in the foreground (the microphone hears only then). */
        val inFront: Boolean

        /** The key test or the safety check runs (they have the microphone): its words; else null. */
        fun busy(): String?

        /** Why the power plug can't be trusted now, in words; null when it can (or there is no plug). */
        fun plugUnsafe(): String?

        /** The 24 V is up (always, without the power plug). */
        val railUp: Boolean

        /** [line] sent on the piano's console, and its own reply ([LoudnessSteps.answers]); null when none came in time. */
        suspend fun send(line: String): String?

        /** The player's stop sequence, at once. */
        fun stopSequence()

        /** The tablet's own piano sound held silent ([on]), or let be again. */
        fun muteTablet(on: Boolean)

        /** The piano's settings and facts read again (after the steps were written). */
        fun reread()
    }

    private val _state = MutableStateFlow(StepsState())
    val state: StateFlow<StepsState> = _state.asStateFlow()

    /** Whether a calibration runs now. */
    val running: Boolean get() = _state.value.running

    private var job: Job? = null
    private var tapped = false

    /** The last strike that went, and the first (`nanos`), and how many have gone: the pace, and the time left. */
    private var lastStrikeAt: Long? = null
    private var firstStrikeAt: Long? = null
    private var strikes = 0

    /** What has changed on the piano so far (rows cleared or written), and whether Full power went off. */
    private var written = 0
    private var fullPowerOff = false

    /** Begins, or is refused with its reason. */
    fun start(): ListenAnswer {
        refusal()?.let { return it }
        if (!ears.allowed(null)) return ListenAnswer.Refused(ListenAnswer.OFF, ListenCopy.NO_PERMISSION)
        val take = StepsTake()
        val heard = ears.tap(null, take::add) { end(StepsCopy.STOPPED_MICROPHONE) }
        if (heard is ListenAnswer.Refused) return heard
        tapped = true
        piano.muteTablet(true)
        lastStrikeAt = null
        firstStrikeAt = null
        strikes = 0
        written = 0
        fullPowerOff = false
        _state.value = StepsState(running = true, phase = StepsPhase.Rail, total = total, shown = true)
        job = scope.launch { run(take) }
        return ListenAnswer.Started
    }

    /** Stop: it ends at once with the player's stop sequence, and nothing more is written. */
    fun stop() = end(StepsCopy.STOPPED)

    /** The card's OK: its end goes. Nothing while it runs. */
    fun dismiss() {
        if (!running) _state.value = StepsState()
    }

    private fun refusal(): ListenAnswer.Refused? {
        if (running) return ListenAnswer.Refused(RUNNING, StepsCopy.RUNNING)
        if (!piano.connected) return ListenAnswer.Refused(ListenAnswer.NOT_CONNECTED, StepsCopy.NOT_CONNECTED)
        if (!piano.steps) return ListenAnswer.Refused(NO_STEPS, StepsCopy.NO_STEPS)
        if (!piano.writable) return ListenAnswer.Refused(READ_ONLY, StepsCopy.READ_ONLY)
        if (piano.playing) return ListenAnswer.Refused(ListenAnswer.PLAYING, StepsCopy.PLAYING)
        piano.quiet()?.let { return ListenAnswer.Refused(ListenAnswer.QUIET, it) }
        if (piano.guest) return ListenAnswer.Refused(GUEST, StepsCopy.GUEST)
        if (piano.updating) return ListenAnswer.Refused(ListenAnswer.UPDATING, StepsCopy.UPDATING)
        if (!piano.inFront) return ListenAnswer.Refused(ListenAnswer.AWAY, StepsCopy.AWAY)
        piano.busy()?.let { return ListenAnswer.Refused(ListenAnswer.TESTING, it) }
        piano.plugUnsafe()?.let { return ListenAnswer.Refused(PLUG, it) }
        return null
    }

    /** Why it can't go on now; null while it may. */
    private fun interruption(): String? = when {
        !piano.connected -> StepsCopy.STOPPED_DISCONNECTED
        piano.playing -> StepsCopy.STOPPED_PLAYING
        piano.quiet() != null -> StepsCopy.STOPPED_QUIET
        piano.updating -> StepsCopy.STOPPED_UPDATING
        piano.guest -> StepsCopy.STOPPED_GUEST
        !piano.inFront -> StepsCopy.STOPPED_AWAY
        piano.plugUnsafe() != null -> StepsCopy.STOPPED_PLUG
        !piano.railUp -> StepsCopy.STOPPED_RAIL
        else -> null
    }

    private suspend fun run(take: StepsTake) {
        val ready = try {
            before()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ready) return end(StepsCopy.STOPPED_RAIL)
        interruption()?.let { return end(it) }

        // The room: its noise, for the floor's margin.
        _state.update { it.copy(phase = StepsPhase.Room, leftMs = estimate(0, 0)) }
        val roomFrom = nanos()
        if (!pause(ROOM_MS)) return
        val roomTo = nanos()
        val room = heard { take.room(roomFrom, roomTo) } ?: return end(StepsCopy.STOPPED_MICROPHONE)
        if (room > ROOM_TOO_LOUD_DB) return end(StepsCopy.TOO_LOUD)

        // Each key, low to high.
        _state.update { it.copy(phase = StepsPhase.Keys) }
        for ((i, key) in keys.withIndex()) {
            _state.update { it.copy(key = key, number = i + 1, progress = i.toFloat() / total, leftMs = estimate(i, 0)) }
            if (!sweep(take, key, i, room)) return
        }
        heard { if (take.unmeasured() == 0) true else null } ?: return end(StepsCopy.STOPPED_MICROPHONE)
        listenNoMore()

        val levels = take.levels().groupBy { it.key }
        val picks = keys.map { key -> StepPicker.pick(key, levels[key].orEmpty().map { StrikeLevel(it.pwm, it.db) }, room) }
        write(picks)
    }

    /**
     * Key [key] (the [index]th): the coarse look, soft to hard, with the finer look at its floor struck as soon as the coarse
     * levels heard so far show where it first clears the room (a strike or two later, its sound still being heard: so the
     * strike before a soft one is never a loud one, whose ring would cover it); then the finer look at its top. A key whose
     * floor shows only once the coarse look is over gets [SETTLE_MS] first, for the loudest strike's ring to die away. False
     * when it ended.
     */
    private suspend fun sweep(take: StepsTake, key: Int, index: Int, room: Double): Boolean {
        var struck = 0
        suspend fun hit(pwm: Int): Boolean {
            if (!strike(take, key, pwm)) return false
            struck++
            _state.update { it.copy(progress = (index + struck.toFloat() / PER_KEY).coerceAtMost(index + 1f) / total, leftMs = estimate(index, struck)) }
            return true
        }
        var floor: Int? = null
        for (pwm in StepPicker.COARSE) {
            if (!hit(pwm)) return false
            if (floor != null) continue
            floor = StepPicker.firstClear(coarse(take, key), room) ?: continue
            for (fine in StepPicker.fineFloor(floor)) if (!hit(fine)) return false
        }
        if (floor == null) {
            var clear: Int? = null
            heard {
                val levels = coarse(take, key)
                clear = StepPicker.firstClear(levels, room)
                if (clear != null || StepPicker.neverClears(levels, room)) true else null
            } ?: run {
                end(StepsCopy.STOPPED_MICROPHONE)
                return false
            }
            floor = clear ?: return true   // never heard: no finer look, and it stays uncalibrated
            if (!pause(SETTLE_MS)) return false
            for (fine in StepPicker.fineFloor(floor)) if (!hit(fine)) return false
        }
        val clears: Int = floor
        val knee = heard {
            val levels = coarse(take, key)
            if (levels.all { it != null }) {
                StepPicker.knee(StepPicker.COARSE.zip(levels).map { (pwm, db) -> StrikeLevel(pwm, db!!) }, clears) ?: clears
            } else {
                null
            }
        } ?: run {
            end(StepsCopy.STOPPED_MICROPHONE)
            return false
        }
        for (pwm in StepPicker.fineTop(knee)) if (!hit(pwm)) return false
        return true
    }

    /** Key [key]'s coarse levels so far, in [StepPicker.COARSE]'s order (each level's first strike), null where not heard yet. */
    private fun coarse(take: StepsTake, key: Int): List<Double?> {
        val heard = take.levels().filter { it.key == key }
        return StepPicker.COARSE.map { pwm -> heard.firstOrNull { it.pwm == pwm }?.db }
    }

    /** One `calstrike` of [key] at [pwm], paced, sent again after "too soon". False when it ended. */
    private suspend fun strike(take: StepsTake, key: Int, pwm: Int): Boolean {
        val line = LoudnessSteps.strike(key, pwm)
        var soon = 0
        while (true) {
            lastStrikeAt?.let { last ->
                val wait = last + STRIKE_EVERY_MS * NANOS_PER_MS - nanos()
                if (wait > 0) delay((wait + NANOS_PER_MS - 1) / NANOS_PER_MS)
            }
            interruption()?.let {
                end(it)
                return false
            }
            val at = nanos()
            val reply = piano.send(line)
            if (!running) return false
            when (val read = reply?.let { LoudnessSteps.read(line, it) }) {
                null -> {
                    end(StepsCopy.STOPPED_SILENT)
                    return false
                }
                is LoudnessSteps.Reply.Struck -> {
                    lastStrikeAt = at
                    if (firstStrikeAt == null) firstStrikeAt = at
                    strikes++
                    take.strike(key, pwm, at)
                    return true
                }
                LoudnessSteps.Reply.TooSoon -> {
                    if (++soon > TOO_SOON_TRIES) {
                        end(StepsCopy.STOPPED_TOO_SOON)
                        return false
                    }
                    delay(TOO_SOON_WAIT_MS)
                }
                LoudnessSteps.Reply.Done -> {
                    end(StepsCopy.refused(reply.orEmpty().trim()))
                    return false
                }
                is LoudnessSteps.Reply.Other -> {
                    end(StepsCopy.refused(read.text))
                    return false
                }
            }
        }
    }

    /** Every key done: the uncalibrated keys' rows cleared, a row per calibrated key, then `velsteps`, `fullpower 0`, `save`. */
    private suspend fun write(picks: List<KeySteps>) {
        val rows = picks.mapNotNull { pick -> pick.pwms?.let { pick.key to it } }.toMap()
        val uncalibrated = picks.filter { it.pwms == null }.map { it.key }
        if (rows.isEmpty()) return end(StepsCopy.NONE_FOUND)
        _state.update { it.copy(phase = StepsPhase.Writing, key = null, progress = 1f, leftMs = null) }
        for (key in uncalibrated) {
            if (!say(LoudnessSteps.clear(key))) return
            written++
        }
        for ((key, pwms) in rows) {
            if (!say(LoudnessSteps.row(key, pwms))) return
            written++
        }
        val result = StepsResult(rows, uncalibrated)
        if (!say(LoudnessSteps.steps(result.longest))) return
        if (!say(LoudnessSteps.FULL_POWER_OFF)) return
        fullPowerOff = true
        if (!say(LoudnessSteps.SAVE)) return
        piano.reread()
        finish(result)
    }

    /** One line written and its reply read: what the spec says it says, or the calibration ends. */
    private suspend fun say(line: String): Boolean {
        interruption()?.let {
            end(it)
            return false
        }
        val reply = piano.send(line)
        if (!running) return false
        return when (val read = reply?.let { LoudnessSteps.read(line, it) }) {
            null -> false.also { end(StepsCopy.STOPPED_SILENT) }
            LoudnessSteps.Reply.Done -> true
            is LoudnessSteps.Reply.Other -> false.also { end(StepsCopy.refused(read.text)) }
            else -> false.also { end(StepsCopy.refused(reply.orEmpty().trim())) }
        }
    }

    /** [ms] of waiting, looked at every 100 ms. False when it ended meanwhile. */
    private suspend fun pause(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            val step = minOf(left, LOOK_MS)
            delay(step)
            left -= step
            interruption()?.let {
                end(it)
                return false
            }
        }
        return true
    }

    /**
     * What [probe] says once the microphone has heard enough (non-null), looked at every 20 ms; null when the microphone has
     * heard nothing new for [HEARD_WAIT_MS], or it ended meanwhile.
     */
    private suspend fun <T> heard(probe: () -> T?): T? {
        var waited = 0L
        while (true) {
            probe()?.let { return it }
            if (!running) return null
            interruption()?.let {
                end(it)
                return null
            }
            if (waited >= HEARD_WAIT_MS) return null
            delay(PROBE_MS)
            waited += PROBE_MS
        }
    }

    /** About how long is left, from key [index] with [struck] of its strikes gone, at the pace so far. */
    private fun estimate(index: Int, struck: Int): Long {
        val first = firstStrikeAt
        val pace = if (first != null && strikes >= PACE_AFTER) (nanos() - first) / NANOS_PER_MS / (strikes - 1).coerceAtLeast(1) else STRIKE_EVERY_MS + PACE_SLACK_MS
        val left = (total - index - 1).toLong() * PER_KEY + (PER_KEY - struck).coerceAtLeast(0)
        return left * pace + WRITE_MS
    }

    /** The microphone is done with: untapped, the tablet's sound let be. */
    private fun listenNoMore() {
        if (tapped) {
            tapped = false
            ears.untap()
        }
        piano.muteTablet(false)
    }

    /** It ran to its end: the stop sequence, and the [result]. */
    private fun finish(result: StepsResult) {
        if (!running) return
        job = null
        piano.stopSequence()
        listenNoMore()
        _state.value = StepsState(total = total, result = result, written = written, fullPowerOff = true, shown = true)
    }

    /** It ends before its end, saying [why]: the stop sequence, and the microphone let go. */
    private fun end(why: String) {
        if (!running) return
        val ending = job
        job = null
        _state.value = StepsState(total = total, stopped = why, written = written, fullPowerOff = fullPowerOff, shown = true)
        piano.stopSequence()
        listenNoMore()
        ending?.cancel()
    }

    companion object {
        /** The piano's 84 keys, low to high. */
        val KEYS: IntRange = KeyTest.KEYS
        val TOTAL: Int = KEYS.count()

        /** Strikes a key takes: the coarse look, then five at its floor and five at its top. */
        val PER_KEY: Int = StepPicker.COARSE.size + 2 * (2 * StepPicker.FINE_STRIKES + 1)

        /** One strike every 310 ms: the firmware's 300 ms and a little for the link's own jitter. */
        const val STRIKE_EVERY_MS = 310L

        /** "Too soon": sent again this long after, at most this many times in a row. */
        const val TOO_SOON_WAIT_MS = 40L
        const val TOO_SOON_TRIES = 10

        /** The room heard for 2 s; louder than this (dB of full scale, about a classroom with people talking) is too loud. */
        const val ROOM_MS = 2_000L
        const val ROOM_TOO_LOUD_DB = -40.0

        /** How long the microphone may hear nothing new while a level is awaited. */
        const val HEARD_WAIT_MS = 1_500L

        /** A key whose floor shows only after its loudest strike waits this long before its soft ones. */
        const val SETTLE_MS = 1_000L

        /** About how long the writing takes, for the time left. */
        const val WRITE_MS = 5_000L

        private const val LOOK_MS = 100L
        private const val PROBE_MS = 20L
        private const val PACE_AFTER = 5
        private const val PACE_SLACK_MS = 20L
        private const val NANOS_PER_MS = 1_000_000L

        /** Refusals of its own (the rest are [ListenAnswer]'s). */
        const val RUNNING = "steps-running"
        const val NO_STEPS = "no-steps"
        const val READ_ONLY = "read-only"
        const val GUEST = "guest"
        const val PLUG = "plug"
    }
}
