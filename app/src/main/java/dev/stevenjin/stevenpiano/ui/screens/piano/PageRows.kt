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
import dev.stevenjin.stevenpiano.ui.FirmwareCopy
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.TabletSoundCopy

/**
 * One row of an app page (a page whose rows are not the piano's settings table): its [anchor] (where
 * search scrolls to, unique across the tab), its [label] as the row shows it, the [page] and the
 * [section] eyebrow it sits under (null: the page's one unnamed section), and [synonyms] the search
 * also finds it by. The page draws its row with this label and anchor, and the search index is built
 * from the same rows, so the two can't drift apart.
 */
@Immutable
data class AppRow(val anchor: String, val label: String, val page: SettingsPage, val section: String? = null, val synonyms: List<String> = emptyList())

/**
 * The app pages' rows (v1.13 — M31b), page by page in the hub's order: the table the pages draw their
 * labels from and [SettingsIndex] indexes. Rows that show only sometimes (Forget the keyboard, the panel's
 * address, Unlock for now…) are here too: search opens their page at its top when the row isn't shown.
 */
object PageRows {
    // ---- INSTRUMENTS › Instrument --------------------------------------------------------------------
    val CONNECTION = AppRow("instrument.connection", "Connect or disconnect", SettingsPage.Instrument, InstrumentCopy.THIS_INSTRUMENT, listOf("bluetooth", "link"))
    val AUTO_CONNECT = AppRow("instrument.autoconnect", "Auto-connect on launch", SettingsPage.Instrument, InstrumentCopy.THIS_INSTRUMENT, listOf("automatically", "startup"))
    val STEVEN_PIANO = AppRow("instrument.steven", InstrumentCopy.STEVEN_PIANO, SettingsPage.Instrument, InstrumentCopy.CHOOSE, listOf("school piano", "player piano"))
    val ANOTHER_PIANO = AppRow("instrument.midi", InstrumentCopy.ANOTHER_MIDI_PIANO, SettingsPage.Instrument, InstrumentCopy.CHOOSE, listOf("digital piano", "instrument", "output"))
    val ALL_KEYS_OFF = AppRow("instrument.allkeysoff", InstrumentCopy.ALL_KEYS_OFF, SettingsPage.Instrument, InstrumentCopy.ACTIONS, listOf("panic", "stuck notes", "silence", "stop"))

    // ---- INSTRUMENTS › Keyboard ---------------------------------------------------------------------
    val CHOOSE_KEYBOARD = AppRow("keyboard.choose", InstrumentCopy.CHOOSE_KEYBOARD, SettingsPage.Keyboard, null, listOf("MIDI keyboard", "controller", "USB", "play live"))
    val FORGET_KEYBOARD = AppRow("keyboard.forget", "Forget the keyboard", SettingsPage.Keyboard, InstrumentCopy.THIS_KEYBOARD, listOf("remove", "unpair"))

    // ---- THE PIANO › Firmware and status (drawn by the page, beside the table's rows) -------------------
    val CHECK_PIANO_UPDATES = AppRow("firmware.check", FirmwareCopy.CHECK, SettingsPage.Firmware, "Firmware", listOf("firmware update", "upgrade"))

    // ---- PLAYING › Playback -------------------------------------------------------------------------
    val PAUSE = AppRow("playback.pause", "Pause before each piece", SettingsPage.Playback, null, listOf("gap between pieces", "pre-roll", "wait"))
    val DEFAULT_TEMPO = AppRow("playback.tempo", "Default tempo", SettingsPage.Playback, null, listOf("speed", "faster", "slower"))
    val TRANSPOSE = AppRow("playback.transpose", "Transpose", SettingsPage.Playback, null, listOf("key", "semitone", "pitch"))
    val VELOCITY = AppRow("playback.velocity", "Velocity", SettingsPage.Playback, null, listOf("loudness", "louder", "softer", "dynamics"))
    val DYNAMIC_RANGE = AppRow("playback.range", "Dynamic range", SettingsPage.Playback, null, listOf("dynamics", "soft notes", "loud and soft", "contrast"))
    val QUIETEST_NOTE = AppRow("playback.floor", "Quietest note", SettingsPage.Playback, null, listOf("soft notes", "dynamics", "velocity floor", "minimum"))
    val EXPRESSION = AppRow("playback.expression", "Expression", SettingsPage.Playback, null, listOf("humanize", "humanise", "dynamics", "phrasing", "musical"))
    val RESTRIKE = AppRow("playback.restrike", "Re-strike time", SettingsPage.Playback, null, listOf("repeat", "repeated notes", "re-strike", "trill", "tremolo"))
    val FOLD = AppRow("playback.fold", "Fold notes outside C1–B7", SettingsPage.Playback, null, listOf("range", "octave", "low notes", "high notes"))
    val SKIP_DRUMS = AppRow("playback.drums", "Skip drum channel", SettingsPage.Playback, null, listOf("percussion", "channel 10"))

