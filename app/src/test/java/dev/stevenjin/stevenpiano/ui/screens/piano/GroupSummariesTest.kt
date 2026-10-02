// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import dev.stevenjin.stevenpiano.audio.TabletSoundMode
import dev.stevenjin.stevenpiano.firmware.FirmwareFailures
import dev.stevenjin.stevenpiano.firmware.FirmwareManifest
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.firmware.OtaExample
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.MidiNames
import dev.stevenjin.stevenpiano.instruments.MidiTransport
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.settings.Appearance
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.StandbyCanvas
import dev.stevenjin.stevenpiano.settings.StandbyShows
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.update.UpdateState
import dev.stevenjin.stevenpiano.web.WebStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The hub's one-line values: from what the app holds, never a read from the piano. */
class GroupSummariesTest {
    private fun ready(vararg values: Pair<String, String>, facts: Map<String, String> = emptyMap()) =
        PianoState.Ready(values.toMap(), facts)

    private val unknown = GroupSummaries.UNKNOWN

    @Test
    fun `Feel reads full power, or the volume`() {
        assertEquals("Full power", GroupSummaries.feel(ready("fullpower" to "1", "volume" to "100")))
        assertEquals("full power wins over the volume", "Full power", GroupSummaries.feel(ready("fullpower" to "1", "volume" to "55")))
        assertEquals("Piano volume 70%", GroupSummaries.feel(ready("fullpower" to "0", "volume" to "70")))
        assertEquals("Piano volume 0%", GroupSummaries.feel(ready("fullpower" to "0", "volume" to "0")))
        assertEquals("firmware without the names", unknown, GroupSummaries.feel(ready("fullpower" to "0")))
    }

