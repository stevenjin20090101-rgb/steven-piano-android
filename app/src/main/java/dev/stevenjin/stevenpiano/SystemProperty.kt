// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

/**
 * A system property as `getprop` prints it (v1.24 — M57: one copy for every `debug.stevenpiano.*` switch set with
 * `adb shell setprop`), trimmed; "" when it is unset or can't be read. Read afresh at each call.
 */
object SystemProperty {
    fun read(name: String): String = runCatching {
        ProcessBuilder("getprop", name).start().inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrDefault("")
}
