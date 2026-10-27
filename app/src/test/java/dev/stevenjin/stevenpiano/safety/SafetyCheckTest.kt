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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety check's steps (v1.26 — M61), as a pure state machine: piano safety. Each test feeds the results a piano would
 * give, one command at a time, and checks what the check does next and how it ends.
 */
class SafetyCheckTest {
    private val allOk = "OK,OK,OK,OK,OK,OK,OK"
    private val oneMissing = "OK,OK,MISSING,OK,OK,OK,OK"

    /** The 84 keys struck, those in [silent] not heard. */
    private fun sweep(silent: Set<Int> = emptySet()) = Event.Swept((24..107).map { KeyResult(it, it !in silent) })

    /** A check under way: each event fed in turn, [expect] checking the command it led to. */
    private class Run(start: Step) {
        var step = start
            private set

        fun then(event: Event, expect: Command): Run {
            step = SafetyCheck.on(step.state, event)
            assertEquals(expect, step.command)
            return this
        }

        val marks: List<Pair<SafetyStep, Mark>> get() = step.state.lines.map { it.step to it.mark }
    }

    private fun run(listens: Boolean = true, coils: Boolean = true, boards: Boolean = true, restart: Boolean = true, reported: Boolean = true): Run {
        val start = SafetyCheck.start(listens, coils, boards, restart, reported)
        assertEquals("the release first", Command.Release, start.command)
        return Run(start)
    }

    @Test
    fun `a check that passes - keys released, no coil on, every board answering, every key heard`() {
        val check = run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
        val struck = SafetyCheck.struck(check.step.state, 37, 84)
        assertEquals(SafetyStep.SWEEP to Mark.RUNNING, struck.running?.let { it.step to it.mark })
        assertEquals(37 to 84, struck.running?.let { it.done to it.of })
        check.then(sweep(), Command.Finish(SafetyOutcome.Passed(keys = 84, recovered = false)))
        assertEquals(
            listOf(SafetyStep.RELEASE to Mark.DONE, SafetyStep.COILS to Mark.DONE, SafetyStep.BOARDS to Mark.DONE, SafetyStep.SWEEP to Mark.DONE),
            check.marks,
        )
        assertEquals(7 to 7, check.step.state.line(SafetyStep.BOARDS)?.let { it.done to it.of })
        assertEquals(1f, check.step.state.progress)
    }

