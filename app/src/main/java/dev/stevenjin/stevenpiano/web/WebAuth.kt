// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// The web panel's PIN and sessions (BUILD_SPEC.md › v1.5.1 — M18). PinHash and LoginGuard know
// nothing of the web (no server, no JSON): the kiosk's PIN (M20) uses them as they are.

/**
 * A PIN as it is kept: PBKDF2-HMAC-SHA256 over its six digits with a random 16-byte salt,
 * [ITERATIONS] rounds, a 32-byte key. The PIN itself is never kept, logged or sent anywhere;
 * [matches] compares in constant time. [saltText] and [hashText] are what the settings store
 * (base64), and nothing prints either ([toString] is blank).
 */
class PinHash private constructor(private val salt: ByteArray, private val hash: ByteArray) {
    /** Whether [pin] is this PIN. A malformed one never is (and costs no derivation). */
    fun matches(pin: String): Boolean = isValid(pin) && ConstantTime.equals(derive(pin, salt), hash)

    val saltText: String get() = BASE64.encodeToString(salt)
    val hashText: String get() = BASE64.encodeToString(hash)

    override fun toString(): String = "PinHash(kept)"

    companion object {
        const val DIGITS = 6
        const val ITERATIONS = 100_000
        const val SALT_BYTES = 16
        const val KEY_BYTES = 32
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private val BASE64 = Base64.getEncoder()

        /** Exactly six ASCII digits. */
        fun isValid(pin: String): Boolean = pin.length == DIGITS && pin.all { it in '0'..'9' }

        /** A new hash of [pin] (six digits) under a fresh salt from [random]. Slow on purpose: call it off the main thread. */
        fun create(pin: String, random: SecureRandom = SecureRandom()): PinHash {
            require(isValid(pin)) { "A PIN is six digits" }
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            return PinHash(salt, derive(pin, salt))
        }

        /** The hash the settings keep, or null when either part is missing or not what [create] makes. */
        fun restore(saltText: String?, hashText: String?): PinHash? {
            if (saltText == null || hashText == null) return null
            val salt = decode(saltText) ?: return null
            val hash = decode(hashText) ?: return null
            if (salt.size != SALT_BYTES || hash.size != KEY_BYTES) return null
            return PinHash(salt, hash)
        }

        private fun decode(text: String): ByteArray? = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }

        internal fun derive(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
            val chars = pin.toCharArray()
            val spec = PBEKeySpec(chars, salt, iterations, KEY_BYTES * 8)
            return try {
                SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
                chars.fill('\u0000')
            }
        }
    }
}

/** Comparisons whose time does not depend on where two values first differ. */
object ConstantTime {
    fun equals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        for (i in 0 until maxOf(a.size, b.size)) diff = diff or (a.getOrElse(i) { 0 }.toInt() xor b.getOrElse(i) { 0 }.toInt())
        return diff == 0
    }
}

/**
 * The panel's sessions, in memory only (a restart of the app logs everyone out): at most [max] at
 * once, the least recently used going first, and each forgotten after [idleMs] without a request.
 * A session is a 32-byte random token from [random] ([open]), sent back as the `sp_session`
 * cookie; only its SHA-256 is kept here, so the table itself holds no usable token. Thread-safe:
 * the server's request threads share it.
 */
