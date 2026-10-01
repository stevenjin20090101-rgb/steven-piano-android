// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

/** What the MIDI picker is choosing (v1.11 — M29): a keyboard to play from, or an instrument to play on. */
enum class MidiPurpose { Keyboard, Instrument }

/**
 * The MIDI picker's rows (v1.11 — M29), pure: the devices Android lists that can serve [MidiPurpose] (a
 * keyboard has a port the app hears, an instrument one it sends to), USB ones first (and, in debug builds,
 * test ports), then Bluetooth ones, those open first and then the search's finds in the order found, each
 * address once. Never Steven Piano: no device named so, none at the remembered piano's address, none
 * without a name.
 */
object MidiPicker {
    fun rows(purpose: MidiPurpose, listed: List<MidiDeviceRef>, found: List<FoundMidi>, pianoAddress: String?): List<MidiChoice> {
        val piano = pianoAddress?.trim()?.uppercase()
        fun allowed(name: String, address: String?): Boolean =
            name.isNotBlank() && !MidiDevices.isPianoName(name) && (address == null || address.trim().uppercase() != piano)

        val serves = listed.filter { if (purpose == MidiPurpose.Keyboard) it.canBeKeyboard else it.canBeInstrument }
        val cabled = serves.filter { it.transport != MidiTransport.BLUETOOTH && allowed(it.name, null) }
            .sortedBy { if (it.transport == MidiTransport.USB) 0 else 1 }
            .map(MidiChoice::of)
        val open = serves.filter { it.transport == MidiTransport.BLUETOOTH && allowed(it.name, it.address) }.map(MidiChoice::of)
        val seen = open.mapNotNullTo(HashSet()) { it.address?.uppercase() }
        val nearby = found.filter { allowed(it.name, it.address) && seen.add(it.address.uppercase()) }.map(MidiChoice::bluetooth)
        return cabled + open + nearby
    }
}
