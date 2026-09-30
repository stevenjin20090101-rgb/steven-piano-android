// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the relay's bearer secret sealed (BUILD_SPEC.md › v1.10 — M26): what goes into the app's
 * DataStore is [seal]'s text, never the secret. [open] gives it back, or null when it can't be
 * opened (the key is gone, the text is not one it sealed, or was changed): the tablet then reads as
 * not enrolled. [forget] drops the key with the enrolment.
 */
interface SecretSealer {
    fun seal(secret: String): String?

    fun open(sealed: String): String?

    fun forget()
}

/**
 * The tablet's sealer: an AES-256-GCM key in the AndroidKeyStore ([ALIAS], made at the first
 * [seal], never leaving the Keystore), a fresh 12-byte IV the Keystore picks for every seal, the
 * 128-bit tag checked on [open]. The sealed text is `v1:` and the IV with the ciphertext in base64.
 * No `security-crypto`: this is all it needs. A key that has vanished (a Keystore wiped, the data
 * restored elsewhere) opens nothing, so the tablet asks to be enrolled again.
 */
class KeystoreSealer(private val alias: String = ALIAS) : SecretSealer {
    override fun seal(secret: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key() ?: create())
        val iv = cipher.iv
        if (iv.size != IV_BYTES) return null
        VERSION + Base64.getEncoder().encodeToString(iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8)))
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: RuntimeException) {
        null   // the Keystore's own failures (ProviderException) are unchecked
    }

    override fun open(sealed: String): String? {
        if (!sealed.startsWith(VERSION)) return null
        return try {
            val bytes = Base64.getDecoder().decode(sealed.substring(VERSION.length))
            if (bytes.size <= IV_BYTES + TAG_BITS / 8) return null
            val key = key() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun forget() {
        try {
            keyStore().deleteEntry(alias)
        } catch (e: GeneralSecurityException) {
            // No key to forget.
        } catch (e: RuntimeException) {
            // The Keystore is unreachable: nothing it holds can be opened without the enrolment anyway.
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun create(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ALIAS = "steven-piano-cloud"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION = "v1:"
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

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

/**
 * The relay's secret as the app keeps it: sealed ([sealer]) in the settings ([read], [write], the
 * DataStore's `cloudSecret`, read on its own like the PIN). [secret] is null unless the kept text
 * opens to a secret of the relay's form; [seal] gives a new one's sealed text (for an enrolment,
 * written with the piano's id at once) and [keep] puts a rotated one in place of the old, each
 * null or false when it can't; [forget] drops the text and the key.
 */
class CloudSecrets(
    private val sealer: SecretSealer,
    private val read: suspend () -> String?,
    private val write: suspend (String?) -> Unit,
) {
    suspend fun secret(): String? = read()?.let(sealer::open)?.takeIf(RelayProtocol.SECRET::matches)

    /** [secret] sealed, once it is of the relay's form and what was sealed opens again; null otherwise. */
    fun seal(secret: String): String? {
        if (!RelayProtocol.SECRET.matches(secret)) return null
        val sealed = sealer.seal(secret) ?: return null
        return sealed.takeIf { sealer.open(it) == secret }
    }

    suspend fun keep(secret: String): Boolean {
        val sealed = seal(secret) ?: return false
        write(sealed)
        return true
    }

    suspend fun forget() {
        write(null)
        sealer.forget()
    }
}
