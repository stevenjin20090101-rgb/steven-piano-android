// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import dev.stevenjin.stevenpiano.Emulator
import dev.stevenjin.stevenpiano.SystemProperty

/**
 * The emulator only, as the other emulator hooks (v1.10 — M26): `adb shell setprop debug.stevenpiano.cloudurl
 * http://10.0.2.2:8787` stands a relay on the Mac (`wrangler dev` of `cloud/`) in for the one the
 * person typed, before the app starts: enrolment posts to that origin's `/api/enrol` and the relay
 * client connects to its `/tablet` over `ws://` (the debug build's network security config allows
 * plain HTTP to 10.0.2.2 and nowhere else), and the panel there is plain `http`. Inert on a phone or
 * tablet, and in every release build: those reach only `https://`/`wss://` at the typed host.
 */
object CloudOverride {
    const val PROPERTY = "debug.stevenpiano.cloudurl"

    private val ORIGIN = Regex("https?://[a-z0-9.-]+(:[0-9]{1,5})?")

    /** The stand-in origin the property names (`scheme://host:port`, nothing after it), or null (unset, not an emulator, or a release build). */
    fun origin(): String? {
        if (!Emulator.on) return null
        val value = SystemProperty.read(PROPERTY)
        return value.lowercase().trimEnd('/').takeIf { ORIGIN.matches(it) }
    }
}
