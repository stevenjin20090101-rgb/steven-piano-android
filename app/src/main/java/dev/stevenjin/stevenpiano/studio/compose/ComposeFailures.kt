// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

/**
 * What composing says when it can't (v1.7 — M24), thrown as a `StudioFailure` whose message is the line
 * the Studio page and the web panel show, as `StudioFailures` does for transcription.
 */
object ComposeFailures {
    const val NO_SEED = "That piece has no notes to start from. Choose another."
    const val RAN_OUT = "The tablet ran short of memory, so composing stopped. Close other apps and try again."
    const val NO_MUSIC = "The model didn't write any notes this time. Try again."
}
