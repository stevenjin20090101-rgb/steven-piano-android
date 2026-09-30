// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The relay's secret as the app keeps it: sealed in the settings, opened only to a secret of the relay's form, gone with its key. */
class SealedSecretTest {
    private var kept: String? = null
    private val sealer = PlainSealer()
    private val secrets = CloudSecrets(sealer, read = { kept }, write = { kept = it })

    @Test
    fun `a secret is kept sealed and opens again`() = runBlocking {
        val secret = "A".repeat(20) + "b".repeat(23)
        assertNull("none yet", secrets.secret())
        assertTrue(secrets.keep(secret))
        assertEquals("plain:$secret", kept)
        assertEquals(secret, secrets.secret())
        val next = "Z".repeat(43)
        assertTrue("a rotation replaces it", secrets.keep(next))
        assertEquals(next, secrets.secret())
    }

    @Test
    fun `anything but a secret of the relay's form is refused, and what can't be opened reads as not enrolled`() = runBlocking {
        assertFalse(secrets.keep("short"))
        assertFalse(secrets.keep("A".repeat(42) + "!"))
        assertNull(kept)
        kept = "v1:garbage"
        assertNull("a text it didn't seal", secrets.secret())
        kept = "plain:short"
        assertNull("a secret of another form", secrets.secret())
        val vanished = CloudSecrets(object : SecretSealer {
            override fun seal(secret: String): String = "sealed"

            override fun open(sealed: String): String? = null   // the Keystore's key is gone

            override fun forget() = Unit
        }, read = { "sealed" }, write = {})
        assertNull("a vanished key: not enrolled", vanished.secret())
        assertFalse("and nothing it seals is kept, since it wouldn't open", vanished.keep("A".repeat(43)))
    }

    @Test
    fun `forgetting drops the text and the key`() = runBlocking {
        secrets.keep("A".repeat(43))
        secrets.forget()
        assertNull(kept)
        assertNull(secrets.secret())
        assertEquals(1, sealer.forgotten)
    }
}
