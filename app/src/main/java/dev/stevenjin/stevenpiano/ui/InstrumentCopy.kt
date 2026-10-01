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
import dev.stevenjin.stevenpiano.instruments.KeyboardState
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

    /** Under the Keys header while a keyboard is set: "Keyboard · Roland FP-30X", with its state when not connected. */
    fun keysEyebrow(state: KeyboardState): String? {
        val chosen = state.chosen ?: return null
        return if (state.connected) "Keyboard · ${chosen.name}" else "Keyboard · ${chosen.name} · ${stateWords(state.phase)}"
    }
}
