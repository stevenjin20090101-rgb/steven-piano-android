// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreLayoutEngineTest {
    private val density = 2f
    private val space = 6f * density
    private val head = 1.18f * space

    /** A phone's score panel: one page, two bars a system, three systems a page. */
    private val phone = metrics(379f, 400f, ScoreWidth.COMPACT)

    private fun metrics(widthDp: Float, heightDp: Float, width: ScoreWidth) =
        ScoreMetrics.forPanel(width, widthDp * density, heightDp * density, density, head, 2.74f * space)

    /** Notes in any order, written in time order (a release before a strike at one tick). */
    private class Notes {
        val events = mutableListOf<Triple<Long, Int, Boolean>>()

        fun note(tick: Long, key: Int, length: Long) {
            events += Triple(tick, key, true)
            events += Triple(tick + length, key, false)
        }
    }

    /** A two-track file: [metas] (tempo, signatures) in the first, [notes] in the second. */
    private fun piece(ppq: Int = 480, metas: SmfBuilder.Track.() -> Unit = {}, notes: Notes.() -> Unit): MidiPiece {
        val events = Notes().apply(notes).events.sortedWith(compareBy({ it.first }, { it.third }))
        return SmfParser.parse(
            SmfBuilder(format = 1, division = ppq).track(metas).track {
                for ((tick, key, on) in events) if (on) noteOn(tick, key) else noteOff(tick, key)
            }.build(),
        )
    }

    private fun layout(
        piece: MidiPiece,
        metrics: ScoreMetrics = phone,
        transpose: Int = 0,
        fold: Boolean = true,
        hands: ByteArray? = null,
        fingers: ByteArray? = null,
    ): ScoreLayout =
        ScoreLayoutEngine.layout(
            piece.notes,
            IntArray(piece.notes.size) { KeyMap.map(piece.notes.note(it), transpose, fold) },
            piece.tempoMap,
            piece.barStartsMicros,
            piece.keySignatures.map { it.transposed(transpose) },
            metrics,
            piece.timeSignatures,
            hands,
            fingers,
        )

    /** The note on [key] starting at [tick]. */
    private fun MidiPiece.at(tick: Long, key: Int): Int =
        (0 until notes.size).first { notes.startMicros[it] == tempoMap.tickToMicros(tick) && notes.note(it) == key }

    /** Twelve bars of quarter notes in 4/4, climbing and falling around the treble staff. */
    private val twelveBars = piece { for (beat in 0 until 48) note(beat * 480L, 60 + beat % 12, 480) }

    @Test
    fun `bars fill systems of two on a phone, three systems a page`() {
        val score = layout(twelveBars)
        assertEquals(12, score.bars.count)
        assertEquals(6, score.systems.size)
        assertEquals(2, score.pageCount)
        assertEquals(1, score.systems[3].page)
        assertEquals(0, score.systems[3].slot)
        assertEquals(3..5, score.systemsOn(1))
        assertEquals(IntRange.EMPTY, score.systemsOn(2))
        assertTrue(score.systems.all { it.barCount == 2 })
        assertTrue(score.systems[5].final)
        assertFalse(score.systems[4].final)
        assertEquals(phone.pageWidth - phone.marginX, score.systems[0].right, 0.01f)
        assertTrue(score.quantized)
    }

    @Test
    fun `a note sits where the cursor passes as it sounds, and the system knows its notes`() {
        val score = layout(twelveBars)
        for (i in 0 until score.noteCount) {
            val start = twelveBars.notes.startMicros[i]
            val system = score.systems[score.system[i]]
            assertEquals("note $i", system.xAt(start), score.x[i], 0.01f)
            assertEquals(score.system[i], score.systemAt(start))
            assertTrue(i in system.firstNote until system.noteEnd)
        }
        // Across a bar line the next bar's first beat is right of the last one's.
        for (i in 1 until score.noteCount) if (score.system[i] == score.system[i - 1]) assertTrue(score.x[i] > score.x[i - 1])
        assertEquals(1, score.systemAt(score.bars.startMicros[2]))
        assertEquals(0, score.systemAt(score.bars.startMicros[2] - 1))
    }

    @Test
    fun `a sequenced file gets its note values`() {
        val values = piece {
            note(0, 72, 1920)        // whole C5
            note(1920, 74, 960)      // half D5
            note(2880, 76, 480)      // quarter E5
            note(3360, 77, 240)      // eighth F5
            note(3600, 79, 120)      // sixteenth G5
            note(3720, 81, 120)      // sixteenth A5
            note(3840, 71, 720)      // dotted quarter B4
            note(4560, 72, 240)      // eighth C5
        }
        val score = layout(values)
        assertTrue(score.quantized)
        val heads = (0 until score.noteCount).map { score.head[it].toInt() }
        assertEquals(listOf(Head.WHOLE, Head.HALF, Head.BLACK, Head.BLACK, Head.BLACK, Head.BLACK, Head.BLACK, Head.BLACK), heads)
        // The eighth and two sixteenths share beat 4 of bar 2, so they are beamed and lose their flags
        // (BeamsTest); the eighth alone in its beat keeps its flag.
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 1), (0 until score.noteCount).map { score.flags[it].toInt() })
        assertEquals(2, score.beams.size)   // the primary beam and the sixteenths' second beam
        assertEquals(listOf(false, false, false, false, false, false, true, false), score.dotted.toList())
        assertTrue(score.stemX[0].isNaN())                                  // a whole note has no stem
        assertTrue((1 until score.noteCount).none { score.stemX[it].isNaN() })
        assertTrue(score.durationEnd.all { it.isNaN() })                    // values, not duration lines
    }

    @Test
    fun `stems point up below the middle line and down from it, three and a half spaces long`() {
        val score = layout(piece { listOf(64, 71, 74, 43, 50, 24).forEachIndexed { i, key -> note(i * 480L, key, 480) } })
        assertEquals(listOf(true, false, false, true, false, true), score.stemUp.toList())
        // E4 on the treble's bottom line: 3.5 spaces up from the head.
        assertEquals(score.y[0] - 3.5f * space, score.stemTo[0], 0.01f)
        assertEquals(score.y[0] - 0.168f * space, score.stemFrom[0], 0.01f)
        assertEquals(score.x[0] + head - density, score.stemX[0], 0.01f)     // up: on the head's right
        assertEquals(score.x[1], score.stemX[1], 0.01f)                        // down: on its left
        assertEquals(score.y[1] + 3.5f * space, score.stemTo[1], 0.01f)
        // C1, far below the bass staff, reaches up to the staff's middle line.
        val system = score.systems[score.system[5]]
        assertEquals(system.bassBottom - 2 * space, score.stemTo[5], 0.01f)
    }

    @Test
    fun `a chord shares one stem and one flag, pointing away from its farthest head`() {
        val chord = piece { listOf(60, 64, 67).forEach { note(0, it, 240) } }
        val score = layout(chord)
        val c = chord.at(0, 60)
        val g = chord.at(0, 67)
        val stems = (0 until score.noteCount).filter { !score.stemX[it].isNaN() }
        assertEquals(listOf(c), stems)                 // up, carried by the lowest head
        assertTrue(score.stemUp[c])
        assertEquals(1, score.flags[c].toInt())
        assertEquals(1, (0 until score.noteCount).sumOf { score.flags[it].toInt() })
        assertEquals(score.y[g] - 3.5f * space, score.stemTo[c], 0.01f)
        assertEquals(score.y[c] - 0.168f * space, score.stemFrom[c], 0.01f)
    }

    @Test
    fun `two values struck together on one staff stem apart, the higher up`() {
        val voices = piece {
            note(0, 64, 960)   // a half note E4
            note(0, 72, 480)   // a quarter C5
        }
        val score = layout(voices)
        assertFalse(score.stemUp[voices.at(0, 64)])
        assertTrue(score.stemUp[voices.at(0, 72)])
    }

    @Test
    fun `a second moves its upper head aside, and the stem runs between them`() {
        val second = piece {
            note(0, 60, 480)
            note(0, 62, 480)
        }
        val score = layout(second)
        val c = second.at(0, 60)
        val d = second.at(0, 62)
        assertFalse(score.moved[c])
        assertTrue(score.moved[d])
        assertEquals(score.x[c] + head, score.x[d], 0.01f)
        assertEquals(score.x[c] + head - density, score.stemX[c], 0.01f)
    }

    @Test
    fun `accidentals once per pitch per bar, with naturals where the key needs them`() {
        val eFlat = piece(metas = { keySignature(0, -3) }) {
            listOf(70, 71, 71, 70, 71).forEachIndexed { i, key -> note(i * 480L, key, 480) }   // the fifth is in bar 2
        }
        val score = layout(eFlat)
        assertEquals(
            listOf(Accidental.NONE, Accidental.NATURAL, Accidental.NONE, Accidental.FLAT, Accidental.NATURAL),
            (0 until score.noteCount).map { score.accidental[it].toInt() },
        )
        assertTrue(score.accidentalX[0].isNaN())
        assertTrue(score.accidentalX[1] < score.x[1])
        assertEquals(score.y[0], score.y[1], 0.01f)   // B♭ and B♮ on the same line
    }

    @Test
    fun `accidentals in a chord stack leftwards so they don't collide`() {
        val chord = piece {
            note(0, 61, 480)   // C♯4
            note(0, 63, 480)   // D♯4
        }
        val score = layout(chord)
        val cSharp = chord.at(0, 61)
        val dSharp = chord.at(0, 63)
        assertEquals(Accidental.SHARP, score.accidental[cSharp].toInt())
        assertEquals(Accidental.SHARP, score.accidental[dSharp].toInt())
        assertTrue(score.accidentalX[cSharp] < score.accidentalX[dSharp] - 0.9f * space)
    }

    @Test
    fun `without a key signature black keys are sharps, one per bar`() {
        val plain = piece { listOf(61, 61, 60).forEachIndexed { i, key -> note(i * 480L, key, 480) } }
        val score = layout(plain)
        assertEquals(listOf(Accidental.SHARP, Accidental.NONE, Accidental.NATURAL), (0 until 3).map { score.accidental[it].toInt() })
        assertTrue(score.systems.all { s -> s.signKind.none { it == Sign.SHARP || it == Sign.FLAT } })
    }

    @Test
    fun `the key signature is drawn at every system, on both staves`() {
        val eFlat = piece(metas = { keySignature(0, -3) }) { for (bar in 0 until 8) note(bar * 1920L, 67, 1920) }
        val score = layout(eFlat)
        assertEquals(4, score.systems.size)
        for (system in score.systems) {
            assertEquals(6, system.signKind.count { it == Sign.FLAT })
            assertEquals(1, system.signKind.count { it == Sign.G_CLEF })
            assertEquals(1, system.signKind.count { it == Sign.F_CLEF })
            val flats = system.signKind.indices.filter { system.signKind[it] == Sign.FLAT }
            assertEquals(system.trebleBottom - 2 * space, system.signY[flats[0]], 0.01f)   // B♭4 on the middle line
            assertEquals(system.bassBottom - space, system.signY[flats[1]], 0.01f)         // B♭2 on the bass's second line
        }
    }

    @Test
    fun `a change of key mid-system to C major is cancelled with naturals`() {
        val change = piece(metas = { keySignature(0, -3); keySignature(1920, 0) }) {
            note(0, 67, 1920)
            note(1920, 67, 1920)
        }
        val score = layout(change)
        assertEquals(6, score.systems[0].signKind.count { it == Sign.NATURAL })
    }

    @Test
    fun `the time signature shows at the first system and where the metre changes`() {
        val metres = piece(metas = { timeSignature(0, 3, 4); timeSignature(4320, 4, 4) }) {   // 4/4 from the fourth bar, system 2's second
            for (beat in 0 until 9) note(beat * 480L, 67, 480)
            note(4320, 67, 1920)
            note(6240, 67, 1920)
        }
        val score = layout(metres)
        fun digits(s: Int) = score.systems[s].signKind.filter { it >= Sign.DIGIT }.map { it - Sign.DIGIT }
        assertEquals(listOf(3, 4, 3, 4), digits(0))
        assertEquals(listOf(4, 4, 4, 4), digits(1))
        assertEquals(emptyList<Int>(), digits(2))
        // The change sits inside bar 4, after its bar line, and the notes make room for it.
        val bar = 3
        assertTrue(score.bars.contentLeft[bar] > score.bars.left[bar] + 3 * space)
    }

    @Test
    fun `a performance keeps black heads with a line for each note's length`() {
        val random = java.util.Random(3)
        val played = piece(ppq = 384) {
            var tick = 0L
            repeat(60) {
                val length = 150L + random.nextInt(200)
                note(tick, 55 + random.nextInt(20), length)
                tick += 70 + random.nextInt(150)
            }
        }
        val score = layout(played)
        assertFalse(score.quantized)
        assertTrue((0 until score.noteCount).all { score.head[it].toInt() == Head.BLACK })
        assertTrue(score.stemX.all { it.isNaN() })
        assertTrue(score.flags.all { it.toInt() == 0 })
        val lines = (0 until score.noteCount).filter { !score.durationEnd[it].isNaN() }
        assertTrue(lines.size > score.noteCount / 2)
        assertTrue(lines.all { score.durationEnd[it] > score.x[it] + head })
    }

    @Test
    fun `ledger lines reach the piano's lowest and highest keys`() {
        val score = layout(piece { note(0, 24, 480); note(480, 107, 480) })
        assertEquals(-5, score.ledgers[0].toInt())
        assertEquals(8, score.ledgers[1].toInt())
        assertFalse(score.treble[0])
        assertTrue(score.treble[1])
    }

    @Test
    fun `notes the piano can't play are left out`() {
        val score = layout(piece { note(0, 12, 480); note(480, 60, 480) }, fold = false)
        assertEquals(-1, score.system[0])
        assertEquals(0, score.system[1])
        assertEquals(1, score.systems[0].firstNote)
    }

    @Test
    fun `a transposed piece reads in its new key`() {
        val eFlat = piece(metas = { keySignature(0, -3) }) { note(0, 70, 480) }
        val up = layout(eFlat, transpose = 2)   // F major: one flat
        assertEquals(2, up.systems[0].signKind.count { it == Sign.FLAT })
        assertEquals(Accidental.NONE, up.accidental[0].toInt())   // B♭ became C
    }

    @Test
    fun `dots sit in a space`() {
        val dots = piece {
            note(0, 64, 720)    // E4, a line
            note(960, 65, 720)  // F4, a space
        }
        val score = layout(dots)
        assertEquals(score.y[0] - space / 2, score.dotY[0], 0.01f)
        assertEquals(score.y[1], score.dotY[1], 0.01f)
        assertTrue(score.dotX[0] > score.x[0] + head)
    }

    @Test
    fun `each system may draw into the gaps, never onto its neighbours' staves`() {
        val score = layout(twelveBars)
        assertEquals(0f, score.systems[0].bandTop, 0.01f)
        assertEquals(score.systems[1].trebleTop, score.systems[0].bandBottom, 0.01f)
        assertEquals(score.systems[0].bassBottom, score.systems[1].bandTop, 0.01f)
        assertEquals(phone.pageHeight, score.systems[2].bandBottom, 0.01f)   // the page's last system
        assertEquals(0f, score.systems[3].bandTop, 0.01f)                    // the next page's first
    }

    @Test
    fun `within a bar the cursor moves with the beats, whatever the tempo`() {
        val slowing = piece(metas = { tempo(0, 500_000); tempo(960, 1_000_000) }) {   // half speed from beat 3
            note(0, 67, 1920)
            note(1920, 67, 1920)
        }
        val score = layout(slowing)
        val system = score.systems[0]
        val left = score.bars.contentLeft[0]
        val width = score.bars.contentRight[0] - left
        assertEquals(left + width / 2, system.xAt(slowing.tempoMap.tickToMicros(960)), 0.01f)
        assertEquals(left + width * 3 / 4, system.xAt(slowing.tempoMap.tickToMicros(1440)), 0.01f)
        assertEquals(0, system.barAtX(system.left))
        assertEquals(1, system.barAtX(score.bars.left[1] + 1f))
    }

    @Test
    fun `a wide panel sets pages side by side, even pages on the left`() {
        val tablet = metrics(1_168f, 430f, ScoreWidth.EXPANDED)
        assertEquals(2, tablet.pages)
        val long = piece { for (bar in 0 until 40) note(bar * 1920L, 67, 1920) }
        val score = layout(long, tablet)
        assertEquals(4, score.systems[0].barCount)
        for (system in score.systems) assertEquals(system.page % 2, system.slot)
        assertTrue(score.systems.all { it.right <= tablet.pageWidth })
    }

    @Test
    fun `a right-hand note below middle C lands on the treble staff, a left-hand one above it on the bass`() {
        val crossing = piece {
            note(0, 59, 480)       // B3, right hand
            note(480, 62, 480)     // D4, left hand
            note(960, 57, 2880)    // A3, right hand, tied over the bar line
            note(960, 45, 480)     // A2, right hand: five ledger lines under the treble, so it goes to the bass
            note(1440, 74, 480)    // D5, left hand: five above the bass, so it goes to the treble
        }
        val right = Hands.RIGHT
        val left = Hands.LEFT
        val given = mapOf(59 to right, 62 to left, 57 to right, 45 to right, 74 to left)
        val hands = ByteArray(crossing.notes.size) { given.getValue(crossing.notes.note(it)) }
        val score = layout(crossing, hands = hands)
        val b3 = crossing.at(0, 59)
        val d4 = crossing.at(480, 62)
        val a3 = crossing.at(960, 57)
        assertTrue(score.treble[b3])
        assertEquals(-1, score.ledgers[b3].toInt())                      // B3 hangs under middle C's ledger line
        assertFalse(score.treble[d4])
        assertEquals(1, score.ledgers[d4].toInt())                        // D4 sits on middle C's line over the bass
        assertTrue(score.treble[a3])
        assertTrue(score.tiedHeadCount(a3) > 0)
        for (k in 0 until score.tiedHeadCount(a3)) assertTrue(score.treble[score.tiedHead(a3, k)])   // its tied heads follow it
        assertFalse(score.treble[crossing.at(960, 45)])
        assertTrue(score.treble[crossing.at(1440, 74)])
        // The bass staff's rests leave room for the left hand's D4 there, not on the treble.
        val bar0Bass = (score.rests.inSystem(0)).filter { !score.rests.treble[it] && score.rests.bar[it] == 0 }
        assertTrue(bar0Bass.none { score.rests.x[it] >= score.x[d4] - 0.01f && score.rests.x[it] < score.x[d4] + head })
        // Without hands, the old rule: middle C and up on the treble staff.
        val plain = layout(crossing)
        assertFalse(plain.treble[b3])
        assertTrue(plain.treble[d4])
    }

    @Test
    fun `fingering numerals stand above the right hand's heads and under the left hand's, a chord's stacked`() {
        val piece = piece {
            note(0, 72, 480)       // C5 and E5, the right hand's chord (stem down)
            note(0, 76, 480)
            note(0, 48, 480)       // C3, the left hand
            note(480, 64, 480)     // E4, the right hand (stem up)
        }
        val given = mapOf(72 to 1, 76 to 3, 48 to 5, 64 to 2)
        val hands = ByteArray(piece.notes.size) { if (piece.notes.note(it) >= 60) Hands.RIGHT else Hands.LEFT }
        val fingers = ByteArray(piece.notes.size) { given.getValue(piece.notes.note(it)).toByte() }
        val score = layout(piece, hands = hands, fingers = fingers)
        val f = score.fingers
        assertEquals(4, f.size)
        fun numeral(key: Int) = (0 until f.size).first { piece.notes.note(f.note[it]) == key }
        val c5 = piece.at(0, 72)
        val e5 = piece.at(0, 76)
        val c3 = piece.at(0, 48)
        val e4 = piece.at(480, 64)
        val nC5 = numeral(72)
        val nE5 = numeral(76)
        val nC3 = numeral(48)
        val nE4 = numeral(64)
        assertEquals(listOf(1, 3, 5, 2), listOf(nC5, nE5, nC3, nE4).map { f.finger[it].toInt() })
        // The chord's two stack over its top head, E5's (3) above C5's (1), centred on the column.
        assertTrue(f.above[nC5] && f.above[nE5])
        assertTrue(f.baseline[nE5] < f.baseline[nC5] - phone.numeralHeight)
        assertTrue(f.baseline[nC5] < score.y[e5] - space / 2)
        assertEquals(score.x[c5] + head / 2, f.x[nC5], 0.01f)
        // The left hand's under its head.
        assertTrue(!f.above[nC3])
        assertTrue(f.baseline[nC3] - phone.numeralHeight > score.y[c3] + space / 2)
        // Over a stem that points up: above its tip.
        assertTrue(score.stemUp[e4])
        assertTrue(f.baseline[nE4] < score.stemTo[e4])
        // No fingering asked for, no numerals.
        assertEquals(0, layout(piece, hands = hands).fingers.size)
    }
}
