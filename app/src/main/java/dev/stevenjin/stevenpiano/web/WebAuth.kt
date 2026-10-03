// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.abs

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
 * The panel's sessions: at most [max] at once, the least recently used going first, and each forgotten after [idleMs]
 * without a request. A session is a 32-byte random token from [random] ([open]), sent back as the `sp_session` cookie;
 * only its SHA-256 is kept here, so the table itself holds no usable token. Since 1.15 (M42) the table outlives the
 * app: it is read from [store] once, here, and given back to it whole when a session opens, when one or every session
 * is logged out, and when a use moves a session's last-used time more than [SAVE_AFTER_MS] from what the store holds
 * (so a page's requests do not write it each time). Thread-safe: the server's request threads share it. Blocking
 * while it saves.
 */
class Sessions(
    private val store: SessionStore = SessionStore.NONE,
    private val max: Int = MAX_SESSIONS,
    private val idleMs: Long = IDLE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Digest of a token → when it was last used, least recently used first. */
    private val lastUsed = LinkedHashMap<String, Long>(16, 0.75f, true)

    /** The table as [store] last had it. */
    private var saved: Map<String, Long> = emptyMap()

    init {
        val now = clock()
        store.load().entries.filter { fresh(it.value, now) }.sortedBy { it.value }.takeLast(max).forEach { lastUsed[it.key] = it.value }
        saved = LinkedHashMap(lastUsed)
    }

    /** A new session's token. The least recently used session goes when [max] are open. */
    @Synchronized
    fun open(): String {
        val now = clock()
        prune(now)
        while (lastUsed.size >= max) lastUsed.remove(lastUsed.keys.first())
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).let { URL_SAFE.encodeToString(it) }
        lastUsed[digest(token)] = now
        save()
        return token
    }

    /** Whether [token] names an open session; if so it counts as used now, saved once that has moved more than [SAVE_AFTER_MS]. */
    @Synchronized
    fun isValid(token: String?): Boolean {
        if (token == null || !TOKEN.matches(token)) return false
        val now = clock()
        prune(now)
        val key = digest(token)
        if (lastUsed[key] == null) return false
        lastUsed[key] = now
        val was = saved[key]
        if (was == null || abs(now - was) > SAVE_AFTER_MS) save()
        return true
    }

    /** Logs [token]'s session out. */
    @Synchronized
    fun close(token: String?) {
        if (token != null && TOKEN.matches(token) && lastUsed.remove(digest(token)) != null) save()
    }

    /** Everyone out, and out of the store: the PIN changed, or the panel was turned off. */
    @Synchronized
    fun closeAll() {
        lastUsed.clear()
        save()
    }

    /** How many sessions are open now. */
    @Synchronized
    fun count(): Int {
        prune(clock())
        return lastUsed.size
    }

    private fun prune(now: Long) {
        lastUsed.entries.removeAll { !fresh(it.value, now) }
    }

    /** Used less than [idleMs] ago, and not stamped more than [idleMs] ahead (the tablet's clock set back). */
    private fun fresh(at: Long, now: Long): Boolean = now - at < idleMs && now >= at - idleMs

    private fun save() {
        val table = LinkedHashMap(lastUsed)
        store.save(table)
        saved = table
    }

    private fun digest(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_SESSIONS = 16
        const val IDLE_MS = 365 * 24 * 60 * 60 * 1000L

        /** How far a use may move a session's last-used time from what the store holds before it is saved. */
        const val SAVE_AFTER_MS = 60_000L
        const val TOKEN_BYTES = 32
        private val URL_SAFE = Base64.getUrlEncoder().withoutPadding()

        /** 32 bytes in URL-safe base64 without padding: 43 characters. */
        val TOKEN = Regex("[A-Za-z0-9_-]{43}")
    }
}

/** Where [Sessions] keeps its table between runs of the app (v1.15 — M42): each token's digest → when it was last used (epoch ms). */
interface SessionStore {
    /** The table as last saved; none when nothing was saved or what was cannot be read. */
    fun load(): Map<String, Long>

    /** Keeps [sessions] in place of what was saved before. */
    fun save(sessions: Map<String, Long>)

    companion object {
        /** Keeps nothing: every session ends with the process. */
        val NONE: SessionStore = object : SessionStore {
            override fun load(): Map<String, Long> = emptyMap()

            override fun save(sessions: Map<String, Long>) = Unit
        }
    }
}

