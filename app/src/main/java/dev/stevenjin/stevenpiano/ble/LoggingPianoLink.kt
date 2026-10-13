// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.Emulator

/**
 * Whether this is a debug build on Android's emulator ([Emulator.on]), by the name the web service still asks it
 * (`WebService`'s loopback listener). The emulator's stand-in link moved to `src/debug` as `EmulatedPianoLink` in
 * v1.24 (M57); once the web service asks [Emulator] itself, this goes.
 */
object LoggingPianoLink {
    fun isWanted(): Boolean = Emulator.on
}
