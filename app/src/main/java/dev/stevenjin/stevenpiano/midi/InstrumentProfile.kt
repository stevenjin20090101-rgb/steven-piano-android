// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

/**
 * What the instrument that plays can take (v1.11 — M29), so one router serves Steven Piano and any MIDI
 * piano. Pure.
 *
 * - [lowest]..[highest]: its keys; a note outside folds in by octaves, or is dropped, as the Fold setting
 *   says (transpose applies to a piece's notes only, never to keys played).
 * - [minOnsetGapMicros]: the least time between two strikes of one key (Steven Piano's solenoids need
 *   100 ms; a faster strike is dropped, never delayed); 0: none.
 * - [restrike]: a key struck while another source holds it is struck again (Note Off, then Note On), as a
 *   digital piano can; Steven Piano cannot strike a held key, so there the sources share it.
 * - [pedals]: the controllers that pass (CC64 on Steven Piano; CC64, CC66 and CC67 on a MIDI piano), each
 *   with its value; [pacedPedal]: a piece's CC64 is paced (Steven Piano's actuator moves a real pedal).
 * - [explicitOffs]: the stop sequence begins with a Note Off for every key the router holds, then every
 *   pedal at 0, then All Notes Off (many digital pianos ignore CC123); without, CC64 = 0 then CC123.
 */
class InstrumentProfile private constructor(
    val name: String,
    val lowest: Int,
    val highest: Int,
    val minOnsetGapMicros: Long,
    val restrike: Boolean,
    val pedals: IntArray,
    val pacedPedal: Boolean,
    val explicitOffs: Boolean,
) {
    /** Whether [controller] passes to this instrument. */
    fun forwards(controller: Int): Boolean = controller in pedals

    override fun toString(): String = name

    companion object {
        /** The school piano: keys C1-B7 (MIDI 24-107), 100 ms between strikes of a key, the sustain pedal alone, paced. */
        val StevenPiano = InstrumentProfile(
            name = "Steven Piano",
            lowest = KeyMap.LOWEST,
            highest = KeyMap.HIGHEST,
            minOnsetGapMicros = NoteRouter.MIN_ONSET_GAP_MICROS,
            restrike = false,
            pedals = intArrayOf(NoteRouter.SUSTAIN),
            pacedPedal = true,
            explicitOffs = false,
        )

        /** Any MIDI piano: A0-C8 (21-108), keys struck again, three pedals unpaced, explicit offs before All Notes Off. */
        val StandardPiano = InstrumentProfile(
            name = "Standard MIDI piano",
            lowest = 21,
            highest = 108,
            minOnsetGapMicros = 0L,
            restrike = true,
            pedals = intArrayOf(NoteRouter.SUSTAIN, NoteRouter.SOSTENUTO, NoteRouter.SOFT),
            pacedPedal = false,
            explicitOffs = true,
        )
    }
}
