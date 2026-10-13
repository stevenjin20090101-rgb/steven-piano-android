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
 * The panel's sessions (v1.24 — M56: a safer sign-in). A session is a 32-byte random token from [random] ([open]), sent back as
 * the `sp_session` cookie; only its SHA-256 is kept here, so the table itself holds no usable token. Each session:
 *
 * - **lasts [VALID_MS], 90 days, from its sign-in**, however often it is used ([isValid]). After that, for as long as the
 *   browser keeps its cookie (a year from the sign-in, [KNOWN_MS]), it only marks a **known device** ([knownDevice]): it
 *   authorises nothing, but its next sign-in skips the shared lockout (`WebServer.login`).
 * - **is bound to its [Transport]**, the way it was opened: the tablet's own address (Tailscale, or the debug emulator's
 *   loopback) or the relay. It is never good on the other, so a cookie taken on one way in opens nothing on the other. A row
 *   kept before 1.24 is unbound, and binds to the first transport it is used on: a browser sends a cookie only to the origin
 *   that set it.
 * - may **confirm it's you** ([confirm]) for [CONFIRM_MS], five minutes, in memory, before the panel starts the microphone or
 *   an update (signing in counts as a confirm); the [CONFIRM_TRIES]th wrong PIN in a row there ends it ([wrongConfirm]).
 *
 * At most [max] rows are kept (32: rows outlive their sign-in now), the least recently used going first, each forgotten a year
 * after its sign-in. Since 1.15 (M42) the table outlives the app: it is read from [store] once, here (a table kept before 1.24
 * is written again at once in the new form), and given back to it whole when a session opens, binds or ends, and when a use
 * moves a session's last-used time more than [SAVE_AFTER_MS] from what the store holds (so a page's requests do not write it
 * each time). Thread-safe: the server's request threads share it. Blocking while it saves.
 */
