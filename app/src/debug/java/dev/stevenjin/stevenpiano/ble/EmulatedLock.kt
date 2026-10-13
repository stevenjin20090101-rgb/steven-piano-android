// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.piano.LockCrypto
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The piano's Bluetooth lock as firmware 2.1.0 answers it (`firmware/docs/BLE_LOCK.md` and its pinned Replies), for the
 * emulator's stand-in piano and for tests (v1.25 — M58). Up to 4 device keys; open, claimed or pairing (a 5-minute
 * window); one connection at a time (the emulated piano has one), untrusted until it proves a key, with its nonce, made
 * at its first `dump` and renewed after every attempt. `claim` runs the piano's own X25519 and K (one claim's work a
 * second, whoever asks; a successful claim closes the window, and the first claim of an open piano ends guest play);
 * `auth` checks every key (at most one attempt a second, not counted while it waits); `lock pairwindow`, `lock forget`
 * and `lock guest N` from a trusted connection. Its [facts] are the dump's, after the others. Not thread-safe.
 */
class EmulatedLock(mode: Mode = Mode.Open, private val clock: () -> Long = System::currentTimeMillis) {
    /** How the emulated piano starts: [Open] as fresh from 2.1.0, [Claimed] by another device, [Pairing] that with its window open. */
    enum class Mode(val key: String) {
        Open("open"),
        Claimed("claimed"),
        Pairing("pairing"),
        ;

        companion object {
            fun named(value: String?): Mode? = entries.firstOrNull { it.key == value?.trim() }
        }
    }

    private val keys = mutableListOf<ByteArray>()
    private var windowUntil = 0L
    private var guestUntil = 0L
    private var lastClaimAt: Long? = null

    // The connection's own.
    private var trusted = false
    private var nonce: ByteArray? = null
    private var lastAttemptAt: Long? = null

    init {
        if (mode != Mode.Open) keys += otherDevice()
        if (mode == Mode.Pairing) windowUntil = clock() + WINDOW_MS
    }

    /** At least one key: a connection may only read and release until it proves one. */
    val claimed: Boolean get() = keys.isNotEmpty()

    /** How many keys the piano keeps. */
    val keyCount: Int get() = keys.size

    /** This connection's trust: everyone is trusted while the piano is open. */
    val trustedNow: Boolean get() = !claimed || trusted

    /** Notes and the pedal from this connection play: the piano is open, the connection trusted, or guest play is on. */
    fun notesAllowed(): Boolean = trustedNow || guestActive()

    /** A new connection: untrusted, no nonce yet, no attempts. */
    fun connected() {
        trusted = false
        nonce = null
        lastAttemptAt = null
    }

    /** The lock's facts, as the dump lists them after `!trips` (the nonce made now if the connection has none). */
    fun facts(): List<Pair<String, String>> = listOf(
        "lockproto" to "1",
        "lock" to word(),
        "auth" to if (trustedNow) "1" else "0",
        "nonce" to LockCrypto.hex(nonceNow()),
        "guest" to guestSeconds().toString(),
    )

    /** One of the lock's facts ([name] without its "!"), or null for another name. */
    fun fact(name: String): String? = facts().firstOrNull { it.first == name }?.second

    /** Whether an untrusted connection may send [command]: reading, `off` and `broadcast` only (claim and auth are [handle]'s). */
    fun allows(command: String): Boolean = trustedNow || command.substringBefore(' ') in UNTRUSTED

    /** The piano's reply to a lock line (`claim`, `auth`, `lock …`), or null for any other line. */
    fun handle(command: String): List<String>? {
        val argument = command.substringAfter(' ', "").trim()
        return when (command.substringBefore(' ')) {
            "claim" -> claim(argument)
            "auth" -> auth(argument)
            "lock" -> if (trustedNow) lock(argument) else listOf(NOT_AUTHORISED)
            else -> null
        }
    }

    /** The piano's screen: press and hold a lighting button, then Let a new tablet pair. */
    fun openWindow() {
        windowUntil = clock() + WINDOW_MS
    }

    /** A device claimed it before with [key] (a test's tablet, whose key the test keeps too). */
    fun claimedBefore(key: ByteArray) {
        keys += key.copyOf()
    }

    /** Whether the piano keeps [key] (a test checks the claim's K is the tablet's). */
    fun knows(key: ByteArray): Boolean = keys.any { it.contentEquals(key) }

    private fun word(): String = when {
        !claimed -> "open"
        windowUntil > clock() -> "pairing"
        else -> "claimed"
    }

