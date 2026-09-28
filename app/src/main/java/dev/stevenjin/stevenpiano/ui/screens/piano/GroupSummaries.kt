// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.runtime.Immutable
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.label
import kotlin.math.roundToInt
import dev.stevenjin.stevenpiano.piano.PianoSettings as PianoTable

/**
 * The one-line values on the hub's page rows (DESIGN.md › v1.5), worked out from what the app
 * already holds: the piano's last report and the app's own preferences. Never a read from the
 * piano. While the piano has not answered (not connected, still reading, or firmware without the
 * settings) its four rows read [UNKNOWN] and still open their pages.
 */
@Immutable
data class GroupSummaries(
    val feel: String,
    val lighting: String,
    val pedal: String,
    val firmware: String,
    val playback: String,
    val display: String,
) {
    /** The value on [page]'s row. */
    fun of(page: SettingsPage): String = when (page) {
        SettingsPage.Feel -> feel
        SettingsPage.Lighting -> lighting
        SettingsPage.Pedal -> pedal
        SettingsPage.Firmware -> firmware
        SettingsPage.Playback -> playback
        SettingsPage.Display -> display
    }

    companion object {
        /** A value not known yet. */
        const val UNKNOWN = "—"

        /** Every row's value; [wide] when the window shows the score beside the notes (Note display then picks the roll's style). */
        fun from(piano: PianoState, settings: PianoSettings, wide: Boolean): GroupSummaries = GroupSummaries(
            feel = feel(piano),
            lighting = lighting(piano),
            pedal = pedal(piano),
            firmware = firmware(piano),
            playback = playback(settings),
            display = display(settings, wide),
        )

        /** "Full power" while full power is on, else "Volume 70%". */
        fun feel(piano: PianoState): String {
            val values = (piano as? PianoState.Ready)?.values ?: return UNKNOWN
            if (values["fullpower"]?.let(::on) == true) return "Full power"
            val volume = values["volume"]?.toFloatOrNull() ?: return UNKNOWN
            return "Volume ${Format.percent(volume.roundToInt())}"
        }

        /** "Off" while the strip (or its mode) is off, else the mode and the brightness: "Reactive · 62%". */
        fun lighting(piano: PianoState): String {
            val values = (piano as? PianoState.Ready)?.values ?: return UNKNOWN
            if (values["leds"]?.let(::on) == false) return OFF
            val mode = values["ledmode"]?.let { PianoTable.named("ledmode")?.display(it) }
            if (mode == OFF) return OFF
            val brightness = values["ledbright"]?.let { PianoTable.named("ledbright")?.display(it) }?.toIntOrNull()?.let(Format::percent)
            return listOfNotNull(mode, brightness).joinToString(" · ").ifEmpty { UNKNOWN }
        }

        /** The sustain pedal: "On" or "Off". */
        fun pedal(piano: PianoState): String {
            val values = (piano as? PianoState.Ready)?.values ?: return UNKNOWN
            return values["pedalon"]?.let { if (on(it)) "On" else OFF } ?: UNKNOWN
        }

        /** The piano's firmware version, as it reports it. */
        fun firmware(piano: PianoState): String =
            (piano as? PianoState.Ready)?.facts?.get("fw")?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN

        /** The default tempo, "100%" (from M16, the pause before each piece comes first: "2 s pause · 100%"). */
        fun playback(settings: PianoSettings): String = Format.percent(settings.defaultTempoPct)

        /** The note display's name; on wide screens the roll's style, as the Display page offers it there. */
        fun display(settings: PianoSettings, wide: Boolean): String =
            (if (wide) settings.noteDisplay.rollStyle else settings.noteDisplay).label

        private const val OFF = "Off"

        private fun on(wire: String): Boolean = wire.trim() != "0"
    }
}