class Sessions(
    private val store: SessionStore = SessionStore.NONE,
    private val max: Int = MAX_SESSIONS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** How a request reached the panel (v1.24 — M56): the tablet's own address (Tailscale; the debug emulator's loopback), or the relay. */
    enum class Transport(val key: String) {
        TAILNET("tailnet"),
        RELAY("relay"),
    }

    /** One session: its [transport] (null until first used, for a row kept before 1.24), its sign-in, its last use; and, in memory only, its confirmation. */
    private class Row(var transport: Transport?, val signedIn: Long, var used: Long) {
        var confirmedUntil = 0L
        var wrongConfirms = 0

        fun record() = SessionRecord(transport, signedIn, used)
    }

    /** Digest of a token → its row. */
    private val rows = LinkedHashMap<String, Row>()

    /** The table as [store] last had it. */
    private var saved: Map<String, SessionRecord> = emptyMap()

    init {
        val now = clock()
        val loaded = store.load(now)
        loaded.sessions.entries
            .filter { (_, row) -> kept(row.signedIn, row.used, now) }
            .sortedBy { it.value.used }
            .takeLast(max)
            .forEach { (digest, row) -> rows[digest] = Row(row.transport, row.signedIn, row.used) }
        if (loaded.old) save() else saved = snapshot()
    }

    /**
     * A new session's token, bound to [transport] and confirmed for [CONFIRM_MS] (signing in counts as a confirm). The least
     * recently used row goes when [max] are kept.
     */
    @Synchronized
    fun open(transport: Transport): String {
        val now = clock()
        prune(now)
        while (rows.size >= max) rows.remove(rows.entries.minBy { it.value.used }.key)
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).let { URL_SAFE.encodeToString(it) }
        rows[digest(token)] = Row(transport, now, now).also { it.confirmedUntil = now + CONFIRM_MS }
        save()
        return token
    }

    /**
     * Whether [token] names a session that may act on [transport] now: signed in less than [VALID_MS] ago, and opened on
     * [transport] (a row kept before 1.24 binds to it here, and is saved at once). If so it counts as used now, saved once that
     * has moved more than [SAVE_AFTER_MS].
     */
    @Synchronized
    fun isValid(token: String?, transport: Transport): Boolean {
        val digest = digestOf(token) ?: return false
        val now = clock()
        prune(now)
        val row = rows[digest]?.takeIf { valid(it, now) } ?: return false
        val binds = row.transport == null
        if (binds) row.transport = transport else if (row.transport != transport) return false
        row.used = now
        val was = saved[digest]
        if (binds || was == null || abs(now - was.used) > SAVE_AFTER_MS) save()
        return true
    }

    /**
     * Whether [token] names a session signed in less than [VALID_MS] ago, whichever its transport: Listening's look at the
     * session it hears for (v1.23 — M55), whose start was a request on its own transport. Not a use: nothing binds or moves.
     */
    @Synchronized
    fun isValid(token: String?): Boolean {
        val digest = digestOf(token) ?: return false
        return rows[digest]?.let { valid(it, clock()) } == true
    }

    /**
     * Whether [token] marks a known device on [transport] (v1.24 — M56): a session opened on [transport] less than [KNOWN_MS]
     * ago, valid or not any more (its browser still keeps the cookie). A row kept before 1.24 binds to [transport] here.
     */
    @Synchronized
    fun knownDevice(token: String?, transport: Transport): Boolean {
        val digest = digestOf(token) ?: return false
        prune(clock())
        val row = rows[digest] ?: return false
        if (row.transport == null) {
            row.transport = transport
            save()
        }
        return row.transport == transport
    }

    /** The right PIN to confirm it's you, from [token]'s session: it may start the microphone and an update for [CONFIRM_MS]. */
    @Synchronized
    fun confirm(token: String?) {
        val row = digestOf(token)?.let { rows[it] } ?: return
        row.confirmedUntil = clock() + CONFIRM_MS
        row.wrongConfirms = 0
    }

    /** Whether [token]'s session confirmed it's you, or signed in, less than [CONFIRM_MS] ago. */
    @Synchronized
    fun confirmed(token: String?): Boolean {
        val row = digestOf(token)?.let { rows[it] } ?: return false
        val now = clock()
        return now < row.confirmedUntil && now >= row.confirmedUntil - CONFIRM_MS
    }

    /**
     * A wrong PIN to confirm it's you, from [token]'s session. True when the session has ended: this was the
     * [CONFIRM_TRIES]th in a row (a right one starts the count again), or it had ended already.
     */
    @Synchronized
    fun wrongConfirm(token: String?): Boolean {
        val digest = digestOf(token) ?: return true
        val row = rows[digest] ?: return true
        row.wrongConfirms++
        if (row.wrongConfirms < CONFIRM_TRIES) return false
        rows.remove(digest)
        save()
        return true
    }

    /** Logs [token]'s session out (its row goes: no longer a known device either). */
    @Synchronized
    fun close(token: String?) {
        val digest = digestOf(token) ?: return
        if (rows.remove(digest) != null) save()
    }

    /** Everyone out, and out of the store: the PIN changed, or the panel was turned off. */
    @Synchronized
    fun closeAll() {
        rows.clear()
        save()
    }

    /**
     * Every row not yet bound to a transport goes (v1.24 — M56, `WebPanel.retireWifiPanel`): with Panel on Wi-Fi too on before
     * 1.24, their cookies may have crossed the Wi-Fi unencrypted.
     */
    @Synchronized
    fun dropUnbound() {
        if (rows.entries.removeAll { it.value.transport == null }) save()
    }

    /** How many sessions may act now (signed in less than [VALID_MS] ago): the devices signed in. */
    @Synchronized
    fun count(): Int {
        val now = clock()
        prune(now)
        return rows.values.count { valid(it, now) }
    }

    private fun digestOf(token: String?): String? = token?.takeIf { TOKEN.matches(it) }?.let(::digest)

    private fun valid(row: Row, now: Long): Boolean = within(row.signedIn, now, VALID_MS)

    private fun prune(now: Long) {
        rows.entries.removeAll { !kept(it.value.signedIn, it.value.used, now) }
    }

    private fun save() {
        val table = snapshot()
        store.save(table)
        saved = table
    }

    private fun snapshot(): Map<String, SessionRecord> = rows.entries.associateTo(LinkedHashMap()) { it.key to it.value.record() }

    companion object {
        const val MAX_SESSIONS = 32
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** A session acts for 90 days from its sign-in, however often it is used. */
        const val VALID_MS = 90 * DAY_MS

        /** Its row marks a known device for a year from its sign-in: as long as the browser keeps the cookie ([WebCookies.SESSION_MAX_AGE_S]). */
        const val KNOWN_MS = 365 * DAY_MS

        /** "Confirm it's you" holds five minutes. */
        const val CONFIRM_MS = 5 * 60_000L

        /** The wrong PINs in a row that end a session asked to confirm it's you. */
        const val CONFIRM_TRIES = 3

        /** How far ahead of the tablet's clock a stamp may be (its clock set back) before the row is forgotten. */
        const val AHEAD_MS = DAY_MS

        /** How far a use may move a session's last-used time from what the store holds before it is saved. */
        const val SAVE_AFTER_MS = 60_000L
        const val TOKEN_BYTES = 32
        private val URL_SAFE = Base64.getUrlEncoder().withoutPadding()

        /** 32 bytes in URL-safe base64 without padding: 43 characters. */
        val TOKEN = Regex("[A-Za-z0-9_-]{43}")

        /** A token's digest, as the table keeps it and the login guard's keys name a session: SHA-256 in 64 lower-case hex digits. */
        fun digest(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

        /** Stamped [at] less than [lifeMs] before [now], and not more than [AHEAD_MS] after it. */
        private fun within(at: Long, now: Long, lifeMs: Long): Boolean = now - at < lifeMs && at - now <= AHEAD_MS

        /** A row worth keeping: signed in and used within the year (a row kept before 1.24 is signed in when it was read). */
        private fun kept(signedIn: Long, used: Long, now: Long): Boolean = within(signedIn, now, KNOWN_MS) && within(used, now, KNOWN_MS)
    }
}

