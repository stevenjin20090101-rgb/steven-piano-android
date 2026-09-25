// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/** "Open with" and Share: files the app may not read never crash it; they become the Library's message. */
class SharedFilesTest {
    private val handed = mutableListOf<List<String>>()

    @Test
    fun `no files means nothing to import, and the service is not started`() {
        assertEquals(SharedFiles.Outcome.None, SharedFiles.hand(emptyList<String>()) { handed += it })
        assertTrue(handed.isEmpty())
    }

    @Test
    fun `readable files go to the import service`() {
        val files = listOf("content://files/nocturne.mid", "content://files/clair.mid")
        assertEquals(SharedFiles.Outcome.Importing, SharedFiles.hand(files) { handed += it })
        assertEquals(listOf(files), handed)
    }

    @Test
    fun `a file the sender gave no access to is unreadable, not a crash`() {
        val outcome = SharedFiles.hand(listOf("content://com.android.externalstorage.documents/document/primary%3Anocturne.mid")) {
            throw SecurityException("UID 10192 does not have permission to content://…")
        }
        assertEquals(SharedFiles.Outcome.Unreadable, outcome)
    }

    @Test
    fun `a file that is gone is unreadable too`() {
        assertEquals(SharedFiles.Outcome.Unreadable, SharedFiles.hand(listOf("content://files/gone.mid")) { throw FileNotFoundException("gone.mid") })
    }

    @Test
    fun `only content URIs are taken from another app, 500 at most`() {
        val scheme = { uri: String -> uri.substringBefore("://", "").ifEmpty { null } }
        val sent = listOf("content://files/a.mid", "file:///data/data/dev.stevenjin.stevenpiano/databases/steven-piano.db", "file:///proc/self/fd/0", "http://x/y.mid", "b.mid")
        assertEquals(listOf("content://files/a.mid"), SharedFiles.accepted(sent, scheme))
        val many = (0 until 2_000).map { "content://files/$it.mid" }
        assertEquals(many.take(500), SharedFiles.accepted(many, scheme))
    }

    @Test
    fun `anything else is a bug and still throws`() {
        assertThrows(IllegalStateException::class.java) {
            SharedFiles.hand(listOf("content://files/nocturne.mid")) { throw IllegalStateException("not allowed to start a service") }
        }
    }
}