class Sessions(
    private val max: Int = MAX_SESSIONS,
    private val idleMs: Long = IDLE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Digest of a token → when it was last used, least recently used first. */
    private val lastUsed = LinkedHashMap<String, Long>(16, 0.75f, true)

    /** A new session's token. The oldest session goes when [max] are open. */
    @Synchronized
    fun open(): String {
        prune()
        while (lastUsed.size >= max) lastUsed.remove(lastUsed.keys.first())
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).let { URL_SAFE.encodeToString(it) }
        lastUsed[digest(token)] = clock()
        return token
    }

    /** Whether [token] names an open session; if so it counts as used now. */
    @Synchronized
    fun isValid(token: String?): Boolean {
        if (token == null || !TOKEN.matches(token)) return false
        prune()
        val key = digest(token)
        if (lastUsed[key] == null) return false
        lastUsed[key] = clock()
        return true
    }

    /** Logs [token]'s session out. */
    @Synchronized
    fun close(token: String?) {
        if (token != null && TOKEN.matches(token)) lastUsed.remove(digest(token))
    }

    /** Everyone out: the PIN changed, or the panel was turned off. */
    @Synchronized
    fun closeAll() = lastUsed.clear()

    /** How many sessions are open now. */
    @Synchronized
    fun count(): Int {
        prune()
        return lastUsed.size
    }

    private fun prune() {
        val now = clock()
        lastUsed.entries.removeAll { now - it.value >= idleMs || now < it.value - idleMs }
    }

    private fun digest(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_SESSIONS = 10
        const val IDLE_MS = 24 * 60 * 60 * 1000L
        const val TOKEN_BYTES = 32
        private val URL_SAFE = Base64.getUrlEncoder().withoutPadding()

        /** 32 bytes in URL-safe base64 without padding: 43 characters. */
        val TOKEN = Regex("[A-Za-z0-9_-]{43}")
    }
}

/**
 * Slows down guessing a PIN (the panel's now, the kiosk's in M20). Wrong tries are counted per
 * [key] (the web panel's: the client's address) and across all keys together; after [threshold]
 * wrong tries in a row a key (or everyone) must wait [firstLockMs], and every wrong try after that
 * doubles the wait, up to [maxLockMs]. A right PIN clears its key's count and the global one.
 * While a wait runs nothing is weighed: a try then is refused without being counted, so hammering
 * never lengthens it. At most [maxKeys] keys are remembered (the oldest forgotten first). Thread-safe.
 */
class LoginGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val threshold: Int = THRESHOLD,
    private val firstLockMs: Long = FIRST_LOCK_MS,
    private val maxLockMs: Long = MAX_LOCK_MS,
    private val maxKeys: Int = MAX_KEYS,
) {
    private class Record(var failures: Int = 0, var lockMs: Long = 0, var lockedUntil: Long = 0)

    private val records = LinkedHashMap<String, Record>(16, 0.75f, true)
    private val everyone = Record()

    /** How long [key] must still wait before a try is weighed, in milliseconds; 0 when it may try now. */
    @Synchronized
    fun waitMs(key: String): Long {
        val now = clock()
        return maxOf(remaining(records[key], now), remaining(everyone, now))
    }

    /** A wrong PIN from [key]: counted for it and for everyone. Returns the wait now in force (0 when none yet). */
    @Synchronized
    fun failed(key: String): Long {
        val now = clock()
        val record = records.getOrPut(key) { Record() }
        while (records.size > maxKeys) records.remove(records.keys.first())
        count(record, now)
        count(everyone, now)
        return maxOf(remaining(record, now), remaining(everyone, now))
    }

    /** The right PIN from [key]: its count starts again, and so does everyone's. */
    @Synchronized
    fun succeeded(key: String) {
        records.remove(key)
        everyone.failures = 0
        everyone.lockMs = 0
        everyone.lockedUntil = 0
    }

    /** How many keys the guard remembers now (at most [maxKeys]). */
    @Synchronized
    fun keyCount(): Int = records.size

    private fun count(record: Record, now: Long) {
        record.failures++
        if (record.failures < threshold) return
        record.lockMs = if (record.lockMs == 0L) firstLockMs else minOf(record.lockMs * 2, maxLockMs)
        record.lockedUntil = now + record.lockMs
    }

    private fun remaining(record: Record?, now: Long): Long = if (record == null) 0L else (record.lockedUntil - now).coerceIn(0L, maxLockMs)

    companion object {
        const val THRESHOLD = 5
        const val FIRST_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 10 * 60_000L
        const val MAX_KEYS = 256
    }
}
