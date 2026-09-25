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

/**
 * Pieces for the engraving tests (beams, rests, ties, tempo marks, dynamics): notes written at
 * ticks in one track, tempo and signatures in another, parsed as the app parses files and laid out
 * on a phone's score panel (one page, two bars a system) at density 2.
 */
internal object ScoreFixtures {
    const val DENSITY = 2f
    const val SPACE = 6f * DENSITY
    const val HEAD = 1.18f * SPACE

    /** A phone's score panel by default: 379 x 400 dp, two bars a system, three systems a page. */
    fun metrics(widthDp: Float = 379f, heightDp: Float = 400f, width: ScoreWidth = ScoreWidth.COMPACT): ScoreMetrics =
        ScoreMetrics.forPanel(width, widthDp * DENSITY, heightDp * DENSITY, DENSITY, HEAD, 2.74f * SPACE)

    /** Notes in any order; written in time order, a release before a strike at one tick. */
    class Notes {
        internal val events = mutableListOf<Event>()

        fun note(tick: Long, key: Int, length: Long, velocity: Int = 80) {
            events += Event(tick, key, true, velocity)
            events += Event(tick + length, key, false, 0)
        }
    }

    class Event(val tick: Long, val key: Int, val on: Boolean, val velocity: Int)

    /** A two-track file: [metas] (tempo, signatures) in the first, [notes] in the second. */
    fun piece(ppq: Int = 480, metas: SmfBuilder.Track.() -> Unit = {}, notes: Notes.() -> Unit): MidiPiece {
        val events = Notes().apply(notes).events.sortedWith(compareBy({ it.tick }, { it.on }))
        return SmfParser.parse(
            SmfBuilder(format = 1, division = ppq).track(metas).track {
                for (e in events) if (e.on) noteOn(e.tick, e.key, e.velocity) else noteOff(e.tick, e.key)
            }.build(),
        )
    }

    fun layout(piece: MidiPiece, metrics: ScoreMetrics = metrics(), transpose: Int = 0, fold: Boolean = true): ScoreLayout =
        ScoreLayoutEngine.layout(
            piece.notes,
            IntArray(piece.notes.size) { KeyMap.map(piece.notes.note(it), transpose, fold) },
            piece.tempoMap,
            piece.barStartsMicros,
            piece.keySignatures.map { it.transposed(transpose) },
            metrics,
            piece.timeSignatures,
        )

    /** The note on [key] starting at [tick]. */
    fun MidiPiece.at(tick: Long, key: Int): Int =
        (0 until notes.size).first { notes.startMicros[it] == tempoMap.tickToMicros(tick) && notes.note(it) == key }
}