/** One session as [SessionStore] keeps it (v1.24 — M56): its transport (null: not bound yet), its sign-in and its last use, epoch ms. */
data class SessionRecord(val transport: Sessions.Transport?, val signedIn: Long, val used: Long)

/** Where [Sessions] keeps its table between runs of the app (v1.15 — M42): each token's digest → its [SessionRecord]. */
interface SessionStore {
    /**
     * The table as last saved; none when nothing was saved or what was cannot be read. A table kept before 1.24 (each digest →
     * when it was last used) reads as unbound rows signed in at [now], and says so ([Loaded.old]).
     */
    fun load(now: Long): Loaded

    /** Keeps [sessions] in place of what was saved before. */
    fun save(sessions: Map<String, SessionRecord>)

    /** What [load] read: the [sessions], and whether they were kept before 1.24 ([old]: [Sessions] writes them again at once). */
    data class Loaded(val sessions: Map<String, SessionRecord>, val old: Boolean = false) {
        companion object {
            val NONE = Loaded(emptyMap())
        }
    }

    companion object {
        /** Keeps nothing: every session ends with the process. */
        val NONE: SessionStore = object : SessionStore {
            override fun load(now: Long): Loaded = Loaded.NONE

            override fun save(sessions: Map<String, SessionRecord>) = Unit
        }
    }
}

/**
 * [Sessions]' table in [file], `files/web/sessions.json` ([under]). Since 1.24 (M56): `{"v":2,"sessions":{"<SHA-256 of a token,
 * hex>":{"t":"tailnet","in":<signed in>,"used":<last used>},…}}`, epoch ms, `t` (`tailnet` or `relay`) absent while a row is
 * unbound; digests only, never a token. A table kept before 1.24, `{"<digest>": <last used>, …}`, reads as unbound rows signed
 * in when it is read ([SessionStore.load]). Written whole each time and atomically: a `.part` file, synced, then renamed over
 * the old one. A write that fails deletes the file instead, so a session that has ended never comes back at the next start
 * (everyone enters the PIN again). A file that is missing, over [MAX_BYTES] or not exactly one of those two shapes reads as
 * none. Blocking.
 */
class FileSessionStore(private val file: File) : SessionStore {
    override fun load(now: Long): SessionStore.Loaded = try {
        if (file.isFile && file.length() <= MAX_BYTES) read(file.readText(Charsets.UTF_8), now) else SessionStore.Loaded.NONE
    } catch (e: IOException) {
        SessionStore.Loaded.NONE
    }

    override fun save(sessions: Map<String, SessionRecord>) {
        val part = File(file.path + PART)
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(part).use { out ->
                out.write(write(sessions).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!part.renameTo(file)) throw IOException("The sessions' file was not replaced")
        } catch (e: IOException) {
            part.delete()
            file.delete()
        }
    }

