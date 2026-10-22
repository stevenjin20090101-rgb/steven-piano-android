// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

/** The JVM tests' sealer: the secret marked, not hidden (there is no Keystore off Android). */
class PlainSealer : SecretSealer {
    var forgotten = 0
        private set

    override fun seal(secret: String): String = PREFIX + secret

    override fun open(sealed: String): String? = sealed.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    override fun forget() {
        forgotten++
    }

    private companion object {
        const val PREFIX = "plain:"
    }
}
