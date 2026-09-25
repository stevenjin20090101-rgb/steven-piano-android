// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.Bars
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analyses and the layout call their caller's checkpoint every few thousand notes (windows,
 * systems) and between passes, and stop when it throws: a piece replaced while it is worked out
 * stops there instead of running on (the v1.3 delta audit, L1).
 */
class AnalysisCancelTest {
    private val tempo = TempoMap.constant(480)
    private val common = listOf(TimeSignature.Common)

    /** 100,000 notes a sixteenth apart, the left hand's and the right hand's in turn, over 6,250 bars. */
    private val count = 100_000
    private val notes = NoteList(
        LongArray(count) { tempo.tickToMicros(it * 120L) },
        LongArray(count) { tempo.tickToMicros(it * 120L + 240) },
        ByteArray(count) { (if (it % 2 == 0) 48 + it % 12 else 72 + it % 12).toByte() },
        ByteArray(count) { 80 },
        ByteArray(count),
    )
    private val bars = Bars.starts(tempo, common, notes.endMicros.max())
    private val hands = ByteArray(count) { if (it % 2 == 0) Hands.LEFT else Hands.RIGHT }

    /** Counts its calls, and throws as a cancelled coroutine's checkpoint does at call [at]. */
    private class Checkpoint(private val at: Int = Int.MAX_VALUE) : () -> Unit {
        var calls = 0

        override fun invoke() {
            if (++calls == at) throw CancellationException("a newer piece replaced this one")
        }
    }

    /** [work] calls its checkpoint at least [least] times when left to run, and stops at the call that throws. */
    private fun stops(least: Int, work: (() -> Unit) -> Unit) {
        val left = Checkpoint()
        work(left)
        assertTrue("${left.calls} calls, $least expected", left.calls >= least)
        val stopped = Checkpoint(at = 3)
        assertThrows(CancellationException::class.java) { work(stopped) }
        assertEquals(3, stopped.calls)
    }

    @Test
    fun `the hands are checked every 4096 notes`() =
        stops(count / Hands.CHECK_EVERY) { checkpoint -> Hands.assign(notes, emptyList(), tempo, common, checkpoint) }

    @Test
    fun `the fingering is checked every 4096 notes of each hand`() =
        stops(2 * (count / 2 / Fingering.CHECK_EVERY)) { checkpoint -> Fingering.assign(notes, hands, 0, true, checkpoint) }

    @Test
    fun `the chords are checked every 1024 windows`() =
        stops(Chords.MAX_WINDOWS / Chords.CHECK_EVERY) { checkpoint -> Chords.detect(notes, tempo, bars, common, emptyList(), checkpoint) }

    @Test
    fun `the layout is checked between its passes and every 4096 notes`() {
        val keys = IntArray(count) { KeyMap.map(notes.note(it), 0, true) }
        val fingers = ByteArray(count) { (1 + it % 5).toByte() }
        stops(count / ScoreLayoutEngine.CHECK_EVERY + 8) { checkpoint ->
            ScoreLayoutEngine.layout(notes, keys, tempo, bars, emptyList(), ScoreFixtures.metrics(), common, hands, fingers, checkpoint)
        }
    }
}