    companion object {
        /** A table of [Sessions.MAX_SESSIONS] is about 4 KB. */
        const val MAX_BYTES = 64 * 1024L
        private const val PART = ".part"

        /** The table's form since 1.24, its `v`. */
        const val VERSION = 2
        private const val V = "v"
        private const val SESSIONS = "sessions"
        private const val TRANSPORT = "t"
        private const val SIGNED_IN = "in"
        private const val USED = "used"
        private val ROW_KEYS = setOf(SIGNED_IN, USED)
        private val BOUND_ROW_KEYS = setOf(TRANSPORT, SIGNED_IN, USED)

        /** A token's digest as [Sessions] keeps it: SHA-256 in 64 lower-case hex digits. */
        val DIGEST = Regex("[0-9a-f]{64}")

        /** The store at `files/web/sessions.json` under the app's [filesDir]. */
        fun under(filesDir: File): FileSessionStore = FileSessionStore(File(filesDir, "web/sessions.json"))

        /** [sessions] as the file holds them. */
        internal fun write(sessions: Map<String, SessionRecord>): String {
            val table = JSONObject()
            for ((digest, row) in sessions) {
                val entry = JSONObject()
                row.transport?.let { entry.put(TRANSPORT, it.key) }
                table.put(digest, entry.put(SIGNED_IN, row.signedIn).put(USED, row.used))
            }
            return JSONObject().put(V, VERSION).put(SESSIONS, table).toString()
        }

        /** The table [text] holds: one of the two forms exactly, else none at all; a table kept before 1.24 signed in at [now]. */
        internal fun read(text: String, now: Long): SessionStore.Loaded {
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return SessionStore.Loaded.NONE
            }
            if (!json.has(V)) return readOld(json, now)
            val table = json.optJSONObject(SESSIONS)
            if (json.length() != 2 || json.opt(V) != VERSION || table == null) return SessionStore.Loaded.NONE
            val sessions = LinkedHashMap<String, SessionRecord>()
            for (digest in table.keys()) {
                val entry = table.optJSONObject(digest)
                val keys = entry?.keys()?.asSequence()?.toSet()
                if (!DIGEST.matches(digest) || entry == null || (keys != ROW_KEYS && keys != BOUND_ROW_KEYS)) return SessionStore.Loaded.NONE
                val transport = when {
                    !entry.has(TRANSPORT) -> null
                    else -> Sessions.Transport.entries.firstOrNull { it.key == entry.opt(TRANSPORT) } ?: return SessionStore.Loaded.NONE
                }
                val signedIn = whole(entry.opt(SIGNED_IN)) ?: return SessionStore.Loaded.NONE
                val used = whole(entry.opt(USED)) ?: return SessionStore.Loaded.NONE
                sessions[digest] = SessionRecord(transport, signedIn, used)
            }
            return SessionStore.Loaded(sessions)
        }

        /** The table before 1.24: digests to when each was last used, read as unbound rows signed in at [now]. */
        private fun readOld(json: JSONObject, now: Long): SessionStore.Loaded {
            val sessions = LinkedHashMap<String, SessionRecord>()
            for (digest in json.keys()) {
                val used = whole(json.opt(digest))
                if (!DIGEST.matches(digest) || used == null) return SessionStore.Loaded.NONE
                sessions[digest] = SessionRecord(null, now, used)
            }
            return SessionStore.Loaded(sessions, old = true)
        }

        private fun whole(value: Any?): Long? = if (value is Int || value is Long) (value as Number).toLong() else null
    }
}

