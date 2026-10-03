// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.score.Hands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Expression (v1.16 — M44): a flat file shaped as a pianist would, within its bounds, Full more than Light, Off not at all. */
class ExpressionTest {
    /**
     * Eight bars of 4/4 at 120 BPM, every note at 64: on each beat a three-note chord in the right hand (its top the
     * melody) and a bass note in the left; two phrases, a rest of a beat and more between them; the pedal down each bar.
     */
    private val flat: MidiPiece = piece {
        val tops = intArrayOf(72, 74, 76, 77, 79, 77, 76, 74)
        var beat = 0
        for (bar in 0L until 8L) {
            cc(bar * 2_000, 64, 127)
            cc(bar * 2_000 + 1_950, 64, 0)
            for (b in 0L until 4L) {
                if (bar == 3L && b >= 2L) continue   // the rest between the two phrases
                val at = bar * 2_000 + b * 500
                val top = tops[beat++ % tops.size]
                note(at, top, 450)
                note(at, top - 4, 450)
                note(at, top - 7, 450)
                note(at, if (b % 2 == 0L) 48 else 43, 450)
            }
        }
    }

    private val hands = ByteArray(flat.notes.size) { if (flat.notes.note(it) >= 60) Hands.RIGHT else Hands.LEFT }

    private fun shaped(level: ExpressionLevel): MidiPiece = Performance.shape(flat, hands, RepeatsOnly.copy(expression = level), FreeRepeats)

    /** The k-th note of each channel and key in [before], beside the k-th in [after]. */
    private fun pairs(before: MidiPiece, after: MidiPiece): List<Pair<Sounded, Sounded>> {
        val shapedBySource = after.sounded().groupBy { it.channel * 128 + it.key }
        return before.sounded().groupBy { it.channel * 128 + it.key }.flatMap { (source, notes) -> notes.zip(shapedBySource.getValue(source)) }
    }

    @Test
    fun `Off is the identity`() {
        assertSame(flat, Performance.shape(flat, hands, RepeatsOnly, FreeRepeats))
    }

    @Test
    fun `a flat file gains variance within 1-127, and the melody rises above the inner notes`() {
        val notes = shaped(ExpressionLevel.LIGHT).sounded()
        val velocities = notes.map { it.velocity }
        assertTrue("$velocities", velocities.distinct().size > 3)
        assertTrue(velocities.all { it in 1..127 })
        // Each beat's chord (onsets move 25 ms at most): the melody above both inner notes, the bass below the melody.
        for ((_, chord) in notes.groupBy { Math.round(it.on / 500_000.0) }) {
            val right = chord.filter { it.key >= 60 }.sortedBy { it.key }
            val melody = right.last()
            for (inner in right.dropLast(1)) assertTrue("$melody against $inner", melody.velocity > inner.velocity)
            assertTrue(chord.single { it.key < 60 }.velocity < melody.velocity)
        }
    }

    @Test
    fun `no onset moves more than 25 ms, the count and length stay, the events stay in order, and the pedal keeps its times`() {
        for (level in listOf(ExpressionLevel.LIGHT, ExpressionLevel.FULL)) {
            val played = shaped(level)
            val pairs = pairs(flat, played)
            assertEquals(flat.sounded().size, pairs.size)
            assertEquals(flat.events.count { it.command == 0x90 }, played.events.count { it.command == 0x90 })
            for ((before, after) in pairs) assertTrue("$level: $before became $after", abs(after.on - before.on) <= 25 * MS)
            assertTrue("some onsets move at all", pairs.any { (before, after) -> before.on != after.on })
            assertEquals(flat.durationMicros, played.durationMicros)
            assertEquals(flat.events.lastMicros, played.events.lastMicros)
            fun rank(command: Int) = when (command) {
                0xB0 -> 0
                0x80 -> 1
                else -> 2
            }
            for ((a, b) in played.events.zipWithNext()) {
                assertTrue("$level: $a before $b", a.atMicros < b.atMicros || (a.atMicros == b.atMicros && rank(a.command) <= rank(b.command)))
            }
            assertEquals(flat.controllers(), played.controllers())
        }
    }

    @Test
    fun `Full deviates at least as much as Light`() {
        fun loudness(piece: MidiPiece) = piece.sounded().sumOf { abs(it.velocity - 64) }
        fun timing(piece: MidiPiece) = pairs(flat, piece).sumOf { (before, after) -> abs(after.on - before.on) }
        val light = shaped(ExpressionLevel.LIGHT)
        val full = shaped(ExpressionLevel.FULL)
        assertTrue("${loudness(full)} against ${loudness(light)}", loudness(full) >= loudness(light))
        assertTrue("${timing(full)} against ${timing(light)}", timing(full) >= timing(light))
        assertTrue(loudness(light) > 0 && timing(light) > 0)
    }
}
