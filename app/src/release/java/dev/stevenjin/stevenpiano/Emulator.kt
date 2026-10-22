// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.content.Intent
import android.os.Handler
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.firmware.FirmwareStandIn
import dev.stevenjin.stevenpiano.instruments.MidiPorts
import dev.stevenjin.stevenpiano.power.PlugTransport
import dev.stevenjin.stevenpiano.update.UpdateSource

/**
 * The emulator's stand-ins in a release build: none (v1.24 — M57). The debug build's `src/debug/…/Emulator.kt` has the
 * same API and the stand-ins themselves; here every answer is "the real thing", so release carries none of them.
 */
object Emulator {
    /** Never: release builds have no emulator stand-ins. */
    val on: Boolean = false

    fun pianoLink(): PianoLink? = null

    fun midiPorts(android: MidiPorts, handler: Handler): MidiPorts = android

    fun firmware(): FirmwareStandIn? = null

    fun updateSource(): UpdateSource? = null

    fun modelsSource(): UpdateSource? = null

    fun librarySource(): UpdateSource? = null

    /** The 24 V power plug's real Bluetooth (v1.26 — M60). */
    fun plugTransport(): PlugTransport? = null

    fun onIntent(graph: AppGraph, intent: Intent) = Unit
}
