// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.safety

import dev.stevenjin.stevenpiano.audio.KeyResult

/** The safety check's steps (v1.26 — M61), in the order the card lists them. */
enum class SafetyStep {
    /** The player's stop sequence (CC64 = 0, CC123), then a second: "Keys released". */
    RELEASE,

    /** `!active` read: no coil on ("No coil on"). */
    COILS,

    /** `!boards` read: every power board answering ("Boards answering (5 of 7)"). */
    BOARDS,

    /** The 84 keys struck in turn while the tablet's microphone listens ("Testing each key (37 of 84)"). */
    SWEEP,

    /** The piano's `restart` (firmware 2.1.1): every coil let go, the controller started again ("Restarting the piano"). */
    RESTART,

    /** The 24 V switched off and on again by the power plug (M60's `RailPower.repower`): "Power off and on". */
    REPOWER,

    /** The keys struck again after the restart and the repower ("Testing each key again (12 of 84)"). */
    SWEEP_AGAIN,
}

/** How a step stands: still to come, running, done, left out, or failed. */
enum class Mark { WAITING, RUNNING, DONE, SKIPPED, FAILED }

/** Why a step was left out or failed (the card says it in words), or why a check could not finish. */
enum class Why {
    /** The piano's firmware does not report it (no `!active` or no `!boards` in its dump). */
    NOT_REPORTED,

    /**
     * The piano's console gave no dump on this connection, even asked again (v1.27 — M64): what it reports, the coils
     * first, is unknown, so the check can't finish.
     */
    NO_DUMP,

    /** Asked, and the piano did not answer within 2 s. */
    NO_ANSWER,

    /** `!active` above 0 after the release: a coil is still on. */
    COIL_ON,

    /** A power board is not answering. */
    BOARDS_MISSING,

    /** No sweep: the safety check's microphone switch is off, or Android does not allow the microphone. */
    MICROPHONE_OFF,

    /** Keys struck and not heard. */
    KEYS_SILENT,

    /** The piano offers no `restart` (no `!restartproto=1`: firmware before 2.1.1). */
    NOT_OFFERED,

    /** `!ota=pending`: the firmware has not confirmed itself, and a restart would roll the update back. */
    OTA_PENDING,

    /** The piano refused `restart` (its words are the line's). */
    REFUSED,

    /** After `restart`, the piano and every board did not come back within 60 s. */
    NOT_BACK,

    /** The repower did not confirm: the power plug could not bring every board back. */
    NOT_CONFIRMED,

    /** No repower: no power plug paired (or its feature off). */
    NO_PLUG,

    /** The piano was not connected (or its lock had not accepted this tablet) when the check began. */
    NO_PIANO,

    /** The 24 V rail (the power plug, M60) didn't come up within 10 s before a sweep: no key is judged. */
    RAIL_DOWN,

    /** The check stopped before its end (its words say why). */
    STOPPED,
}

/**
 * One line of the card: its [step], how it stands, its figures ([done] of [of]: boards answering, keys struck or heard),
 * the keys not heard ([keys]), why it was left out or failed, and the piano's or the tablet's own words for that ([said]).
 */
data class StepLine(
    val step: SafetyStep,
    val mark: Mark,
    val done: Int = 0,
    val of: Int = 0,
    val keys: List<Int> = emptyList(),
    val why: Why? = null,
    val said: String? = null,
)

/** How a check ended. */
sealed interface SafetyOutcome {
    /** Every key heard ([keys] of them; null: no sweep, the microphone off), after the restart and repower when [recovered]. */
    data class Passed(val keys: Int?, val recovered: Boolean) : SafetyOutcome

    /** A coil was still on after the release: the 24 V is cut, everything stops, and Play is locked. */
    data object CoilOn : SafetyOutcome

    /** [keys] (MIDI) were not heard, after the restart and the repower too: Play is locked. */
    data class KeysSilent(val keys: List<Int>) : SafetyOutcome

    /** The check could not finish ([why], in the tablet's or the piano's [said] words): it runs again before the next Play. */
    data class Unfinished(val why: Why, val said: String? = null) : SafetyOutcome
}

/** What the runner does next for the check. */
sealed interface Command {
    /** The player's stop sequence, then a second (the release). */
    data object Release : Command

    /** `get !<fact>`, its answer awaited (2 s): `active`, `boards`, `ota`. */
    data class Ask(val fact: String) : Command

    /** The key test's sweep, the microphone listening. */
    data object Sweep : Command

    /** `expectRestart()` on the link, then the piano's `restart`, its reply awaited. */
    data object Restart : Command