/**
 * [Sessions]' table in [file], `files/web/sessions.json` ([under]): `{"<SHA-256 of a token, hex>": <last used, epoch ms>,
 * …}`, digests only, never a token. Written whole each time and atomically: a `.part` file, synced, then renamed over the
 * old one. A write that fails deletes the file instead, so a session that has ended never comes back at the next start
 * (everyone enters the PIN again). A file that is missing, over [MAX_BYTES] or not exactly that shape reads as none.
 * Blocking.
 */
class FileSessionStore(private val file: File) : SessionStore {
    override fun load(): Map<String, Long> = try {
        if (file.isFile && file.length() <= MAX_BYTES) read(file.readText(Charsets.UTF_8)) else emptyMap()
    } catch (e: IOException) {
        emptyMap()
    }

    override fun save(sessions: Map<String, Long>) {
        val part = File(file.path + PART)
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(part).use { out ->
                out.write(JSONObject(sessions).toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!part.renameTo(file)) throw IOException("The sessions' file was not replaced")
        } catch (e: IOException) {
            part.delete()
            file.delete()
        }
    }

    companion object {
        /** A table of [Sessions.MAX_SESSIONS] is about 1.4 KB. */
        const val MAX_BYTES = 64 * 1024L
        private const val PART = ".part"

        /** A token's digest as [Sessions] keeps it: SHA-256 in 64 lower-case hex digits. */
        val DIGEST = Regex("[0-9a-f]{64}")

        /** The store at `files/web/sessions.json` under the app's [filesDir]. */
        fun under(filesDir: File): FileSessionStore = FileSessionStore(File(filesDir, "web/sessions.json"))

        /** The table [text] holds: a JSON object of digests to whole numbers, else none at all. */
        internal fun read(text: String): Map<String, Long> {
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return emptyMap()
            }
            val sessions = LinkedHashMap<String, Long>()
            for (digest in json.keys()) {
                val lastUsed = json.opt(digest)
                if (!DIGEST.matches(digest) || (lastUsed !is Int && lastUsed !is Long)) return emptyMap()
                sessions[digest] = (lastUsed as Number).toLong()
            }
            return sessions
        }
    }
}

/**
 * Slows down guessing a PIN (the panel's now, the kiosk's in M20). Wrong tries are counted two
 * ways, on two different schedules:
 *
 * - **Per [key]** (the web panel's key is the client's address): after [threshold] wrong tries in a
 *   row that key must wait [firstLockMs], doubling to [maxLockMs] (30 s → 10 min). This stops one
 *   address hammering a random PIN.
 * - **Across every key together**: a far gentler ceiling that bounds a brute force spread over many
 *   addresses (a per-address lock is escaped by rotating addresses, since at most [maxKeys] are
 *   remembered). It trips only after [globalThreshold] wrong tries in a row — much higher than
 *   [threshold] — and its wait is short and shallow-capped ([globalFirstLockMs] → [globalMaxLockMs],
 *   5 s → 1 min). So one device that can reach the panel can no longer shut the gate for everyone
 *   for ten minutes at a time (audit 1.5.1, W1): the most it can impose on the owner is a minute,
 *   ridden out; a random six-digit PIN still takes on the order of a year to grind through at
 *   ~1,440 tries a day, behind the tailnet.
 *
 * A right PIN from a key clears that key's count and the global one. While a wait runs nothing is
 * weighed: a try then is refused without being counted, so hammering never lengthens it. At most
 * [maxKeys] keys are remembered (the oldest forgotten first). Thread-safe.
 *
 * The relay's requests (v1.10, audit delta 3) are weighed by a guard of their own, [forRelay]: the
 * internet reaches that gate, not only the tailnet, so its ceiling for everyone is far lower (10 wrong in
 * a row, then 1 min doubling to an hour: some 24 tries a day, where the listeners' gate allows ~1,440),
 * and an internet caller can no longer close the listeners' gate (it has its own).
 */
class LoginGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val threshold: Int = THRESHOLD,
    private val firstLockMs: Long = FIRST_LOCK_MS,
    private val maxLockMs: Long = MAX_LOCK_MS,
    private val globalThreshold: Int = GLOBAL_THRESHOLD,
    private val globalFirstLockMs: Long = GLOBAL_FIRST_LOCK_MS,
    private val globalMaxLockMs: Long = GLOBAL_MAX_LOCK_MS,
    private val maxKeys: Int = MAX_KEYS,
) {
    private class Record(var failures: Int = 0, var lockMs: Long = 0, var lockedUntil: Long = 0)

    /** How one try went: refused uncounted while a wait runs ([Wait]), weighed and wrong ([Wrong], with the wait now in force), or right. */
    sealed interface Attempt {
        data class Wait(val ms: Long) : Attempt

        data class Wrong(val waitMs: Long) : Attempt

        data object Right : Attempt
    }

    private val records = LinkedHashMap<String, Record>(16, 0.75f, true)
    private val everyone = Record()

    /** Held while a try is weighed: one at a time, whichever key sent it. */
    private val weighing = Any()

    /**
     * One try from [key], weighed by [check] (the PIN's slow derivation) only when no wait runs,
     * and **one try at a time** across every key (audit 1.5.1, W2): a try re-reads the wait once it
     * holds the lock, so tries sent at once cannot all pass the wait before the first of them is
     * counted (they did: eight of ten, for a threshold of five), and at most one derivation runs
     * at a time, whatever the number of listeners and request threads. A wrong answer is counted,
     * a right one clears the key and everyone's count.
     */
    fun attempt(key: String, check: () -> Boolean): Attempt {
        waitMs(key).let { if (it > 0) return Attempt.Wait(it) }   // a locked key never queues behind a derivation
        synchronized(weighing) {
            waitMs(key).let { if (it > 0) return Attempt.Wait(it) }
            if (!check()) return Attempt.Wrong(failed(key))
            succeeded(key)
            return Attempt.Right
        }
    }

    /** How long [key] must still wait before a try is weighed, in milliseconds; 0 when it may try now. */
    @Synchronized
    fun waitMs(key: String): Long {
        val now = clock()
        return maxOf(remaining(records[key], now, maxLockMs), remaining(everyone, now, globalMaxLockMs))
    }

    /** A wrong PIN from [key]: counted for it and for everyone. Returns the wait now in force (0 when none yet). */
    @Synchronized
    fun failed(key: String): Long {
        val now = clock()
        val record = records.getOrPut(key) { Record() }
        while (records.size > maxKeys) records.remove(records.keys.first())
        count(record, now, threshold, firstLockMs, maxLockMs)
        count(everyone, now, globalThreshold, globalFirstLockMs, globalMaxLockMs)
        return maxOf(remaining(record, now, maxLockMs), remaining(everyone, now, globalMaxLockMs))
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

    private fun count(record: Record, now: Long, threshold: Int, firstLockMs: Long, maxLockMs: Long) {
        record.failures++
        if (record.failures < threshold) return
        record.lockMs = if (record.lockMs == 0L) firstLockMs else minOf(record.lockMs * 2, maxLockMs)
        record.lockedUntil = now + record.lockMs
    }

    private fun remaining(record: Record?, now: Long, cap: Long): Long = if (record == null) 0L else (record.lockedUntil - now).coerceIn(0L, cap)

    companion object {
        const val THRESHOLD = 5
        const val FIRST_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 10 * 60_000L

        /** The gate for everyone together: higher, shorter and shallower than the per-address one, so it bounds a distributed brute force without becoming a denial-of-service lever. */
        const val GLOBAL_THRESHOLD = 20
        const val GLOBAL_FIRST_LOCK_MS = 5_000L
        const val GLOBAL_MAX_LOCK_MS = 60_000L

        const val MAX_KEYS = 256

        /**
         * The relay's gate for everyone together (audit delta 3). The listeners' gentle one (W1: ~1,440 tries a
         * day at its one-minute cap) was chosen behind the tailnet; through the relay the whole internet can
         * spread its tries over any number of addresses, and at ~1,440 a day a random six-digit PIN falls within
         * a year about one time in two. Here: 10 wrong in a row, then a minute, doubling to an hour, so about 24
         * a day at the cap (under 1 % of the PINs in a year). Its cost is the lever W1 took away, on the relay
         * alone: someone who keeps sending wrong PINs can keep its sign-in shut for up to an hour at a time;
         * the tablet, the tailnet and every session already open are untouched.
         */
        const val RELAY_GLOBAL_THRESHOLD = 10
        const val RELAY_GLOBAL_FIRST_LOCK_MS = 60_000L
        const val RELAY_GLOBAL_MAX_LOCK_MS = 60 * 60_000L

        /** The guard for requests that came through the relay: the per-address gate as the listeners', the global one far lower. */
        fun forRelay(clock: () -> Long = System::currentTimeMillis): LoginGuard = LoginGuard(
            clock = clock,
            globalThreshold = RELAY_GLOBAL_THRESHOLD,
            globalFirstLockMs = RELAY_GLOBAL_FIRST_LOCK_MS,
            globalMaxLockMs = RELAY_GLOBAL_MAX_LOCK_MS,
        )
    }
}
