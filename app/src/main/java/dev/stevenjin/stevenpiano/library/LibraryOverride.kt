// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.library

import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import dev.stevenjin.stevenpiano.update.UpdateSource

/**
 * The emulator only, as `UpdateOverride` and `ModelsOverride` (v1.10 — M27): `adb shell setprop
 * debug.stevenpiano.libraryurl http://10.0.2.2:8767/library.json` points the library pack's manifest at
 * a server on the Mac, before the app starts, so a pack can be tried before its manifest is on `main`,
 * and a newer one can be offered without publishing it. The pack's zip may then be on that origin, or
 * the published one on GitHub ([UpdateSource.localLibrary]); it must still match the manifest's size and
 * SHA-256. Inert on a phone or tablet, and in every release build.
 */
object LibraryOverride {
    const val PROPERTY = "debug.stevenpiano.libraryurl"

    /** The local source the property names, or null (unset, not an emulator, or a release build). */
    fun source(): UpdateSource? {
        if (!LoggingPianoLink.isWanted()) return null
        val value = runCatching {
            ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        return if (value.isEmpty()) null else UpdateSource.localLibrary(value)
    }
}
