// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

/**
 * What the LED strip shows between pieces in Reactive (M81, Piano › Lights and screen › Between pieces): the tablet's own
 * choice, kept with the app's other settings, never the piano's. [SOLID]: the whole strip in the colour shown (the cover's,
 * or the custom colour) while nothing plays, fading to the notes as a piece starts (the default); [NOTES]: the notes alone,
 * as before M81.
 */
enum class LedRestChoice { SOLID, NOTES }

/**
 * The strip at rest (M81, firmware 2.1.3; `~/.claude/plans/m81-led-rest-firmware-brief.md`), pure: its one key and its
 * fact, what the tablet wants the piano to hold, and when the page shows the choice. A piano whose dump lacks
 * `!ledrestproto=1` gets none of it: nothing shows and nothing is sent.
 *
 * - `ledrest 0|1`: in Reactive (`ledmode=3`), 1 is the colour shown solid along the strip, 0 Reactive as before. **Never
 *   saved** (the piano's `save` and its auto-save don't know it, 0 at boot), and over Bluetooth it ends with the link that
 *   set it: back to 0 when that connection drops. On the tablet always a temporary write ([PianoSettingsRepository.rest]).
 */
object LedRest {
    const val KEY = "ledrest"

    /** The fact a piano with the key reports: `!ledrestproto=1`. */
    const val PROTO_FACT = "ledrestproto"

    const val ON = "1"
    const val OFF = "0"

    /** Whether the piano's [facts] say it has `ledrest`. */
    fun supported(facts: Map<String, String>?): Boolean = facts?.get(PROTO_FACT)?.trim() == "1"

    /** Whether [value] is what the piano takes: exactly `0` or `1` (anything else: "usage: ledrest 0|1"). */
    fun accepts(value: String): Boolean = value == ON || value == OFF

    /**
     * The value the tablet wants the piano to hold: [ON] while nothing [playing], with [SOLID][LedRestChoice.SOLID] chosen,
     * the strip's [mode] Reactive and its [colour] Album art or Custom (with Palette the page shows no colour to glow in),
     * unless [guest] play has the piano; [OFF] otherwise; null (send nothing) on a piano that isn't [supported].
     */
    fun wanted(supported: Boolean, mode: String?, choice: LedRestChoice, colour: LedColourSource, playing: Boolean, guest: Boolean): String? = when {
        !supported -> null
        choice == LedRestChoice.SOLID && mode?.trim() == LedColour.REACTIVE && colour != LedColourSource.PALETTE && !playing && !guest -> ON
        else -> OFF
    }

    /**
     * Whether Lights and screen shows Between pieces for [ready] (null: not read yet): in Reactive, with the tablet's [colour]
     * Album art or Custom, on a piano with the fact.
     */
    fun shows(ready: PianoState.Ready?, colour: LedColourSource): Boolean =
        ready != null && supported(ready.facts) && ready.values[LedColour.MODE]?.trim() == LedColour.REACTIVE &&
            colour != LedColourSource.PALETTE
}
