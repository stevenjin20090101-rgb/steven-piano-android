// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.LiveThru
import dev.stevenjin.stevenpiano.instruments.LiveTrip
import dev.stevenjin.stevenpiano.record.RecordingSession
import dev.stevenjin.stevenpiano.record.TakeEnd
import java.util.Locale
import dev.stevenjin.stevenpiano.instruments.MidiTransport

/**
 * The words of keyboards and instruments (DESIGN.md › v1.11): the Piano tab's INSTRUMENTS rows, the
 * Keyboard page, the MIDI picker, the Keys tab's eyebrow. A device's name is the device's own (cleaned when
 * it was chosen); everything around it is the app's.
 */
object InstrumentCopy {
    /** The Piano tab's group. */
    const val GROUP = "Instruments"

    const val NONE = "None"

    /** The Keyboard row's value: "None", "Roland FP-30X", or "Roland FP-30X · not connected". */
    fun keyboardValue(state: KeyboardState): String {
        val chosen = state.chosen ?: return NONE
        return if (state.connected) chosen.name else "${chosen.name} · not connected"
    }

    /** A keyboard's state in a few words, as the Keyboard page and the Keys tab's eyebrow say it. */
    fun stateWords(phase: KeyboardState.Phase): String = when (phase) {
        KeyboardState.Phase.None -> "None chosen"
        KeyboardState.Phase.NotConnected -> "Not connected"
        KeyboardState.Phase.Connecting -> "Connecting…"
        KeyboardState.Phase.Connected -> "Connected"
        KeyboardState.Phase.NeedsPairing -> "Asks to pair"
        KeyboardState.Phase.Unavailable -> "No MIDI on this tablet"
    }

    /** The Keyboard page's state line. */
    fun keyboardLine(state: KeyboardState): String {
        val chosen = state.chosen
        return when {
            state.phase == KeyboardState.Phase.Unavailable -> "This tablet has no MIDI, so it can't use a keyboard."
            chosen == null -> "No keyboard yet. Choose one to play from it."
            else -> when (state.phase) {
                KeyboardState.Phase.Connected -> "${chosen.name} is connected."
                KeyboardState.Phase.Connecting -> "Connecting to ${chosen.name}…"
                KeyboardState.Phase.NeedsPairing -> "${chosen.name} asks to pair."
                else -> if (chosen.transport == MidiTransport.BLUETOOTH) {
                    "${chosen.name} isn't connected. Switch it on near the tablet."
                } else {
                    "${chosen.name} isn't connected. Plug it into the tablet."
                }
            }
        }
    }

    /** "Bluetooth · Connected", under the chosen keyboard's name. */
    fun keyboardDetail(state: KeyboardState): String {
        val chosen = state.chosen ?: return ""
        return "${transport(chosen.transport)} · ${stateWords(state.phase)}"
    }

    /** "USB" or "Bluetooth". */
    fun transport(transport: MidiTransport): String = transport.label

    const val THIS_KEYBOARD = "This keyboard"
    const val CHOOSE_KEYBOARD = "Choose a keyboard…"
    const val FORGET = "Forget"
    const val PLAY_FROM_KEYS = "Play it from the Keys tab."
    const val CABLE_NOTE = "A cable is steadier than Bluetooth: plug the keyboard into the tablet when you can."
    const val PAIR_LINE = "This keyboard asks to pair. Accept the request, or pair it in Bluetooth settings, then choose it again."
    const val OPEN_BLUETOOTH_SETTINGS = "Open Bluetooth settings"

    // ---- The Instrument page -----------------------------------------------------------------

    const val STEVEN_PIANO = "Steven Piano"
    const val THIS_INSTRUMENT = "This instrument"
    const val CHOOSE = "Choose"
    const val ANOTHER_MIDI_PIANO = "Another MIDI piano…"
    const val ACTIONS = "Actions"
    const val ALL_KEYS_OFF = "All keys off"
    const val CONNECT = "Connect"
    const val DISCONNECT = "Disconnect"

    /** What kind of instrument it is. */
    fun kindLine(kind: InstrumentKind): String = if (kind == InstrumentKind.StevenPiano) "The school piano" else "Standard MIDI piano"

    /** The Instrument row's value: "Steven Piano", or the MIDI piano's name. */
    fun instrumentValue(kind: InstrumentKind, midiName: String?): String =
        if (kind == InstrumentKind.StevenPiano) STEVEN_PIANO else midiName?.takeIf { it.isNotBlank() } ?: "MIDI piano"

    /** The instrument's state in words, as the connection card and the Instrument page say it. */
    fun linkWords(kind: InstrumentKind, link: LinkState): String = when (link) {
        is LinkState.Connected -> "Connected"
        LinkState.Scanning -> if (kind == InstrumentKind.StevenPiano) "Looking for the piano…" else "Connecting…"
        LinkState.Connecting -> "Connecting…"
        is LinkState.Reconnecting -> "Reconnecting…"
        LinkState.Disconnected, is LinkState.Error -> "Not connected"
    }

    /** Under a MIDI piano: why the PIANO group is gone. */
    fun hiddenNote(name: String): String = "Feel, Lighting, Pedal and Firmware belong to Steven Piano and are hidden while $name plays."