    /** The link and every board back after the restart, 60 s at most. */
    data object AwaitBack : Command

    /** The power plug's repower. */
    data object Repower : Command

    /** The check is over. */
    data class Finish(val outcome: SafetyOutcome) : Command
}

/** What a command came to, or an interruption from outside. */
sealed interface Event {
    data object Released : Event

    /** The answer to the [Command.Ask] the check is waiting for: the fact's value, or null with none in 2 s. */
    data class Answer(val value: String?) : Event

    /** The sweep ran to its end and heard [results], one per key. */
    data class Swept(val results: List<KeyResult>) : Event

    /** The sweep stopped before its end, as the key test [said]. */
    data class SweepStopped(val said: String) : Event

    /** Before the sweep, the 24 V rail didn't come up within 10 s (the power plug's `awaitRail`): no key was struck. */
    data object RailDown : Event

    /** The piano's reply to `restart` ([reply]: `restarting`, or a refusal), null with none in 2 s. */
    data class Restarted(val reply: String?) : Event

    /** Whether the link and every board came back after the restart. */
    data class Back(val ok: Boolean) : Event

    /** Whether the repower confirmed, and whether a power plug was there to do it ([plugged]). */
    data class Repowered(val ok: Boolean, val plugged: Boolean = true) : Event

    /** The check must stop (the app left the screen, a quiet time began, an update started, Stop…), as [said]. */
    data class Interrupted(val said: String) : Event
}

/** A check's state: the decision taken next ([command]) with it. */
data class Step(val state: CheckState, val command: Command)

/** Where the check is (see [SafetyCheck]). */
enum class Phase { RELEASE, COILS, BOARDS, SWEEP, OTA, RESTART, BACK, REPOWER, DONE }

/**
 * One check as far as it has come: whether it will sweep ([listens]), what the piano's dump offers ([coils]: `!active`,
 * [boards]: `!boards`, [restart]: `!restartproto=1`; [reported] false: no dump came on this connection, v1.27 — M64), the
 * card's [lines], whether the restart and repower were done ([recovered]), how many sweeps ran, and how it ended ([outcome]).
 */
data class CheckState(
    val listens: Boolean,
    val coils: Boolean,
    val boards: Boolean,
    val restart: Boolean,
    val reported: Boolean = true,
    val lines: List<StepLine> = emptyList(),
    val phase: Phase = Phase.RELEASE,
    val recovered: Boolean = false,
    val sweeps: Int = 0,
    val outcome: SafetyOutcome? = null,
    /** The recovery was called for by the rail not coming up: the repower alone, no restart. */
    val railDown: Boolean = false,
) {
    /** The line of [step], if it has begun. */
    fun line(step: SafetyStep): StepLine? = lines.firstOrNull { it.step == step }

    /** The step running now, if any. */
    val running: StepLine? get() = lines.firstOrNull { it.mark == Mark.RUNNING }

    /**
     * Every step the check means to take: those begun, in the order they began, then those still to come
     * ([Mark.WAITING]) in the order they will run. The coils, the boards and the sweep are always to come until they
     * have run; the restart and the repower, then (after a failed sweep) the second sweep, once a restart is called for.
     * A check that has ended lists only what it did.
     */
    fun steps(): List<StepLine> {
        if (outcome != null) return lines
        val begun = lines.mapTo(HashSet()) { it.step }
        val waiting = ArrayList<SafetyStep>()
        fun want(step: SafetyStep) {
            if (step !in begun && step !in waiting) waiting += step
        }
        want(SafetyStep.COILS)
        want(SafetyStep.BOARDS)
        if (recovered) {
            if (!railDown) want(SafetyStep.RESTART)
            want(SafetyStep.REPOWER)
        }
        if (sweeps == 0) want(SafetyStep.SWEEP) else if (recovered && listens && sweeps == 1) want(SafetyStep.SWEEP_AGAIN)
        return lines + waiting.map { StepLine(it, Mark.WAITING) }
    }

    /**
     * How far the check has come, 0 to 1, each step weighed by about how long it takes (the sweeps most), the sweep
     * running by its keys: the card's slim bar. It may step back a little when a restart is called for.
     */
    val progress: Float
        get() {
            if (outcome != null) return 1f
            val all = steps()
            val total = all.sumOf { WEIGHT.getValue(it.step) }
            val gone = all.sumOf { line ->
                when (line.mark) {
                    Mark.WAITING -> 0.0
                    Mark.RUNNING -> if (line.of > 0) WEIGHT.getValue(line.step) * line.done / line.of else 0.0
                    else -> WEIGHT.getValue(line.step)
                }
            }
            return (gone / total).toFloat().coerceIn(0f, 1f)
        }

    private companion object {
        val WEIGHT = mapOf(
            SafetyStep.RELEASE to 1.0,
            SafetyStep.COILS to 0.5,
            SafetyStep.BOARDS to 0.5,
            SafetyStep.SWEEP to 12.0,
            SafetyStep.RESTART to 4.0,
            SafetyStep.REPOWER to 2.0,
            SafetyStep.SWEEP_AGAIN to 12.0,
        )
    }
}