    @Test
    fun `a coil still on after the release ends the check at once - nothing more is asked or struck`() {
        val check = run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("2"), Command.Finish(SafetyOutcome.CoilOn))
        assertEquals(listOf(SafetyStep.RELEASE to Mark.DONE, SafetyStep.COILS to Mark.FAILED), check.marks)
        assertEquals(Why.COIL_ON, check.step.state.line(SafetyStep.COILS)?.why)
        // And after a restart and repower too: a coil on still ends it, before any sweep.
        run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(oneMissing), Command.Ask("ota"))
            .then(Event.Answer("confirmed"), Command.Restart)
            .then(Event.Restarted("restarting"), Command.AwaitBack)
            .then(Event.Back(true), Command.Repower)
            .then(Event.Repowered(true), Command.Ask("active"))
            .then(Event.Answer("1"), Command.Finish(SafetyOutcome.CoilOn))
    }

    @Test
    fun `a missing board calls for the restart and the repower before the sweep, then the piano is read again`() {
        val check = run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(oneMissing), Command.Ask("ota"))
        assertEquals("shown: 6 of 7", 6 to 7, check.step.state.line(SafetyStep.BOARDS)?.let { it.done to it.of })
        assertEquals(Mark.FAILED, check.step.state.line(SafetyStep.BOARDS)?.mark)
        assertNull("no sweep yet", check.step.state.line(SafetyStep.SWEEP))
        // Still to come, in the order they run: the restart, the repower, then the sweep.
        assertEquals(listOf(SafetyStep.RESTART, SafetyStep.REPOWER, SafetyStep.SWEEP), check.step.state.steps().drop(3).map { it.step })
        check.then(Event.Answer("confirmed"), Command.Restart)
            .then(Event.Restarted("restarting"), Command.AwaitBack)
            .then(Event.Back(true), Command.Repower)
            .then(Event.Repowered(true), Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
            .then(sweep(), Command.Finish(SafetyOutcome.Passed(keys = 84, recovered = true)))
        assertEquals(
            listOf(
                SafetyStep.RELEASE to Mark.DONE, SafetyStep.COILS to Mark.DONE, SafetyStep.BOARDS to Mark.DONE,
                SafetyStep.RESTART to Mark.DONE, SafetyStep.REPOWER to Mark.DONE, SafetyStep.SWEEP to Mark.DONE,
            ),
            check.marks,
        )
    }

    @Test
    fun `keys not heard only warn - the check passes with them named, no restart, repower or second sweep (v1_33 M71)`() {
        val check = run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
            .then(sweep(setOf(66, 36)), Command.Finish(SafetyOutcome.Passed(keys = 84, recovered = false, missed = listOf(36, 66))))
        val line = check.step.state.line(SafetyStep.SWEEP)!!
        assertEquals("a pass: the sweep done", Mark.DONE, line.mark)
        assertEquals(listOf(36, 66), line.keys)
        assertEquals(Why.KEYS_SILENT, line.why)
        assertEquals(82 to 84, line.done to line.of)
        assertEquals(
            listOf(SafetyStep.RELEASE to Mark.DONE, SafetyStep.COILS to Mark.DONE, SafetyStep.BOARDS to Mark.DONE, SafetyStep.SWEEP to Mark.DONE),
            check.marks,
        )
        assertFalse(check.step.state.recovered)
        assertEquals(1, check.step.state.sweeps)
        assertFalse((check.step.command as Command.Finish).outcome.let { it as SafetyOutcome.Passed }.heardAll)
    }

    @Test
    fun `every key heard - a pass with nothing missed, the one that heard them all`() {
        val check = run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
            .then(sweep(), Command.Finish(SafetyOutcome.Passed(keys = 84, recovered = false, missed = emptyList())))
        assertTrue(((check.step.command as Command.Finish).outcome as SafetyOutcome.Passed).heardAll)
        assertFalse("no sweep heard nothing", SafetyOutcome.Passed(keys = null, recovered = false).heardAll)
    }

    @Test
    fun `boards back after the restart and repower - the sweep, and keys not heard still only warn (v1_33 M71)`() {
        run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(oneMissing), Command.Ask("ota"))
            .then(Event.Answer("none"), Command.Restart)
            .then(Event.Restarted("restarting"), Command.AwaitBack)
            .then(Event.Back(true), Command.Repower)
            .then(Event.Repowered(true), Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
            .then(sweep(setOf(40)), Command.Finish(SafetyOutcome.Passed(keys = 84, recovered = true, missed = listOf(40))))
    }

    @Test
    fun `boards still missing after the restart and repower lock Play, with the microphone on (v1_33 M71)`() {
        val check = run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(oneMissing), Command.Ask("ota"))
            .then(Event.Answer("confirmed"), Command.Restart)
            .then(Event.Restarted("restarting"), Command.AwaitBack)
            .then(Event.Back(false), Command.Repower)
            .then(Event.Repowered(true), Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(oneMissing), Command.Finish(SafetyOutcome.BoardsMissing(6, 7)))
        assertNull("no sweep for a piano that lost a board", check.step.state.line(SafetyStep.SWEEP))
        assertEquals(Mark.FAILED to Why.BOARDS_MISSING, check.step.state.line(SafetyStep.BOARDS)?.let { it.mark to it.why })
    }

    @Test
    fun `the restart is left out where the firmware has none, while an update awaits its confirmation, or when the piano refuses`() {
        fun boardsRead(restart: Boolean) = run(restart = restart)
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))

        // No `!restartproto=1` (firmware before 2.1.1): straight to the repower.
        val none = boardsRead(restart = false).then(Event.Answer(oneMissing), Command.Repower)
        assertEquals(Mark.SKIPPED to Why.NOT_OFFERED, none.step.state.line(SafetyStep.RESTART)?.let { it.mark to it.why })

        // `!ota=pending`: never restart (it would roll the update back).
        val pending = boardsRead(restart = true)
            .then(Event.Answer(oneMissing), Command.Ask("ota"))
            .then(Event.Answer("pending"), Command.Repower)
        assertEquals(Mark.SKIPPED to Why.OTA_PENDING, pending.step.state.line(SafetyStep.RESTART)?.let { it.mark to it.why })

        // Every refusal contains "refused": the piano's own words are kept for the card, and the repower follows.
        for (refusal in listOf("refused — updating firmware", "refused — this firmware has not confirmed itself yet", "refused — not authorised (send auth first)")) {
            val refused = boardsRead(restart = true)
                .then(Event.Answer(oneMissing), Command.Ask("ota"))
                .then(Event.Answer("confirmed"), Command.Restart)
                .then(Event.Restarted(refusal), Command.Repower)
            val line = refused.step.state.line(SafetyStep.RESTART)!!
            assertEquals(Mark.SKIPPED to Why.REFUSED, line.mark to line.why)
            assertEquals(refusal, line.said)
        }

        // No reply at all: the repower follows too.
        val silent = boardsRead(restart = true)
            .then(Event.Answer(oneMissing), Command.Ask("ota"))
            .then(Event.Answer("confirmed"), Command.Restart)
            .then(Event.Restarted(null), Command.Repower)
        assertEquals(Why.NO_ANSWER, silent.step.state.line(SafetyStep.RESTART)?.why)
    }

    @Test
    fun `a repower that can't confirm is said so, with or without a power plug, and the check goes on`() {
        fun recovering() = run(restart = false)
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(oneMissing), Command.Repower)
        val noPlug = recovering().then(Event.Repowered(ok = false, plugged = false), Command.Ask("active"))
        assertEquals(Mark.SKIPPED to Why.NO_PLUG, noPlug.step.state.line(SafetyStep.REPOWER)?.let { it.mark to it.why })
        val failed = recovering().then(Event.Repowered(ok = false, plugged = true), Command.Ask("active"))
        assertEquals(Mark.SKIPPED to Why.NOT_CONFIRMED, failed.step.state.line(SafetyStep.REPOWER)?.let { it.mark to it.why })
    }

    @Test
    fun `the microphone off - no sweep, and boards still missing after the restart and repower lock Play too (v1_33 M71)`() {
        val quiet = run(listens = false)
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Finish(SafetyOutcome.Passed(keys = null, recovered = false)))
        assertEquals(Mark.SKIPPED to Why.MICROPHONE_OFF, quiet.step.state.line(SafetyStep.SWEEP)?.let { it.mark to it.why })

        val missing = run(listens = false)
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(oneMissing), Command.Ask("ota"))
            .then(Event.Answer("confirmed"), Command.Restart)
            .then(Event.Restarted("restarting"), Command.AwaitBack)
            .then(Event.Back(false), Command.Repower)
            .then(Event.Repowered(false, plugged = false), Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(oneMissing), Command.Finish(SafetyOutcome.BoardsMissing(6, 7)))
        assertEquals("the boards as they are, shown", Mark.FAILED, missing.step.state.line(SafetyStep.BOARDS)?.mark)
        assertNull(missing.step.state.line(SafetyStep.SWEEP))
    }

    @Test
    fun `but a coil on locks Play with the microphone off too`() {
        run(listens = false)
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("3"), Command.Finish(SafetyOutcome.CoilOn))
    }

    @Test
    fun `facts the firmware doesn't report are left out, and a piano that doesn't answer is restarted, then left for the next Play`() {
        val older = run(coils = false, boards = false)
            .then(Event.Released, Command.Sweep)
        assertEquals(Why.NOT_REPORTED, older.step.state.line(SafetyStep.COILS)?.why)
        assertEquals(Why.NOT_REPORTED, older.step.state.line(SafetyStep.BOARDS)?.why)

        run(restart = false)
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer(null), Command.Repower)   // no answer in 2 s: the repower (no restart on this firmware)
            .then(Event.Repowered(true), Command.Ask("active"))
            .then(Event.Answer(null), Command.Finish(SafetyOutcome.Unfinished(Why.NO_ANSWER)))   // still nothing: not passed, not locked
    }

    @Test
    fun `no dump on this connection - the keys released, then unfinished, never passed, though the microphone is off (v1_27 M64)`() {
        val check = run(listens = false, coils = false, boards = false, restart = false, reported = false)
            .then(Event.Released, Command.Finish(SafetyOutcome.Unfinished(Why.NO_DUMP)))
        assertEquals(listOf(SafetyStep.RELEASE to Mark.DONE, SafetyStep.COILS to Mark.FAILED), check.marks)
        assertEquals(Why.NO_DUMP, check.step.state.line(SafetyStep.COILS)?.why)
        // A piano with no console at all: its facts left out, and it passes as before.
        run(listens = false, coils = false, boards = false, restart = false)
            .then(Event.Released, Command.Finish(SafetyOutcome.Passed(keys = null, recovered = false)))
    }

    @Test
    fun `a sweep that stops, or an interruption, leaves the check unfinished with its words`() {
        run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
            .then(Event.SweepStopped("Stopped: the tablet's microphone stopped."), Command.Finish(SafetyOutcome.Unfinished(Why.STOPPED, "Stopped: the tablet's microphone stopped.")))
        val interrupted = run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Interrupted("A quiet time began."), Command.Finish(SafetyOutcome.Unfinished(Why.STOPPED, "A quiet time began.")))
        assertEquals(Mark.FAILED, interrupted.step.state.line(SafetyStep.COILS)?.mark)
        // A result the check wasn't waiting for never passes it.
        val lost = SafetyCheck.on(SafetyCheck.start(true, true, true, true).state, sweep())
        assertTrue(lost.command is Command.Finish && (lost.command as Command.Finish).outcome is SafetyOutcome.Unfinished)
    }

    @Test
    fun `the boards' fact reads as how many answer of how many there are`() {
        assertEquals(7 to 7, SafetyCheck.boards("OK,OK,OK,OK,OK,OK,OK"))
        assertEquals(5 to 7, SafetyCheck.boards(" OK,OK,MISSING,OK,OK,MISSING,OK "))
        assertEquals(0 to 7, SafetyCheck.boards("MISSING,MISSING,MISSING,MISSING,MISSING,MISSING,MISSING"))
        assertNull(SafetyCheck.boards(null))
        assertNull(SafetyCheck.boards(""))
        assertNull(SafetyCheck.boards("OK,,OK"))
    }

    @Test
    fun `the bar only moves on within a step, and the sweep carries most of it`() {
        val check = run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
        var before = check.step.state.progress
        assertTrue("the quick steps are a small part: $before", before < 0.2f)
        for (done in 1..84) {
            val now = SafetyCheck.struck(check.step.state, done, 84).progress
            assertTrue("$done: $now after $before", now >= before)
            before = now
        }
        assertTrue(before <= 1f)
    }

    @Test
    fun `the 24 V not up before a sweep judges no key - the repower straight away, then the keys, or unfinished (v1_26 M61)`() {
        val first = run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
            .then(Event.RailDown, Command.Repower)
        val skipped = first.step.state.line(SafetyStep.SWEEP)!!
        assertEquals(Mark.SKIPPED to Why.RAIL_DOWN, skipped.mark to skipped.why)
        assertTrue("no key failed", skipped.keys.isEmpty())
        assertNull("no restart for a rail", first.step.state.line(SafetyStep.RESTART))
        assertTrue(first.step.state.steps().none { it.step == SafetyStep.RESTART })
        first.then(Event.Repowered(true), Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
        assertEquals("the same line, swept now", SafetyStep.SWEEP to Mark.RUNNING, first.step.state.running?.let { it.step to it.mark })
        first.then(sweep(), Command.Finish(SafetyOutcome.Passed(keys = 84, recovered = true)))

        // Down again after the repower: the check can't finish (no lock: no key was judged).
        run()
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
            .then(Event.RailDown, Command.Repower)
            .then(Event.Repowered(false, plugged = true), Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
            .then(Event.RailDown, Command.Finish(SafetyOutcome.Unfinished(Why.RAIL_DOWN)))

        // And after the recovery for a missing board: unfinished too, never a lock from keys not judged.
        run(restart = false)
            .then(Event.Released, Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(oneMissing), Command.Repower)
            .then(Event.Repowered(true), Command.Ask("active"))
            .then(Event.Answer("0"), Command.Ask("boards"))
            .then(Event.Answer(allOk), Command.Sweep)
            .then(Event.RailDown, Command.Finish(SafetyOutcome.Unfinished(Why.RAIL_DOWN)))
    }
}