    // ---- The picker -------------------------------------------------------------------------

    const val PICKER_EYEBROW = "MIDI"
    const val PICK_KEYBOARD = "Choose a keyboard"
    const val PICK_INSTRUMENT = "Choose an instrument"
    const val LOOKING = "Looking for MIDI devices…"
    const val LOOK_AGAIN = "Look again"
    const val CANCEL = "Cancel"
    const val ALLOW = "Allow"

    /** Nothing listed and nothing found. */
    const val NOTHING_FOUND = "No MIDI device found. Plug one into the tablet, or switch a Bluetooth one on nearby."

    /** Android allows few Bluetooth searches at a time (the piano's included): the picker waits [ms]. */
    fun waitLine(ms: Long): String = "Bluetooth needs a moment between searches. Looking again in ${((ms + 999) / 1000).coerceAtLeast(1)} s."

    /** Why the picker can't look for Bluetooth devices; USB ones are listed all the same. */
    fun blocked(problem: LinkError?): String = when (problem) {
        LinkError.BluetoothOff -> "Bluetooth is off. Turn it on to find Bluetooth devices; USB ones are listed here."
        LinkError.PermissionMissing -> "Allow Nearby devices to find Bluetooth MIDI devices."
        LinkError.LocationOff -> "Location is off. This tablet needs it on to find Bluetooth devices."
        else -> "This tablet can't look for Bluetooth devices; USB ones are listed here."
    }

    /** A row of the picker for TalkBack: "Roland FP-30X, Bluetooth", and ", chosen" on the current one. */
    fun pickerRow(name: String, transport: MidiTransport, current: Boolean): String =
        "$name, ${transport(transport)}" + if (current) ", chosen" else ""

    // ---- The Keys tab -----------------------------------------------------------------------

    /** The Live pill, off and on. */
    const val LIVE = "Live"
    const val LIVE_ON = "Live on"

    /** The connection line while a keyboard is connected and Live is off. */
    const val KEYBOARD_LIVE_OFF = "Keyboard connected. Live is off."

    /** Under the pills while Live plays Steven Piano. */
    const val COILS_NOTE = "The piano lets a held key go after 2 seconds to keep its coils cool."

    /** Under the pills when the keyboard is the instrument too. */
    const val LOOPED = "Live stays off: this keyboard is the instrument too, so it already plays its own keys."

    // ---- Recording ----------------------------------------------------------------------------

    /** The Record control, and what TalkBack says of it. */
    const val RECORD = "Record"
    const val RECORDING_ON = "Recording"
    const val RECORDING_OFF = "Off"

    /** The sheet after Stop. */
    const val RECORDING_EYEBROW = "Recording"
    const val KEEP_RECORDING = "Keep this recording?"
    const val TITLE_FIELD = "Title"
    const val DISCARD = "Discard"
    const val LISTEN = "Listen"
    const val KEEP = "Keep"
    const val DONE = "Done"

    /** The sheet in kiosk mode without the PIN. */
    const val KIOSK_SAVED = "Saved to Recordings. Someone with the PIN keeps or discards it."

    /** Under the pills for a moment after a take with no note. */
    const val NOTHING_PLAYED = "Nothing was played."

    /** Under the pills when the library would not take a take. */
    const val NOT_SAVED = "The recording couldn't be saved. The app tries again when it next starts."

    /** "0:42 · 318 notes" (one note: "1 note"), the length in tabular digits. */
    fun takeLine(durationMicros: Long, notes: Int): String =
        "${RecordingSession.clock(durationMicros * 1000)} · " + String.format(Locale.ROOT, "%,d", notes) + if (notes == 1) " note" else " notes"

    /** Why a take stopped by itself, under the sheet's line; null when the person stopped it. */
    fun takeEnded(ended: TakeEnd): String? = when (ended) {
        TakeEnd.Longest -> "It stopped by itself after an hour."
        TakeEnd.Fullest -> "It stopped by itself: it held as much as one recording can."
        TakeEnd.Silence -> "It stopped by itself after five minutes with nothing played."
        TakeEnd.AppLeft -> "It stopped when the app left the screen."
        TakeEnd.Stopped -> null
    }

    /** Under the pills after the flood breaker switched Live off. */
    fun tripped(trip: LiveTrip): String = "Live turned off: " + when (trip) {
        LiveTrip.TooManyNotes -> "the keyboard sent more than ${LiveThru.MAX_NOTES_PER_SECOND} notes in a second."
        LiveTrip.TooManyKeys -> "the keyboard held ${LiveThru.MAX_HELD} keys at once."
        LiveTrip.Garbled -> "the keyboard sent garbled data."
    } + " Turn it on to play again."

    /** Under the Keys header while a keyboard is set: "Keyboard · Roland FP-30X", with its state when not connected. */
    fun keysEyebrow(state: KeyboardState): String? {
        val chosen = state.chosen ?: return null
        return if (state.connected) "Keyboard · ${chosen.name}" else "Keyboard · ${chosen.name} · ${stateWords(state.phase)}"
    }
}