/**
 * The safety check (v1.26 — M61; DESIGN.md › v1.26 — M61), as a pure state machine: [start] begins one, [on] takes what
 * each command came to and says what to do next; [SafetyRunner] runs the commands. Steven's steps:
 *
 * 1. **Release**: the player's stop sequence, then a second.
 * 2. **Coils**: `!active` above 0 is a fire risk: the check ends at once ([SafetyOutcome.CoilOn]: the 24 V cut,
 *    everything stopped, Play locked). **Boards**: any board missing (or either fact unanswered) calls for the restart
 *    and the repower before the sweep. A fact the piano's firmware doesn't report is left out; with no dump at all from
 *    its console on this connection the coils can't be read, and the check can't finish (v1.27 — M64, [Why.NO_DUMP]).
 * 3. **Sweep** (only when the check [CheckState.listens]: the microphone switch on and allowed; without it no key can
 *    lock Play): the 84 keys while the microphone listens.
 * 4. **Any key not heard**: the restart (only when the piano offers it, `!restartproto=1`, and never while
 *    `!ota=pending`; a refusal, or no answer, goes straight to the repower) and the wait for the piano and its boards,
 *    then the repower; then the coils and the boards are read again (a coil on still ends it) and the keys swept again.
 * 5. **Still not heard** (after the restart and repower, which happen once a check): Play is locked
 *    ([SafetyOutcome.KeysSilent]).
 * 6. **Pass**: every key heard (or, with no sweep, no coil on).
 *
 * A check that can't finish (the piano never answered, the sweep stopped, an interruption) is
 * [SafetyOutcome.Unfinished]: it runs again before the next Play.
 */
object SafetyCheck {
    /** Begins a check: the release first. [reported] false: the piano's console gave no dump on this connection. */
    fun start(listens: Boolean, coils: Boolean, boards: Boolean, restart: Boolean, reported: Boolean = true): Step =
        Step(CheckState(listens, coils, boards, restart, reported, lines = listOf(StepLine(SafetyStep.RELEASE, Mark.RUNNING))), Command.Release)

    /** What [event] (the last command's result, or an interruption) leads to from [state]. */
    fun on(state: CheckState, event: Event): Step {
        if (state.phase == Phase.DONE) return Step(state, Command.Finish(state.outcome ?: SafetyOutcome.Unfinished(Why.STOPPED)))
        return when (event) {
            is Event.Interrupted -> end(state.failRunning(Why.STOPPED, event.said), SafetyOutcome.Unfinished(Why.STOPPED, event.said))
            Event.Released -> if (state.phase == Phase.RELEASE) coils(state.put(SafetyStep.RELEASE, Mark.DONE)) else lost(state)
            is Event.Answer -> when (state.phase) {
                Phase.COILS -> onActive(state, event.value)
                Phase.BOARDS -> onBoards(state, event.value)
                Phase.OTA -> onOta(state, event.value)
                else -> lost(state)
            }
            is Event.Swept -> if (state.phase == Phase.SWEEP) onSwept(state, event.results) else lost(state)
            is Event.SweepStopped -> if (state.phase == Phase.SWEEP) {
                end(state.put(sweepStep(state), Mark.FAILED, why = Why.STOPPED, said = event.said), SafetyOutcome.Unfinished(Why.STOPPED, event.said))
            } else {
                lost(state)
            }
            Event.RailDown -> if (state.phase == Phase.SWEEP) onRailDown(state) else lost(state)
            is Event.Restarted -> if (state.phase == Phase.RESTART) onRestart(state, event.reply) else lost(state)
            is Event.Back -> if (state.phase == Phase.BACK) {
                repower(if (event.ok) state.put(SafetyStep.RESTART, Mark.DONE) else state.put(SafetyStep.RESTART, Mark.FAILED, why = Why.NOT_BACK))
            } else {
                lost(state)
            }
            is Event.Repowered -> if (state.phase == Phase.REPOWER) {
                // The piano is read again from the coils: a coil on still ends the check; the boards as they are now.
                val why = if (event.plugged) Why.NOT_CONFIRMED else Why.NO_PLUG
                coils(if (event.ok) state.put(SafetyStep.REPOWER, Mark.DONE) else state.put(SafetyStep.REPOWER, Mark.SKIPPED, why = why))
            } else {
                lost(state)
            }
        }
    }