    private fun guestActive(): Boolean = guestUntil > clock()

    private fun guestSeconds(): Long = if (guestActive()) (guestUntil - clock() + MS_PER_S - 1) / MS_PER_S else 0

    private fun nonceNow(): ByteArray = nonce ?: ByteArray(LockCrypto.NONCE_BYTES).also {
        RANDOM.nextBytes(it)
        nonce = it
    }

    private fun claim(argument: String): List<String> {
        val now = clock()
        val wasOpen = !claimed
        if (!wasOpen && windowUntil <= now) return listOf("  claim refused — not open (open pairing on the piano's screen)")
        val appPublic = LockCrypto.unhex(argument, LockCrypto.KEY_BYTES) ?: return listOf(BAD_KEY)
        if (keys.size >= MAX_KEYS) return listOf("  claim refused — already 4 devices (lock forget first)")
        val last = lastClaimAt
        if (last != null && now - last < GAP_MS) return listOf("  claim refused — wait 1 s")
        lastClaimAt = now
        val piano = LockCrypto.newKeyPair()
        val shared = try {
            LockCrypto.x25519(piano.secret, appPublic)
        } catch (e: GeneralSecurityException) {
            return listOf(BAD_KEY)
        }
        if (LockCrypto.allZero(shared)) return listOf(BAD_KEY)
        keys += LockCrypto.deriveKey(shared, appPublic, piano.public)
        windowUntil = 0L   // one claim per window
        if (wasOpen) guestUntil = 0L
        trusted = true
        return listOf("!claim=" + LockCrypto.hex(piano.public), "  claimed")
    }

    private fun auth(argument: String): List<String> {
        if (!claimed) return listOf("  auth ok — piano is open")
        if (trusted) return listOf("  auth ok — already trusted")
        val now = clock()
        val last = lastAttemptAt
        if (last != null && now - last < GAP_MS) return listOf("  auth refused — wait 1 s")
        lastAttemptAt = now
        val mac = LockCrypto.unhex(argument, LockCrypto.KEY_BYTES)
        val asked = nonce
        val ok = mac != null && asked != null && keys.any { MessageDigest.isEqual(LockCrypto.authMac(it, asked), mac) }
        nonce = null   // a nonce answers one attempt; a new one is made as it is next read
        if (ok) {
            trusted = true
            return listOf("  auth ok")
        }
        return listOf("  auth refused")
    }

    private fun lock(argument: String): List<String> = when {
        argument == "pairwindow" -> {
            openWindow()
            listOf("  pairing open for 5 min")
        }
        argument == "forget" -> {
            keys.clear()
            windowUntil = 0L
            guestUntil = 0L
            listOf("  lock forgotten — piano is open")
        }
        argument.startsWith("guest") -> guest(argument.removePrefix("guest").trim())
        argument == "status" -> listOf("  bluetooth lock: ${word()}; ${keys.size} of $MAX_KEYS device keys")
        argument in USB_ONLY -> listOf("  refused over Bluetooth — use the USB console")
        else -> listOf("  usage: lock pairwindow | forget | guest <0..240> | status")
    }

    private fun guest(argument: String): List<String> {
        val minutes = argument.takeIf { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }?.toInt()
        if (minutes == null || minutes > GUEST_MAX_MIN) return listOf("  lock guest out of range (0..240)")
        if (minutes == 0) {
            guestUntil = 0L
            return listOf("  guest play off")
        }
        guestUntil = clock() + minutes * MS_PER_MINUTE
        return listOf("  guest play on for $minutes min")
    }

    companion object {
        const val NOT_AUTHORISED = "  refused — not authorised (send auth first)"
        private const val BAD_KEY = "  claim refused — bad key"
        private const val MAX_KEYS = 4
        private const val GAP_MS = 1_000L
        private const val WINDOW_MS = 5 * 60_000L
        private const val GUEST_MAX_MIN = 240
        private const val MS_PER_S = 1_000L
        private const val MS_PER_MINUTE = 60_000L
        private val RANDOM = SecureRandom()

        /** What an untrusted connection may still send (with `claim` and `auth`). */
        private val UNTRUSTED = setOf("dump", "get", "status", "help", "off", "broadcast")

        /** `lock` words only the USB console has. */
        private val USB_ONLY = setOf("open", "reset", "selftest")

        /** Another device's key: a claimed piano the tablet holds none for. */
        private fun otherDevice(): ByteArray = ByteArray(LockCrypto.KEY_BYTES).also { RANDOM.nextBytes(it) }
    }
}