    // ---- PLAYING › Tablet sound ---------------------------------------------------------------------
    val TABLET_SOUND = AppRow("tablet.mode", TabletSoundCopy.CHOICE, SettingsPage.TabletSound, null, listOf("speaker", "audio", "silent"))
    val TABLET_VOLUME = AppRow("tablet.volume", TabletSoundCopy.VOLUME, SettingsPage.TabletSound, null, listOf("speaker volume", "loudness"))
    val SOUND_FILE = AppRow("tablet.soundfont", TabletSoundCopy.title(), SettingsPage.TabletSound, null, listOf("SoundFont", "download", "samples"))

    // ---- PLAYING › Schedule -------------------------------------------------------------------------
    val ADD_SCHEDULE = AppRow("schedule.add", "Add schedule", SettingsPage.Schedule, null, listOf("timer", "alarm", "play at a time", "schedule volume"))
    val EXACT_ALARMS = AppRow("schedule.alarms", "Allow exact alarms", SettingsPage.Schedule, null, listOf("alarms and reminders"))

    // ---- SHARING › Web panel ------------------------------------------------------------------------
    const val PANEL = "Panel"
    const val OVER_THE_INTERNET = "Over the internet"
    val WEB_PANEL = AppRow("web.switch", "Web panel", SettingsPage.Remote, PANEL, listOf("remote control", "web control", "browser", "phone"))
    val WEB_ADDRESS = AppRow("web.address", "Panel address", SettingsPage.Remote, PANEL, listOf("QR code", "link", "URL", "Tailscale"))
    val WEB_PIN = AppRow("web.pin", "Web panel PIN", SettingsPage.Remote, PANEL, listOf("set a PIN", "change PIN", "password"))
    val ALSO_ON_WIFI = AppRow("web.wifi", "Also on Wi-Fi", SettingsPage.Remote, PANEL, listOf("local network", "LAN", "panel on Wi-Fi too"))
    val OVER_INTERNET = AppRow("web.internet", "Web panel over the internet", SettingsPage.Remote, OVER_THE_INTERNET, listOf("cloud", "remote access", "relay", "anywhere"))
    val RELAY_ADDRESS = AppRow("web.relay", "Relay address", SettingsPage.Remote, OVER_THE_INTERNET, listOf("cloud address", "host"))
    val ENROL = AppRow("web.enrol", "Enrol with code", SettingsPage.Remote, OVER_THE_INTERNET, listOf("enroll", "console", "cloud"))
    val FORGET_CLOUD = AppRow("web.forget", "Forget this cloud", SettingsPage.Remote, OVER_THE_INTERNET, listOf("unenrol", "relay"))

    // ---- SHARING › Guests ---------------------------------------------------------------------------
    val GUESTS_CAN_REQUEST = AppRow("guests.request", "Guests can request", SettingsPage.Guests, null, listOf("requests", "visitors", "ask the piano"))
    val APPROVE_FIRST = AppRow("guests.approve", "Approve requests first", SettingsPage.Guests, null, listOf("approval", "moderate"))
    val POSTER = AppRow("guests.poster", "Print the request poster", SettingsPage.Guests, null, listOf("QR code", "print", "sign"))

    // ---- THIS TABLET › Display ----------------------------------------------------------------------
    const val APPEARANCE_SECTION = "Appearance"
    const val RESTING_SECTION = "Resting screen"
    val APPEARANCE = AppRow("display.appearance", "Appearance", SettingsPage.Display, APPEARANCE_SECTION, listOf("dark mode", "light mode", "theme", "night"))
    val MONOCHROME = AppRow("display.mono", "Artwork in black and white", SettingsPage.Display, APPEARANCE_SECTION, listOf("monochrome", "greyscale", "grayscale", "colour", "color"))
    val ALBUM_BACKDROP = AppRow("display.backdrop", "Album colours behind the player", SettingsPage.Display, APPEARANCE_SECTION, listOf("backdrop", "colours", "album", "Apple Music"))
    val RESTING = AppRow("display.resting", "Resting screen after a minute", SettingsPage.Display, RESTING_SECTION, listOf("display mode", "standby", "screensaver", "idle"))
    val RESTING_BACKGROUND = AppRow("display.canvas", "Background", SettingsPage.Display, RESTING_SECTION, listOf("canvas", "black", "standby canvas"))
    val RESTING_SHOWS = AppRow("display.shows", "What it shows", SettingsPage.Display, RESTING_SECTION, listOf("paper roll", "art and notes", "standby shows"))