    /** The sweep's progress: [done] of [of] keys struck, on the line running. */
    fun struck(state: CheckState, done: Int, of: Int): CheckState {
        if (state.phase != Phase.SWEEP) return state
        return state.put(sweepStep(state), Mark.RUNNING, done = done, of = of)
    }

    /** `!boards` as [read]: how many answer (`OK`) of how many there are; null when there is no reading. */
    fun boards(value: String?): Pair<Int, Int>? {
        val parts = value?.trim()?.takeIf { it.isNotEmpty() }?.split(',')?.map { it.trim().uppercase() } ?: return null
        if (parts.any { it.isEmpty() }) return null
        return parts.count { it == OK } to parts.size
    }

    private fun coils(state: CheckState): Step {
        // v1.27 — M64: no dump, nothing known: never "not reported", which would let a check pass with no coil read.
        if (!state.reported) return end(state.put(SafetyStep.COILS, Mark.FAILED, why = Why.NO_DUMP), SafetyOutcome.Unfinished(Why.NO_DUMP))
        if (!state.coils) return boards(state.put(SafetyStep.COILS, Mark.SKIPPED, why = Why.NOT_REPORTED))
        return Step(state.copy(phase = Phase.COILS).put(SafetyStep.COILS, Mark.RUNNING), Command.Ask(ACTIVE))
    }

    private fun onActive(state: CheckState, value: String?): Step {
        val active = value?.trim()?.toIntOrNull()
        return when {
            active == null -> {
                val failed = state.put(SafetyStep.COILS, Mark.FAILED, why = Why.NO_ANSWER)
                if (state.recovered) end(failed, SafetyOutcome.Unfinished(Why.NO_ANSWER)) else recover(failed)
            }
            active > 0 -> end(state.put(SafetyStep.COILS, Mark.FAILED, done = active, why = Why.COIL_ON), SafetyOutcome.CoilOn)
            else -> boards(state.put(SafetyStep.COILS, Mark.DONE))
        }
    }

    private fun boards(state: CheckState): Step {
        if (!state.boards) return sweep(state.put(SafetyStep.BOARDS, Mark.SKIPPED, why = Why.NOT_REPORTED))
        return Step(state.copy(phase = Phase.BOARDS).put(SafetyStep.BOARDS, Mark.RUNNING), Command.Ask(BOARDS))
    }

    private fun onBoards(state: CheckState, value: String?): Step {
        val read = boards(value)
        return when {
            read == null -> {
                val failed = state.put(SafetyStep.BOARDS, Mark.FAILED, why = Why.NO_ANSWER)
                if (state.recovered) end(failed, SafetyOutcome.Unfinished(Why.NO_ANSWER)) else recover(failed)
            }
            read.first < read.second -> {
                val missing = state.put(SafetyStep.BOARDS, Mark.FAILED, done = read.first, of = read.second, why = Why.BOARDS_MISSING)
                // Once restarted and repowered, the sweep says which keys that leaves silent (the microphone off: none locks).
                if (state.recovered) sweep(missing) else recover(missing)
            }
            else -> sweep(state.put(SafetyStep.BOARDS, Mark.DONE, done = read.first, of = read.second))
        }
    }

    private fun sweep(state: CheckState): Step {
        val step = if (state.sweeps == 0) SafetyStep.SWEEP else SafetyStep.SWEEP_AGAIN
        if (!state.listens) {
            return end(state.put(step, Mark.SKIPPED, why = Why.MICROPHONE_OFF), SafetyOutcome.Passed(keys = null, recovered = state.recovered))
        }
        return Step(state.copy(phase = Phase.SWEEP, sweeps = state.sweeps + 1).put(step, Mark.RUNNING, of = KEYS), Command.Sweep)
    }

    private fun onSwept(state: CheckState, results: List<KeyResult>): Step {
        val step = sweepStep(state)
        if (results.isEmpty()) return end(state.put(step, Mark.FAILED, why = Why.STOPPED), SafetyOutcome.Unfinished(Why.STOPPED))
        val silent = results.filterNot { it.heard }.map { it.key }.sorted()
        val heard = results.size - silent.size
        if (silent.isEmpty()) {
            return end(state.put(step, Mark.DONE, done = heard, of = results.size), SafetyOutcome.Passed(keys = results.size, recovered = state.recovered))
        }
        val failed = state.put(step, Mark.FAILED, done = heard, of = results.size, keys = silent, why = Why.KEYS_SILENT)
        return if (state.recovered) end(failed, SafetyOutcome.KeysSilent(silent)) else recover(failed)
    }