    @Test
    fun `Lighting reads off, or the mode and the brightness as the piano rounds it`() {
        assertEquals("Off", GroupSummaries.lighting(ready("leds" to "0", "ledmode" to "3", "ledbright" to "160")))
        assertEquals("Reactive · 62%", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "3", "ledbright" to "160")))
        assertEquals("Static · 100%", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "1", "ledbright" to "255")))
        assertEquals("the firmware's own (40 x 100) / 255", "Rainbow · 15%", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "2", "ledbright" to "40")))
        assertEquals("a strip whose mode is Off is off", "Off", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "0", "ledbright" to "160")))
        assertEquals("Reactive", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "3")))
        assertEquals(unknown, GroupSummaries.lighting(ready()))
    }

    @Test
    fun `Pedal reads on or off, and Firmware the version`() {
        assertEquals("On", GroupSummaries.pedal(ready("pedalon" to "1")))
        assertEquals("Off", GroupSummaries.pedal(ready("pedalon" to "0")))
        assertEquals(unknown, GroupSummaries.pedal(ready()))
        assertEquals("1.4.0", GroupSummaries.firmware(ready(facts = mapOf("fw" to "1.4.0"))))
        assertEquals(unknown, GroupSummaries.firmware(ready(facts = mapOf("fw" to " "))))
        assertEquals(unknown, GroupSummaries.firmware(ready()))
    }

    @Test
    fun `until the piano answers its four rows read a dash, and the app's rows still read`() {
        for (state in listOf(PianoState.Unknown, PianoState.Unsupported)) {
            val rows = GroupSummaries.from(state, PianoSettings())
            assertEquals(listOf(unknown, unknown, unknown, unknown), listOf(rows.feel, rows.lighting, rows.pedal, rows.firmware))
            assertEquals("2 s pause · 100%", rows.playback)
            assertEquals("Follow system", rows.display)
        }
    }

    @Test
    fun `Playback reads the pause before each piece, then the default tempo`() {
        assertEquals("2 s pause · 100%", GroupSummaries.playback(PianoSettings()))
        assertEquals("2 s pause · 85%", GroupSummaries.playback(PianoSettings(defaultTempoPct = 85)))
        assertEquals("No pause · 100%", GroupSummaries.playback(PianoSettings(preRollMs = 0)))
        assertEquals("0.5 s pause · 100%", GroupSummaries.playback(PianoSettings(preRollMs = 500)))
        assertEquals("2.5 s pause · 120%", GroupSummaries.playback(PianoSettings(preRollMs = 2_500, defaultTempoPct = 120)))
        assertEquals("5 s pause · 100%", GroupSummaries.playback(PianoSettings(preRollMs = 5_000)))
    }

    @Test
    fun `Display reads the appearance, now that the note views live on Now playing (v1_13)`() {
        assertEquals("Follow system", GroupSummaries.display(PianoSettings()))
        assertEquals("Dark", GroupSummaries.display(PianoSettings(appearance = Appearance.DARK)))
        val changed = PianoSettings(
            appearance = Appearance.LIGHT,
            noteDisplay = NoteDisplay.STAFF,
            displayModeAfterMinute = true,
            standbyCanvas = StandbyCanvas.INK,
            standbyShows = StandbyShows.PAPER_ROLL,
        )
        assertEquals("the resting screen and the note view don't change it", "Light", GroupSummaries.display(changed))
    }

    @Test
    fun `each page's row takes its own value`() {
        val piano = ready("fullpower" to "0", "volume" to "70", "leds" to "0", "pedalon" to "1", facts = mapOf("fw" to "emulator"))
        val rows = GroupSummaries.from(
            piano,
            PianoSettings(defaultTempoPct = 90, noteDisplay = NoteDisplay.FALLING, webEnabled = true, tabletSound = TabletSoundMode.ALWAYS, tabletVolume = 70),
            web = WebStatus(running = true, tailnet = "100.101.2.3"),
            instrument = GroupSummaries.instrumentLine("Steven Piano", "Connected"),
            version = "1.13",
        )
        assertEquals(
            listOf(
                "Steven Piano · Connected", "None", "Piano volume 70%", "Off", "On", "emulator", "2 s pause · 90%", "Always · 70%", "None",
                "On · 100.101.2.3", "Off", "Follow system", "Off", "Automatic", "Automatic", "Version 1.13",
            ),
            SettingsPage.entries.map { rows.of(it) },
        )
    }

    @Test
    fun `the new pages read their own values (v1_13 M31b)`() {
        assertEquals("Steven Piano · Not connected", GroupSummaries.from(PianoState.Unknown, PianoSettings()).of(SettingsPage.Instrument))
        assertEquals("FP-30X · Connected", GroupSummaries.instrumentLine("FP-30X", "Connected"))
        assertEquals("Off", GroupSummaries.tabletSound(PianoSettings(tabletSound = TabletSoundMode.OFF)))
        assertEquals("When not connected · 45%", GroupSummaries.tabletSound(PianoSettings(tabletSound = TabletSoundMode.WHEN_NOT_CONNECTED, tabletVolume = 45)))
        assertEquals("Off", GroupSummaries.guests(PianoSettings()))
        assertEquals("On", GroupSummaries.guests(PianoSettings(webGuests = true, webApproveFirst = false)))
        assertEquals("On · approve first", GroupSummaries.guests(PianoSettings(webGuests = true, webApproveFirst = true)))
        assertEquals("Automatic", GroupSummaries.updates(PianoSettings(checkForUpdates = true), UpdateState.Idle))
        assertEquals("Off", GroupSummaries.updates(PianoSettings(checkForUpdates = false), UpdateState.UpToDate))
        assertEquals("Restart to finish", GroupSummaries.updates(PianoSettings(), UpdateState.Installed("1.13")))
        assertEquals("Updated to 1.13", GroupSummaries.updates(PianoSettings(), UpdateState.Installed("1.13", restartNeeded = false)))
        assertEquals("Automatic", GroupSummaries.artwork(PianoSettings(fetchArtworkAutomatically = true)))
        assertEquals("Off", GroupSummaries.artwork(PianoSettings(fetchArtworkAutomatically = false)))
        assertEquals("Version 1.13", GroupSummaries.help("1.13"))
        assertEquals("", GroupSummaries.help(""))
    }

    @Test
    fun `Keyboard reads None, the keyboard's name, or its name not connected (v1_11 M29)`() {
        val chosen = KeyboardState.Chosen(MidiNames.usbKey("Roland", "FP-30X", "1"), "FP-30X", MidiTransport.USB)
        assertEquals("None", GroupSummaries.from(PianoState.Unknown, PianoSettings()).of(SettingsPage.Keyboard))
        assertEquals("FP-30X", GroupSummaries.from(PianoState.Unknown, PianoSettings(), keyboard = KeyboardState(chosen, KeyboardState.Phase.Connected)).of(SettingsPage.Keyboard))
        assertEquals(
            "FP-30X · not connected",
            GroupSummaries.from(PianoState.Unknown, PianoSettings(), keyboard = KeyboardState(chosen, KeyboardState.Phase.NotConnected)).of(SettingsPage.Keyboard),
        )
    }

    @Test
    fun `Firmware and status reads Update available while a newer release is known, and the release the piano reports`() {
        val manifest = FirmwareManifest.parse(OtaExample.manifestJson())
        val connected = ready(facts = mapOf("fw" to "2.0.0+a1b2c3d"))
        assertEquals("the release, without the build", "2.0.0", GroupSummaries.firmware(connected, FirmwareState.Idle, "2.0.0+a1b2c3d"))
        assertEquals("Device Information's version first", "2.1.0", GroupSummaries.firmware(connected, FirmwareState.Idle, "2.1.0+0000000"))
        assertEquals("the dump's when there is no other", "2.0.0", GroupSummaries.firmware(connected, FirmwareState.Idle, null))
        assertEquals("older firmware's hash as it was", "a1b2c3d", GroupSummaries.firmware(ready(facts = mapOf("fw" to "a1b2c3d")), FirmwareState.Idle, null))
        assertEquals(unknown, GroupSummaries.firmware(PianoState.Unknown, FirmwareState.Idle, null))
        for (offered in listOf(
            FirmwareState.Available(manifest),
            FirmwareState.UsbOnly(manifest),
            FirmwareState.NeedsNewerApp(manifest),
            FirmwareState.Failed(FirmwareFailures.DIDNT_FINISH, retryable = true, manifest = manifest),
            FirmwareState.Failed(FirmwareFailures.OFFLINE, retryable = true, manifest = manifest, check = true),
        )) {
            assertEquals(offered.toString(), "Update available", GroupSummaries.firmware(connected, offered, "2.0.0+a1b2c3d"))
            assertEquals("also before the piano answers", "Update available", GroupSummaries.firmware(PianoState.Unknown, offered, null))
        }
        for (busy in listOf(
            FirmwareState.Downloading(manifest, 0, 1),
            FirmwareState.Sending(manifest, 1, 2, 0),
            FirmwareState.Restarting(manifest),
        )) {
            assertEquals("Updating…", GroupSummaries.firmware(connected, busy, "2.0.0+a1b2c3d"))
        }
        assertEquals("2.0.0", GroupSummaries.firmware(connected, FirmwareState.UpToDate("2.0.0"), "2.0.0+a1b2c3d"))
        assertEquals("a refusal Retry won't mend offers nothing", "2.0.0",
            GroupSummaries.firmware(connected, FirmwareState.Failed(FirmwareFailures.DIDNT_FINISH, retryable = false, manifest = manifest), "2.0.0+a1b2c3d"))
        val rows = GroupSummaries.from(connected, PianoSettings(), firmwareUpdate = FirmwareState.Available(manifest), firmwareVersion = "2.0.0+a1b2c3d")
        assertEquals("Update available", rows.of(SettingsPage.Firmware))
    }

    @Test
    fun `Schedule reads when the next one starts, or None`() {
        val wednesday = ZonedDateTime.of(LocalDateTime.parse("2026-09-30T12:30"), ZoneId.of("America/New_York"))
        assertEquals("None", GroupSummaries.schedule(null))
        assertEquals("Next Wed 12:30", GroupSummaries.schedule(wednesday))
        assertEquals("Next Sun 07:05", GroupSummaries.schedule(wednesday.plusDays(4).withHour(7).withMinute(5)))
        assertEquals("the Schedule row reads without the piano", "Next Wed 12:30", GroupSummaries.from(PianoState.Unknown, PianoSettings(), nextSchedule = wednesday).schedule)
        assertEquals("None", GroupSummaries.from(PianoState.Unknown, PianoSettings()).of(SettingsPage.Schedule))
    }

    @Test
    fun `Web panel reads off, or on with the panel's address`() {
        val on = PianoSettings(webEnabled = true, webPinSet = true)
        assertEquals("Off", GroupSummaries.remote(PianoSettings(), WebStatus(running = true, tailnet = "100.101.2.3")))
        assertEquals("On · 100.101.2.3", GroupSummaries.remote(on, WebStatus(running = true, tailnet = "100.101.2.3", wifi = "192.168.1.20")))
        assertEquals("guests only on the Wi-Fi: no panel address", "On", GroupSummaries.remote(on, WebStatus(running = true, wifi = "192.168.1.20")))
        assertEquals("On · 192.168.1.20", GroupSummaries.remote(on, WebStatus(running = true, wifi = "192.168.1.20", panelOnWifi = true)))
        assertEquals("On", GroupSummaries.remote(on, WebStatus()))
        assertEquals("the Remote row reads without the piano", "Off", GroupSummaries.from(PianoState.Unknown, PianoSettings()).remote)
    }

    @Test
    fun `Web panel adds Internet while it is on over the internet and enrolled (v1_10 M26, renamed in v1_13)`() {
        val enrolled = PianoSettings(webPinSet = true, cloudHost = "relay.example.dev", cloudPianoId = "abcdefgh2345", cloudSecretSet = true)
        val lan = WebStatus(running = true, tailnet = "100.101.2.3")
        assertEquals("On · 100.101.2.3 · Internet", GroupSummaries.remote(enrolled.copy(webEnabled = true, cloudEnabled = true), lan))
        assertEquals("Internet", GroupSummaries.remote(enrolled.copy(cloudEnabled = true), WebStatus()))
        assertEquals("On · 100.101.2.3", GroupSummaries.remote(enrolled.copy(webEnabled = true), lan))
        assertEquals("not enrolled: no Internet yet", "Off", GroupSummaries.remote(PianoSettings(cloudEnabled = true, cloudHost = "relay.example.dev"), WebStatus()))
        assertEquals("Off", GroupSummaries.remote(enrolled, WebStatus()))
    }

    @Test
    fun `Kiosk reads on or off, with or without the piano`() {
        assertEquals("Off", GroupSummaries.kiosk(PianoSettings()))
        assertEquals("On", GroupSummaries.kiosk(PianoSettings(kioskEnabled = true, kioskPinSet = true)))
        assertEquals("On", GroupSummaries.from(PianoState.Unknown, PianoSettings(kioskEnabled = true)).of(SettingsPage.Kiosk))
    }
}
