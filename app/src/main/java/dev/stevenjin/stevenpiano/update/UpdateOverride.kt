// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import dev.stevenjin.stevenpiano.ble.LoggingPianoLink

/**
 * The emulator only (debug builds on an emulator, as the other emulator hooks): `adb shell setprop
 * debug.stevenpiano.updateurl http://10.0.2.2:8765/latest.json` points the updater at a server on
 * the Mac instead of GitHub, before the app starts, so the whole flow can be exercised without a
 * public repository. That origin is then the only one the updater may reach, plain HTTP included
 * (the debug build's network security config allows cleartext to 10.0.2.2 and nowhere else).
 * Inert on a phone or tablet, and in every release build.
 */
object UpdateOverride {
    const val PROPERTY = "debug.stevenpiano.updateurl"

    /** The local source the property names, or null (unset, not an emulator, or a release build). */
    fun source(): UpdateSource? {
        if (!LoggingPianoLink.isWanted()) return null
        val value = runCatching {
            ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        return if (value.isEmpty()) null else UpdateSource.local(value)
    }
}

/**
 * The emulator only, as [UpdateOverride] (v1.7 — M23): `adb shell setprop debug.stevenpiano.modelsurl
 * http://10.0.2.2:8766/models.json` points Studio's model downloads at a server on the Mac, before the
 * app starts, while the repository is private. That origin is then the only one the downloads may reach
 * ([UpdateSource.localModels]); the files must still match the hashes pinned in the app. Inert on a
 * phone or tablet, and in every release build.
 */
object ModelsOverride {
    const val PROPERTY = "debug.stevenpiano.modelsurl"

    /** The local source the property names, or null (unset, not an emulator, or a release build). */
    fun source(): UpdateSource? {
        if (!LoggingPianoLink.isWanted()) return null
        val value = runCatching {
            ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        return if (value.isEmpty()) null else UpdateSource.localModels(value)
    }
}
