// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.Emulator
import kotlinx.coroutines.flow.StateFlow

/**
 * The microphone's stand-in, debug builds only (v1.26 — M61): on Android's emulator ([Emulator.on]), which has no piano to
 * hear, a [FakeMicrophone] that hears the keys the key test strikes ([struck]: its state, asked for once it hears), so the
 * safety check and Test every key can be seen through on the emulator; on a phone or tablet the [real] one, as in release.
 */
object MicrophoneStandIn {
    fun of(real: Microphone, struck: () -> StateFlow<KeyTestState>): Microphone = if (Emulator.on) FakeMicrophone(struck) else real
}
