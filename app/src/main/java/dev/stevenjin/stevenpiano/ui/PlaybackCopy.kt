// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.Performance
import dev.stevenjin.stevenpiano.player.PianoFacts

/**
 * What the Playback page says of how a piece is played (DESIGN.md › v1.16 — M44): the chips of Dynamic range and
 * Expression, the re-strike time's value, and the line under Velocity about the piano's Full power. The rows' notes are
 * [SettingNotes]'.
 */
object PlaybackCopy {
    /** Dynamic range's chips, in `DynamicRange`'s order. */
    val RANGES = listOf("Narrow", "Natural", "Wide")

    /** Expression's chips, in `ExpressionLevel`'s order. */
    val EXPRESSIONS = listOf("Off", "Light", "Full")

    const val FULL_POWER_ON =
        "Full power is on: every note strikes at full strength. Turn it off on Sound and touch to hear dynamics and expression."
    const val FULL_POWER_OFF = "Dynamics are heard with Full power off."

    /** The line under Velocity: what the connected piano's Full power means for dynamics, as it reports it. */
    fun fullPower(piano: PianoState): String {
        val on = (piano as? PianoState.Ready)?.values?.get(FULL_POWER)?.trim()?.let { it != "0" } == true
        return if (on) FULL_POWER_ON else FULL_POWER_OFF
    }

    /** The piano's own repeat period as the connected piano reports it, or null. */
    fun pianoRepeatMs(piano: PianoState): Int? = PianoFacts.read((piano as? PianoState.Ready)?.facts?.get(REPEAT_PERIOD)).repeatMs

    /** The re-strike time as its row reads: "150 ms", "Auto · 110 ms from the piano", "Auto · 100 ms". */
    fun restrike(ms: Int, pianoMs: Int?): String = when {
        ms > Performance.AUTO -> "$ms ms"
        pianoMs != null -> "Auto · $pianoMs ms from the piano"
        else -> "Auto · ${Performance.DEFAULT_RESTRIKE_MS} ms"
    }

    private const val FULL_POWER = "fullpower"
    private const val REPEAT_PERIOD = "repeatms"
}
