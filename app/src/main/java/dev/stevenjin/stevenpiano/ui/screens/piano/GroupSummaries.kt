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
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
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
import dev.stevenjin.stevenpiano.ui.label
import dev.stevenjin.stevenpiano.update.UpdateState
import dev.stevenjin.stevenpiano.web.WebStatus
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import dev.stevenjin.stevenpiano.piano.PianoSettings as PianoTable

/**
 * The one-line values on the hub's page rows (DESIGN.md › v1.5, v1.13 — M31b), worked out from what the
 * app already holds: the piano's last report and the app's own preferences. Never a read from the
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
    /** The MIDI keyboard (v1.11 — M29): "None", its name, or its name and "not connected". */
    val keyboard: String = InstrumentCopy.NONE,
    /** The instrument and its state (v1.13): "Steven Piano · Connected". */
    val instrument: String = instrumentLine(InstrumentCopy.STEVEN_PIANO, NOT_CONNECTED),
    val tabletSound: String = OFF,
    val guests: String = OFF,
    val updates: String = AUTOMATIC,
    val artwork: String = AUTOMATIC,
    val help: String = "",
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
        SettingsPage.TabletSound -> tabletSound
        SettingsPage.Schedule -> schedule
        SettingsPage.Remote -> remote
        SettingsPage.Guests -> guests
        SettingsPage.Display -> display
        SettingsPage.Kiosk -> kiosk
        SettingsPage.Updates -> updates
        SettingsPage.Artwork -> artwork
        SettingsPage.Help -> help
    }

    companion object {
        /** A value not known yet. */
        const val UNKNOWN = "—"

        /**
         * Every row's value; [web] where the web panel listens; [firmwareUpdate] and [firmwareVersion]
         * (Device Information's, v1.6 — M21) for Firmware and status; [nextSchedule] when the next schedule
         * starts; [keyboard] the MIDI keyboard's state (v1.11 — M29); [instrument] the Instrument row's value
         * ([instrumentLine]); [update] the app's updater; [version] the app's version (Help and about). Studio is a tab
         * of its own since v1.12 (M30).
         */
        fun from(
            piano: PianoState,
            settings: PianoSettings,
            web: WebStatus = WebStatus(),
            firmwareUpdate: FirmwareState = FirmwareState.Idle,
            firmwareVersion: String? = null,
            nextSchedule: ZonedDateTime? = null,
            keyboard: KeyboardState = KeyboardState(),
            instrument: String = instrumentLine(InstrumentCopy.STEVEN_PIANO, NOT_CONNECTED),
            update: UpdateState = UpdateState.Idle,
            version: String = "",
        ): GroupSummaries = GroupSummaries(
            feel = feel(piano),
            lighting = lighting(piano),
            pedal = pedal(piano),
            firmware = firmware(piano, firmwareUpdate, firmwareVersion),
            playback = playback(settings),
            display = display(settings),
            remote = remote(settings, web),
            kiosk = kiosk(settings),
            schedule = schedule(nextSchedule),
            keyboard = InstrumentCopy.keyboardValue(keyboard),
            instrument = instrument,
            tabletSound = tabletSound(settings),
            guests = guests(settings),
            updates = updates(settings, update),
            artwork = artwork(settings),
            help = help(version),
        )

        /** The Instrument row (v1.13): the instrument's name and its state, "Steven Piano · Connected". */
        fun instrumentLine(name: String, state: String): String = "$name · $state"

        /** When the next schedule starts, "Next Wed 12:30", or "None". */
        fun schedule(next: ZonedDateTime?): String = ScheduleCopy.hub(next)

        /**
         * The web panel: "Off", or "On" with the panel's address when it has one ("On · 100.101.2.3"), and
         * "· Internet" while it is on over the internet and the tablet enrolled (v1.10 — M26; "Cloud" until
         * v1.13): "On · 100.101.2.3 · Internet", or "Internet" alone with the panel off on the tablet's networks.
         */
        fun remote(settings: PianoSettings, web: WebStatus): String {
            val cloud = settings.cloudEnabled && settings.cloudEnrolled
            if (!settings.webEnabled) return if (cloud) INTERNET else OFF
            val local = web.panelHost?.let { "On · $it" } ?: "On"
            return if (cloud) "$local · $INTERNET" else local
        }

        /** Guests (v1.13): "Off", "On", or "On · approve first". */
        fun guests(settings: PianoSettings): String = when {
            !settings.webGuests -> OFF
            settings.webApproveFirst -> "On · approve first"
            else -> "On"
        }

        /** Kiosk mode: "On" or "Off" ("On" while unlocked for now too: it locks again). */
        fun kiosk(settings: PianoSettings): String = if (settings.kioskEnabled) "On" else OFF

        /** "Full power" while full power is on, else "Piano volume 70%". */
        fun feel(piano: PianoState): String {
            val values = (piano as? PianoState.Ready)?.values ?: return UNKNOWN
            if (values["fullpower"]?.let(::on) == true) return "Full power"
            val volume = values["volume"]?.toFloatOrNull() ?: return UNKNOWN
            return "Piano volume ${Format.percent(volume.roundToInt())}"
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

        /** Tablet sound (v1.13: its own page): "Off", or when it plays and the tablet volume, "Always · 70%". */
        fun tabletSound(settings: PianoSettings): String = when (settings.tabletSound) {
            TabletSoundMode.OFF -> OFF
            TabletSoundMode.WHEN_NOT_CONNECTED -> "When not connected · ${Format.percent(settings.tabletVolume)}"
            TabletSoundMode.ALWAYS -> "Always · ${Format.percent(settings.tabletVolume)}"
        }

        /** Display (v1.13: the app's look, now that the note views live on Now playing): "Follow system", "Light" or "Dark". */
        fun display(settings: PianoSettings): String = settings.appearance.label

        /**
         * Updates (v1.13): what the updater has to say ("1.14 available", "Downloading 1.14", "Ready to
         * install", "Installing…", "Restart to finish"), else whether it checks by itself: "Automatic" or "Off".
         */
        fun updates(settings: PianoSettings, update: UpdateState): String {
            val manifest = update.manifest
            return when {
                update is UpdateState.Installed -> if (update.restartNeeded) "Restart to finish" else "Updated to ${update.version}"
                update is UpdateState.Downloading && manifest != null -> "Downloading ${manifest.versionName}"
                update is UpdateState.ReadyToInstall -> "Ready to install"
                update is UpdateState.Installing -> "Installing…"
                manifest != null -> "${manifest.versionName} available"
                settings.checkForUpdates -> AUTOMATIC
                else -> OFF
            }
        }

        /** Library and artwork (v1.13): whether artwork is fetched by itself, "Automatic" or "Off". */
        fun artwork(settings: PianoSettings): String = if (settings.fetchArtworkAutomatically) AUTOMATIC else OFF

        /** Help and about (v1.13): the app's version, "Version 1.13". */
        fun help(version: String): String = if (version.isBlank()) "" else "Version $version"

        /** The instrument's state before it has said otherwise. */
        const val NOT_CONNECTED = "Not connected"

        private const val OFF = "Off"
        private const val AUTOMATIC = "Automatic"
        private const val INTERNET = "Internet"

        private fun on(wire: String): Boolean = wire.trim() != "0"
    }
}
