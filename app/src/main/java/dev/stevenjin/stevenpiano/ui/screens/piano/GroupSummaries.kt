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
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.firmware.FirmwareVersion
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.schedule.ScheduleCopy
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.FirmwareCopy
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.ui.label
import dev.stevenjin.stevenpiano.web.WebStatus
import java.time.ZonedDateTime
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
    val remote: String,
    val kiosk: String,
    val schedule: String = ScheduleCopy.NONE,
    val studio: String = StudioCopy.hub(0, emptyList()),
    /** The MIDI keyboard (v1.11 — M29): "None", its name, or its name and "not connected". */
    val keyboard: String = InstrumentCopy.NONE,
    /** The instrument (v1.11 — M29): "Steven Piano", or the MIDI piano's name. */
    val instrument: String = InstrumentCopy.STEVEN_PIANO,
) {
    /** The value on [page]'s row. */
    fun of(page: SettingsPage): String = when (page) {
        SettingsPage.Instrument -> instrument
        SettingsPage.Keyboard -> keyboard
        SettingsPage.Feel -> feel
        SettingsPage.Lighting -> lighting
        SettingsPage.Pedal -> pedal
        SettingsPage.Firmware -> firmware
        SettingsPage.Playback -> playback
        SettingsPage.Display -> display
        SettingsPage.Remote -> remote
        SettingsPage.Kiosk -> kiosk
        SettingsPage.Schedule -> schedule
        SettingsPage.Studio -> studio
    }

    companion object {
        /** A value not known yet. */
        const val UNKNOWN = "—"

        /**
         * Every row's value; [wide] when the window shows the score beside the notes (Note display
         * then picks the roll's style); [web] where the web panel listens; [firmwareUpdate] and
         * [firmwareVersion] (Device Information's, v1.6 — M21) for Firmware and status; [nextSchedule]
         * when the next schedule starts; [studio] Studio's own value ([StudioCopy.hub], v1.7 — M23); [keyboard] the MIDI
         * keyboard's state (v1.11 — M29).
         */
        fun from(
            piano: PianoState,
            settings: PianoSettings,
            wide: Boolean,
            web: WebStatus = WebStatus(),
            firmwareUpdate: FirmwareState = FirmwareState.Idle,
            firmwareVersion: String? = null,
            nextSchedule: ZonedDateTime? = null,
            studio: String = StudioCopy.hub(0, emptyList()),
            keyboard: KeyboardState = KeyboardState(),
            instrument: String = InstrumentCopy.STEVEN_PIANO,
        ): GroupSummaries = GroupSummaries(
            feel = feel(piano),
            lighting = lighting(piano),
            pedal = pedal(piano),
            firmware = firmware(piano, firmwareUpdate, firmwareVersion),
            playback = playback(settings),
            display = display(settings, wide),
            remote = remote(settings, web),
            kiosk = kiosk(settings),
            schedule = schedule(nextSchedule),
            studio = studio,
            keyboard = InstrumentCopy.keyboardValue(keyboard),
            instrument = instrument,
        )

        /** When the next schedule starts, "Next Wed 12:30", or "None". */
        fun schedule(next: ZonedDateTime?): String = ScheduleCopy.hub(next)

        /**
         * "Off", or "On" with the panel's address when it has one ("On · 100.101.2.3"), and "· Cloud"
         * while remote access over the internet is on and the tablet enrolled (v1.10 — M26): "On ·
         * 100.101.2.3 · Cloud", or "Cloud" alone with Web control off.
         */
        fun remote(settings: PianoSettings, web: WebStatus): String {
            val cloud = settings.cloudEnabled && settings.cloudEnrolled
            if (!settings.webEnabled) return if (cloud) CLOUD else OFF
            val local = web.panelHost?.let { "On · $it" } ?: "On"
            return if (cloud) "$local · $CLOUD" else local
        }

        /** Kiosk mode: "On" or "Off" ("On" while unlocked for now too: it locks again). */
        fun kiosk(settings: PianoSettings): String = if (settings.kioskEnabled) "On" else OFF

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

        /**
         * Firmware and status with its update (v1.6 — M21): "Update available" while a newer release
         * is known, "Updating…" while one is sent, else the release the piano reports ("2.0.0", from
         * Device Information, or its dump's `!fw`), else what [firmware] reads.
         */
        fun firmware(piano: PianoState, update: FirmwareState, reported: String?): String = when {
            update.busy -> FirmwareCopy.HUB_UPDATING
            update.offered -> FirmwareCopy.HUB_AVAILABLE
            else -> FirmwareVersion.parse(reported ?: (piano as? PianoState.Ready)?.facts?.get("fw"))?.release ?: firmware(piano)
        }

        /** The pause before each piece, then the default tempo: "2 s pause · 100%", or "No pause · 100%". */
        fun playback(settings: PianoSettings): String {
            val pause = if (settings.preRollMs == 0) "No pause" else "${Format.seconds(settings.preRollMs)} pause"
            return "$pause · ${Format.percent(settings.defaultTempoPct)}"
        }

        /** The note display's name; on wide screens the roll's style, as the Display page offers it there. */
        fun display(settings: PianoSettings, wide: Boolean): String =
            (if (wide) settings.noteDisplay.rollStyle else settings.noteDisplay).label

        private const val OFF = "Off"
        private const val CLOUD = "Cloud"

        private fun on(wire: String): Boolean = wire.trim() != "0"
    }
}
