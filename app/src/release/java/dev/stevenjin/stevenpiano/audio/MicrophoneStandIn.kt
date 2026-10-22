// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * The microphone's stand-in in a release build: none (v1.26 — M61). The debug build's `src/debug/…/audio/` has the same API
 * and, on the emulator, a microphone that hears the keys the key test strikes; here it is always the real one.
 */
object MicrophoneStandIn {
    @Suppress("UNUSED_PARAMETER")
    fun of(real: Microphone, struck: () -> StateFlow<KeyTestState>): Microphone = real
}
