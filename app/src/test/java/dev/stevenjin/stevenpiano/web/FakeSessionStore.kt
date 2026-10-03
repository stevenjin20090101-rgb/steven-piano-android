// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

/** The sessions' store in the tests (v1.15 — M42), in memory: the table as last saved. */
class FakeSessionStore : SessionStore {
    @Volatile
    var saved: Map<String, Long> = emptyMap()
        private set

    override fun load(): Map<String, Long> = saved

    override fun save(sessions: Map<String, Long>) {
        saved = LinkedHashMap(sessions)
    }
}