/**
 * Slows down guessing a PIN (the panel's now, the kiosk's in M20). Wrong tries are counted two
 * ways, on two different schedules:
 *
 * - **Per [key]** (the web panel's key is the client's address): after [threshold] wrong tries in a
 *   row that key must wait [firstLockMs], doubling to [maxLockMs] (30 s → 10 min). This stops one
 *   address hammering a random PIN.
 * - **Across every key together** (the **shared** gate): a far gentler ceiling that bounds a brute force
 *   spread over many addresses (a per-address lock is escaped by rotating addresses, since at most
 *   [maxKeys] are remembered). It trips only after [globalThreshold] wrong tries in a row — much higher
 *   than [threshold] — and its wait is short and shallow-capped ([globalFirstLockMs] → [globalMaxLockMs],
 *   5 s → 1 min). So one device that can reach the panel can no longer shut the gate for everyone
 *   for ten minutes at a time (audit 1.5.1, W1): the most it can impose on the owner is a minute,
 *   ridden out; a random six-digit PIN still takes on the order of a year to grind through at
 *   ~1,440 tries a day, behind the tailnet.
 *
 * A right PIN from a key clears that key's count alone (v1.24 — M56: it used to clear everyone's too, so the owner's own
 * sign-ins handed a brute force its free tries again); the shared count is forgotten after [globalQuietMs] without a wrong
 * try (an hour; a day for the relay's). A try may be weighed outside the shared gate (`shared = false`): the panel's known
 * devices and its "Confirm it's you", whose keys are a session's own. While a wait runs nothing is weighed: a try then is
 * refused without being counted, so hammering never lengthens it. At most [maxKeys] keys are remembered (the oldest
 * forgotten first). Thread-safe.
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
    private val globalQuietMs: Long = GLOBAL_QUIET_MS,
    private val maxKeys: Int = MAX_KEYS,
) {
    private class Record(var failures: Int = 0, var lockMs: Long = 0, var lockedUntil: Long = 0, var lastFailure: Long = 0)

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

    /** One try from [key], under the shared gate too ([attempt] with `shared`). */
    fun attempt(key: String, check: () -> Boolean): Attempt = attempt(key, shared = true, check)

    /**
     * One try from [key], weighed by [check] (the PIN's slow derivation) only when no wait runs,
     * and **one try at a time** across every key (audit 1.5.1, W2): a try re-reads the wait once it
     * holds the lock, so tries sent at once cannot all pass the wait before the first of them is
     * counted (they did: eight of ten, for a threshold of five), and at most one derivation runs
     * at a time, whatever the number of listeners and request threads. A wrong answer is counted
     * (for everyone too when [shared]), a right one clears the key's count.
     */
    fun attempt(key: String, shared: Boolean, check: () -> Boolean): Attempt {
        waitMs(key, shared).let { if (it > 0) return Attempt.Wait(it) }   // a locked key never queues behind a derivation
        synchronized(weighing) {
            waitMs(key, shared).let { if (it > 0) return Attempt.Wait(it) }
            if (!check()) return Attempt.Wrong(failed(key, shared))
            succeeded(key)
            return Attempt.Right
        }
    }

    /** How long [key] must still wait before a try is weighed, in milliseconds (the shared gate's wait too when [shared]); 0 when it may try now. */
    @Synchronized
    fun waitMs(key: String, shared: Boolean = true): Long {
        val now = clock()
        val own = remaining(records[key], now, maxLockMs)
        return if (shared) maxOf(own, remaining(everyone, now, globalMaxLockMs)) else own
    }

    /** A wrong PIN from [key]: counted for it, and for everyone when [shared]. Returns the wait now in force (0 when none yet). */
    @Synchronized
    fun failed(key: String, shared: Boolean = true): Long {
        val now = clock()
        val record = records.getOrPut(key) { Record() }
        while (records.size > maxKeys) records.remove(records.keys.first())
        count(record, now, threshold, firstLockMs, maxLockMs)
        if (!shared) return remaining(record, now, maxLockMs)
        if (now - everyone.lastFailure >= globalQuietMs) {   // a quiet period without a wrong try: everyone's count starts again
            everyone.failures = 0
            everyone.lockMs = 0
        }
        everyone.lastFailure = now
        count(everyone, now, globalThreshold, globalFirstLockMs, globalMaxLockMs)
        return maxOf(remaining(record, now, maxLockMs), remaining(everyone, now, globalMaxLockMs))
    }

    /** The right PIN from [key]: its count starts again (everyone's does not: [globalQuietMs] forgets that). */
    @Synchronized
    fun succeeded(key: String) {
        records.remove(key)
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

        /** How long without a wrong try before everyone's count is forgotten (v1.24 — M56): an hour, longer than any of its waits. */
        const val GLOBAL_QUIET_MS = 60 * 60_000L

        const val MAX_KEYS = 256

        /**
         * The relay's gate for everyone together (audit delta 3). The listeners' gentle one (W1: ~1,440 tries a
         * day at its one-minute cap) was chosen behind the tailnet; through the relay the whole internet can
         * spread its tries over any number of addresses, and at ~1,440 a day a random six-digit PIN falls within
         * a year about one time in two. Here: 10 wrong in a row, then a minute, doubling to an hour, so about 24
         * a day at the cap (under 1 % of the PINs in a year). Its cost is the lever W1 took away, on the relay
         * alone: someone who keeps sending wrong PINs can keep its sign-in shut for up to an hour at a time;
         * the tablet, the tailnet and every session already open are untouched, and since 1.24 (M56) so is every
         * browser signed in over the relay within the year (a known device skips this gate).
         */
        const val RELAY_GLOBAL_THRESHOLD = 10
        const val RELAY_GLOBAL_FIRST_LOCK_MS = 60_000L
        const val RELAY_GLOBAL_MAX_LOCK_MS = 60 * 60_000L

        /** The relay's count for everyone is forgotten after a day without a wrong try (v1.24 — M56). */
        const val RELAY_GLOBAL_QUIET_MS = 24 * 60 * 60_000L

        /** The guard for requests that came through the relay: the per-address gate as the listeners', the global one far lower and slower to forget. */
        fun forRelay(clock: () -> Long = System::currentTimeMillis): LoginGuard = LoginGuard(
            clock = clock,
            globalThreshold = RELAY_GLOBAL_THRESHOLD,
            globalFirstLockMs = RELAY_GLOBAL_FIRST_LOCK_MS,
            globalMaxLockMs = RELAY_GLOBAL_MAX_LOCK_MS,
            globalQuietMs = RELAY_GLOBAL_QUIET_MS,
        )
    }
}