    /**
     * The rail didn't come up for the sweep: no key is judged (a key with no power can't sound). The first time, the
     * repower straight away (the restart is for keys that didn't sound), then the piano read again and the sweep tried
     * again; once recovered, the check can't finish, and runs again before the next Play.
     */
    private fun onRailDown(state: CheckState): Step {
        val step = sweepStep(state)
        val skipped = state.put(step, Mark.SKIPPED, why = Why.RAIL_DOWN).copy(sweeps = state.sweeps - 1)
        if (state.recovered) return end(skipped, SafetyOutcome.Unfinished(Why.RAIL_DOWN))
        return repower(skipped.copy(recovered = true, railDown = true))
    }

    /** The restart and the repower, once a check: the restart only where the piano offers it. */
    private fun recover(state: CheckState): Step {
        val once = state.copy(recovered = true)
        if (!once.restart) return repower(once.put(SafetyStep.RESTART, Mark.SKIPPED, why = Why.NOT_OFFERED))
        return Step(once.copy(phase = Phase.OTA).put(SafetyStep.RESTART, Mark.RUNNING), Command.Ask(OTA))
    }

    private fun onOta(state: CheckState, value: String?): Step {
        if (value?.trim() == OTA_PENDING) return repower(state.put(SafetyStep.RESTART, Mark.SKIPPED, why = Why.OTA_PENDING))
        return Step(state.copy(phase = Phase.RESTART), Command.Restart)
    }

    /** Whether the piano's reply to `restart` says it restarts now: it lets go of the link about 200 ms later. */
    fun restarting(reply: String?): Boolean = reply?.trim()?.startsWith(RESTARTING) == true

    private fun onRestart(state: CheckState, reply: String?): Step {
        val text = reply?.trim()
        return when {
            restarting(text) -> Step(state.copy(phase = Phase.BACK), Command.AwaitBack)
            text != null -> repower(state.put(SafetyStep.RESTART, Mark.SKIPPED, why = Why.REFUSED, said = text))
            else -> repower(state.put(SafetyStep.RESTART, Mark.SKIPPED, why = Why.NO_ANSWER))
        }
    }

    private fun repower(state: CheckState): Step = Step(state.copy(phase = Phase.REPOWER).put(SafetyStep.REPOWER, Mark.RUNNING), Command.Repower)

    private fun end(state: CheckState, outcome: SafetyOutcome): Step = Step(state.copy(phase = Phase.DONE, outcome = outcome), Command.Finish(outcome))

    /** An event the check wasn't waiting for: it can't go on as it was, so it ends unfinished and runs again later. */
    private fun lost(state: CheckState): Step = end(state.failRunning(Why.STOPPED, null), SafetyOutcome.Unfinished(Why.STOPPED))

    private fun sweepStep(state: CheckState): SafetyStep = if (state.sweeps <= 1) SafetyStep.SWEEP else SafetyStep.SWEEP_AGAIN

    /** [step]'s line set to [mark] (with its figures, keys and reasons), in its place, or added at the end when it is new. */
    private fun CheckState.put(
        step: SafetyStep,
        mark: Mark,
        done: Int = line(step)?.takeIf { mark == Mark.RUNNING }?.done ?: 0,
        of: Int = line(step)?.takeIf { mark == Mark.RUNNING }?.of ?: 0,
        keys: List<Int> = emptyList(),
        why: Why? = null,
        said: String? = null,
    ): CheckState {
        val line = StepLine(step, mark, done, of, keys, why, said)
        val at = lines.indexOfFirst { it.step == step }
        return copy(lines = if (at < 0) lines + line else lines.toMutableList().apply { set(at, line) })
    }

    /** The line running, if any, marked failed for [why]. */
    private fun CheckState.failRunning(why: Why, said: String?): CheckState {
        val running = running ?: return this
        return put(running.step, Mark.FAILED, done = running.done, of = running.of, why = why, said = said)
    }

    /** The keys a sweep strikes. */
    const val KEYS = 84

    const val ACTIVE = "active"
    const val BOARDS = "boards"
    const val OTA = "ota"
    const val RESTART_FACT = "restartproto"
    private const val OK = "OK"
    private const val OTA_PENDING = "pending"
    private const val RESTARTING = "restarting"
}