    // ---- THIS TABLET › Kiosk ------------------------------------------------------------------------
    val KIOSK_MODE = AppRow("kiosk.mode", "Kiosk mode", SettingsPage.Kiosk, null, listOf("lock", "lockdown", "device owner", "public"))
    val KIOSK_UNLOCK = AppRow("kiosk.unlock", "Unlock for now", SettingsPage.Kiosk, null, listOf("leave kiosk", "lock again"))
    val KIOSK_PIN = AppRow("kiosk.pin", "Kiosk PIN", SettingsPage.Kiosk, null, listOf("set a PIN", "change PIN", "password"))

    // ---- THIS TABLET › Updates ----------------------------------------------------------------------
    val CHECK_AUTOMATICALLY = AppRow("updates.auto", "Check for updates automatically", SettingsPage.Updates, null, listOf("auto-update"))
    val CHECK_FOR_APP_UPDATES = AppRow("updates.check", "Check for app updates", SettingsPage.Updates, null, listOf("check now", "new version", "upgrade"))

    // ---- THIS TABLET › Library and artwork ----------------------------------------------------------
    val FETCH_AUTOMATICALLY = AppRow("artwork.auto", "Fetch artwork automatically", SettingsPage.Artwork, null, listOf("Wikipedia", "portraits", "pictures", "covers"))
    val ALBUM_COVERS = AppRow("artwork.covers", "Album covers", SettingsPage.Artwork, null, listOf("album", "covers", "Apple"))
    val FETCH_EVERY_COMPOSER = AppRow("artwork.every", "Fetch artwork for every composer", SettingsPage.Artwork, null, listOf("Wikipedia", "portraits", "composers"))
    val STEVENS_LIBRARY = AppRow("artwork.library", "Steven's library", SettingsPage.Artwork, null, listOf("load", "update the library", "MAESTRO", "pieces"))

    // ---- THIS TABLET › Help and about ---------------------------------------------------------------
    val DIAGNOSTICS = AppRow("help.diagnostics", "Share diagnostics", SettingsPage.Help, null, listOf("logs", "bug", "report", "crash", "support"))
    val ABOUT = AppRow("help.about", "About", SettingsPage.Help, null, listOf("version", "credits", "licences", "licenses", "made by"))

    /** Every row, page by page in the hub's order. */
    val all: List<AppRow> = listOf(
        CONNECTION, AUTO_CONNECT, STEVEN_PIANO, ANOTHER_PIANO, ALL_KEYS_OFF,
        CHOOSE_KEYBOARD, FORGET_KEYBOARD,
        CHECK_PIANO_UPDATES,
        PAUSE, DEFAULT_TEMPO, TRANSPOSE, VELOCITY, DYNAMIC_RANGE, QUIETEST_NOTE, EXPRESSION, RESTRIKE, FOLD, SKIP_DRUMS,
        TABLET_SOUND, TABLET_VOLUME, SOUND_FILE,
        ADD_SCHEDULE, EXACT_ALARMS,
        WEB_PANEL, WEB_ADDRESS, WEB_PIN, ALSO_ON_WIFI, OVER_INTERNET, RELAY_ADDRESS, ENROL, FORGET_CLOUD,
        GUESTS_CAN_REQUEST, APPROVE_FIRST, POSTER,
        APPEARANCE, MONOCHROME, ALBUM_BACKDROP, RESTING, RESTING_BACKGROUND, RESTING_SHOWS,
        KIOSK_MODE, KIOSK_UNLOCK, KIOSK_PIN,
        CHECK_AUTOMATICALLY, CHECK_FOR_APP_UPDATES,
        FETCH_AUTOMATICALLY, ALBUM_COVERS, FETCH_EVERY_COMPOSER, STEVENS_LIBRARY,
        DIAGNOSTICS, ABOUT,
    )
}
