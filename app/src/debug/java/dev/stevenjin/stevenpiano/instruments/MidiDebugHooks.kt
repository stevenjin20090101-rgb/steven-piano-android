// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

/**
 * Where the debug build's test MIDI device (app/src/debug, `TestMidiDeviceService`, v1.11 — M29) says how
 * bytes reach its output port while Android has it bound. Release builds have no such device: nothing ever
 * sets this, and the activity reads it only on an emulator in a debug build.
 */
object MidiDebugHooks {
    @Volatile
    var testDeviceOutput: ((ByteArray) -> Unit)? = null
}
