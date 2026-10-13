// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import androidx.compose.runtime.Immutable
import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.ble.Trust
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The piano's own word for its Bluetooth lock, `!lock` (firmware 2.1.0; `firmware/docs/BLE_LOCK.md` › Model). */
enum class LockWord(val wire: String) {
    /** No key stored: the piano plays for anyone, as 2.0.x did, and the first device to claim it adds the first key. */
    Open("open"),

    /** At least one key stored: a connection may only read and release until it proves one. */
    Claimed("claimed"),

    /** Claimed, with a 5-minute window in which one more device may claim it. */
    Pairing("pairing"),
    ;

    companion object {
        fun of(text: String?): LockWord? = entries.firstOrNull { it.wire == text?.trim() }
    }
}

/** Where this tablet stands with the piano's lock on the connection there is now (v1.25 — M58). */
enum class LockPhase {
    /** Connected, the settings' dump not read yet. */
    Reading,

    /** No lock: no `!lockproto` in the dump (firmware 2.0.x), or a console that never answered. Everything as before. */
    None,

    /** The claim or the proof is out (3 s at most): notes and setting writes wait. */
    Proving,

    /** Proved on this connection (`auth ok`, or claimed just now), or the piano is open, which trusts everyone. */
    Trusted,

    /** Claimed by another device, and this tablet holds no key for it: read only. */
    LockedOut,

    /** The piano refused this tablet's proof, or didn't answer within 3 s: read only. */
    NotAccepted,
    ;

    /** Settings, notes and updates may go. */
    val writable: Boolean get() = this == None || this == Trusted

    /** Only reading and releasing: no setting writes, no notes, no updates. */
    val readOnly: Boolean get() = this == LockedOut || this == NotAccepted

    /** What the gate in front of the player makes of it ([dev.stevenjin.stevenpiano.ble.LockGate]); null while not known. */
    val trust: Trust?
        get() = when (this) {
            Reading -> null
            None, Trusted -> Trust.Open
            Proving -> Trust.Pending
            LockedOut -> Trust.Refused(LinkError.LockedOut)
            NotAccepted -> Trust.Refused(LinkError.NotAccepted)
        }
}

/**
 * The piano's Bluetooth lock as this tablet sees it on this connection (v1.25 — M58): its [phase], the piano's own word
 * ([piano], `!lock`; null before it said, or without the lock), guest play's [guestSeconds] as read at [guestReadAt]
 * (elapsed ms; 0 when off), whether the tablet keeps a key for this piano ([keyStored]), the piano's own words for the
 * last lock command or refusal ([said]), and whether a lock command is out ([busy]). Not connected, it is [PianoLock.NONE].
 */
@Immutable
data class PianoLock(
    val phase: LockPhase = LockPhase.None,
    val piano: LockWord? = null,
    val guestSeconds: Long = 0,
    val guestReadAt: Long = 0,
    val keyStored: Boolean = false,
    val said: String? = null,
    val busy: Boolean = false,
) {
    /** Settings, notes and updates may go. */
    val writable: Boolean get() = phase.writable

    /** The piano has the lock at all (its dump said `!lockproto`): the Firmware page shows its rows. */
    val present: Boolean get() = phase != LockPhase.Reading && phase != LockPhase.None

    /** The rows that change the lock (another device's pairing, unlocking, guest play) work now: proved, nothing out. */
    val manageable: Boolean get() = phase == LockPhase.Trusted && !busy

    /** Guest play's whole seconds left at [nowMs] (elapsed ms), counted from the last read; 0 when off. */
    fun guestLeft(nowMs: Long): Long {
        if (guestSeconds <= 0) return 0
        return (guestSeconds - (nowMs - guestReadAt).coerceAtLeast(0) / MS_PER_S).coerceAtLeast(0)
    }

    companion object {
        /** No lock known: not connected, or no lock on this piano. */
        val NONE = PianoLock()
        private const val MS_PER_S = 1_000L
    }
}

/**
 * One connection's side of the piano's lock (v1.25 — M58; BLE_LOCK.md › Protocol and the pinned Replies), driven by the
 * settings' session ([PianoSettingsRepository]), which reads the dump and every reply and calls this on its own thread.
 *
 * - [begin], at the dump's end, decides. No `!lockproto`: [LockPhase.None], nothing changes. An open piano trusts
 *   everyone: [LockPhase.Trusted] at once, and the tablet claims it (whatever key it kept: an open piano holds none).
 *   A connection already trusted stays so. A claimed or pairing piano the tablet keeps a key for: the proof
 *   (`auth <HMAC>` over the dump's `!nonce`). A pairing piano without one: the claim. A claimed piano without one:
 *   [LockPhase.LockedOut].
 * - **The claim** (`claim <public key>`) is made only when the key store can keep a key now ([LockKeys.canKeep]), so the
 *   tablet never locks a piano to a key it can't keep; `!claim=` gives the piano's public key, K is worked out at once
 *   (an all-zero secret is refused), and `  claimed` keeps it. A claim of an open piano whose key could not be kept after
 *   all is undone with `lock forget`. Refusals as the Replies table has them: `wait 1 s` again a second later, `bad key`
 *   or `could not save` once more with a fresh key pair, `not open` (another device was first) and `already 4 devices`
 *   locked out; an open piano that wasn't claimed stays trusted and is claimed at the next connection.
 * - **The proof** goes at most once a second: `auth refused — wait 1 s` (not counted, nonce kept) again a second later;
 *   `auth refused` (a new nonce follows) is [LockPhase.NotAccepted], unless the piano's pairing window is open, which
 *   the tablet then claims (its key was stale). `auth ok`, even late, is [LockPhase.Trusted].
 * - **3 s** after the claim or the proof went out without the piano's word, [LockPhase.NotAccepted] (an open piano
 *   stays trusted). Trust is the connection's and ends with it: the next connection begins again.
 * - **The lock's commands** ([command]: `lock pairwindow`, `lock forget`, `lock guest N`) go only from a trusted
 *   connection, one at a time; their answers update the lock (`lock forgotten` also drops the kept key, so the tablet
 *   claims the piano again at its next connection), and `!lock` and `!guest` are read again after each.
 *   `refused — not authorised` means the piano no longer trusts this connection: [LockPhase.NotAccepted].
 *
 * [send] writes a console line now (the claim, the proof and the lock's commands never wait behind held writes), [ask]
 * reads a fact now; [changed] hears every change, [trusted] when writes may go (held ones first), [refused] when this
 * connection is read only.
 */
internal class LockHandshake(
    private val address: String?,
    private val keys: LockKeys,
    private val scope: CoroutineScope,
    private val crypto: CoroutineDispatcher,
    private val now: () -> Long,
    private val send: (String) -> Unit,
    private val ask: (String) -> Unit,
    private val log: (String) -> Unit,
    private val changed: (PianoLock) -> Unit,
    private val trusted: () -> Unit,
    private val refused: () -> Unit,
) {
    /** The lock as it stands now. */
    var lock: PianoLock = PianoLock(phase = LockPhase.Reading)
        private set

    private var begun = false

    /** The connection's current `!nonce`: one proof each, renewed by the piano after every attempt. */
    private var nonce: ByteArray? = null

    /** The key being proved, and the nonce its `auth` went with (kept for `wait 1 s`); null when none is out. */
    private var proving: ByteArray? = null
    private var provedWith: ByteArray? = null

    /** A key waits for `!nonce` (a dump without one). */
    private var waitingNonce = false
    private var lastAuthAt: Long? = null

    /** The claim that is out, if any; [claiming] from the moment one is decided (its key pair is made off the thread first). */
    private var claim: Claim? = null
    private var claiming = false

    /** The lock command that is out, if any. */
    private var command: String? = null
    private var deadline: Job? = null
    private var commandTimer: Job? = null
    private var retry: Job? = null

    /** One claim: its key pair, whether the piano was open, K once `!claim=` came, and how many times it was tried. */
    private class Claim(val pair: LockCrypto.KeyPair, val wasOpen: Boolean, val tries: Int) {
        var key: ByteArray? = null
    }

    /** The dump has ended ([facts] without their "!"): what the lock asks of this tablet, decided once a connection. */
    fun begin(facts: Map<String, String>) {
        if (begun) return
        begun = true
        val proto = facts[LOCKPROTO]?.trim()
        if (proto == null) return set(lock.copy(phase = LockPhase.None))   // firmware before 2.1.0: as before the lock
        if (proto != PROTOCOL) {
            log("Lock: the piano speaks lock protocol $proto, not $PROTOCOL; treated as no lock")
            return set(lock.copy(phase = LockPhase.None))
        }
        facts[LOCK]?.let { lock = lock.copy(piano = LockWord.of(it)) }
        facts[GUEST]?.let(::readGuest)
        nonce = LockCrypto.unhex(facts[NONCE]?.trim(), LockCrypto.NONCE_BYTES)
        val trustedHere = facts[AUTH]?.trim() == "1"
        scope.launch {
            val key = address?.let { keys.key(it) }
            lock = lock.copy(keyStored = key != null)
            when {
                lock.piano == LockWord.Open -> {
                    trust("the piano is open")
                    claim(wasOpen = true)
                }
                trustedHere -> trust("this connection is trusted already")
                key != null -> prove(key)
                lock.piano == LockWord.Pairing -> claim(wasOpen = false)
                else -> refuse(LockPhase.LockedOut, "claimed by another device; this tablet keeps no key for it")
            }
        }
    }

    /** The piano gave no dump (no console, a mute one, another protocol): no lock is known, everything as before. */
    fun none() {
        begun = true
        set(lock.copy(phase = LockPhase.None))
    }

    /** A fact the piano reported ([name] without its "!"): the lock's own update it. */
    fun onFact(name: String, value: String) {
        when (name) {
            LOCK -> LockWord.of(value)?.let { if (it != lock.piano) set(lock.copy(piano = it)) }
            GUEST -> readGuest(value)
            NONCE -> {
                nonce = LockCrypto.unhex(value.trim(), LockCrypto.NONCE_BYTES)
                val fresh = nonce
                if (waitingNonce && fresh != null) {
                    waitingNonce = false
                    sendAuth(fresh)
                }
            }
            AUTH -> if (begun && value.trim() == "0" && lock.phase == LockPhase.Trusted && lock.piano != LockWord.Open) {
                refuse(LockPhase.NotAccepted, "the piano no longer trusts this connection")
            }
        }
    }

    /** One of the piano's lines: true when it was the lock's own (the settings leave it alone). */
    fun onLine(line: String): Boolean {
        if (lock.phase == LockPhase.None) return false
        if (line.startsWith(CLAIM_ANSWER)) {
            onClaimAnswer(line.removePrefix(CLAIM_ANSWER).trim())
            return true
        }
        val text = line.trim()
        when {
            text == "claimed" -> onClaimed()
            text.startsWith("claim refused") -> onClaimRefused(text)
            text.startsWith("auth ok") -> onAuthOk(text)
            text.startsWith("auth refused") -> onAuthRefused(wait = text.contains(WAIT))
            text.startsWith("pairing open") -> commandDone(lock.copy(piano = LockWord.Pairing, said = null))
            text.startsWith("lock forgotten") -> onForgotten(saved = !text.contains("not saved"))
            text.startsWith("lock forget refused") -> commandDone(lock.copy(said = text))
            text.startsWith("guest play on for") -> {
                val minutes = text.removePrefix("guest play on for").trim().substringBefore(' ').toLongOrNull() ?: 0
                commandDone(lock.copy(guestSeconds = minutes * SECONDS_PER_MINUTE, guestReadAt = now(), said = null))
            }
            text == "guest play off" -> commandDone(lock.copy(guestSeconds = 0, guestReadAt = now(), said = null))
            text.startsWith("lock guest out of range") || text.startsWith("usage: lock") -> commandDone(lock.copy(said = text))
            text.startsWith("refused") && text.contains("not authorised") -> {
                val mine = command != null
                if (mine) commandDone(lock.copy(said = text))
                refuse(LockPhase.NotAccepted, "the piano refused a line: not authorised")
                return mine   // a setting the piano refused stays the settings' to show
            }
            text.startsWith("refused") && text.contains("connection closed") -> Unit   // a line of a closed connection: no one's
            else -> return false
        }
        return true
    }

    /** A lock command ([line]: `lock pairwindow`, `lock forget`, `lock guest N`): only trusted, one at a time. False when not sent. */
    fun command(line: String): Boolean {
        if (lock.phase != LockPhase.Trusted || command != null) return false
        command = line
        set(lock.copy(busy = true, said = null))
        send(line)
        commandTimer?.cancel()
        commandTimer = scope.launch {
            delay(COMMAND_TIMEOUT_MS)
            if (command == line) commandDone(lock.copy(said = NO_ANSWER))
        }
        return true
    }

    /** `!lock` and `!guest` read again (the Firmware page while it shows: the piano says nothing when they change). */
    fun refresh() {
        if (!lock.present) return
        ask(LOCK)
        ask(GUEST)
    }

    // ---- The proof ------------------------------------------------------------------------------------------------

    private fun prove(key: ByteArray) {
        proving = key
        set(lock.copy(phase = LockPhase.Proving))
        startDeadline()
        val fresh = nonce
        if (fresh == null) {
            waitingNonce = true   // a dump without !nonce: asked for
            ask(NONCE)
        } else {
            sendAuth(fresh)
        }
    }

    private fun sendAuth(with: ByteArray) {
        val key = proving ?: return
        provedWith = with
        nonce = null   // a nonce answers one attempt
        lastAuthAt = now()
        send("auth " + LockCrypto.hex(LockCrypto.authMac(key, with)))
        log("Lock: proving this tablet's key")
    }

    private fun onAuthOk(text: String) {
        val wasProving = proving != null
        proving = null
        provedWith = null
        waitingNonce = false
        retry?.cancel()
        if (text.contains("piano is open")) lock = lock.copy(piano = LockWord.Open)
        if (lock.phase != LockPhase.Trusted) trust(if (wasProving) "the piano accepted this tablet's key" else "auth ok")
        if (lock.piano == LockWord.Open && claim == null) claim(wasOpen = true)   // unlocked meanwhile: claimed again
    }

    private fun onAuthRefused(wait: Boolean) {
        if (proving == null) return
        if (wait) {   // not checked, not counted, nonce kept: again a second after the last attempt
            val again = provedWith ?: return
            retry?.cancel()
            retry = scope.launch {
                delay(sinceLast())
                if (proving != null && lock.phase == LockPhase.Proving) sendAuth(again)
            }
            return
        }
        proving = null
        provedWith = null
        if (lock.piano == LockWord.Pairing) {
            log("Lock: the piano refused the key this tablet kept; its pairing window is open, so the tablet claims it")
            claim(wasOpen = false)
            return
        }
        refuse(LockPhase.NotAccepted, "the piano refused this tablet's key")
    }

    /** How long until a second has passed since the last proof went. */
    private fun sinceLast(): Long = (ATTEMPT_GAP_MS - (now() - (lastAuthAt ?: 0L))).coerceIn(0L, ATTEMPT_GAP_MS)

    // ---- The claim ------------------------------------------------------------------------------------------------

    private fun claim(wasOpen: Boolean, tries: Int = 0) {
        if (claiming) return
        claiming = true
        if (!wasOpen) {
            set(lock.copy(phase = LockPhase.Proving))
            startDeadline()
        }
        scope.launch {
            if (address == null || !keys.canKeep()) {
                log("Lock: the tablet can't keep a key now, so it doesn't claim the piano")
                claiming = false
                if (!wasOpen) refuse(LockPhase.LockedOut, "no key could be kept")
                return@launch
            }
            val pair = withContext(crypto) { LockCrypto.newKeyPair() }
            val out = Claim(pair, wasOpen, tries)
            claim = out
            if (wasOpen) startDeadline()
            sendClaim(out)
        }
    }

    private fun sendClaim(out: Claim) {
        send("claim " + LockCrypto.hex(out.pair.public))
        log("Lock: claiming the piano for this tablet" + if (out.tries > 0) " (again)" else "")
    }

    private fun onClaimAnswer(hex: String) {
        val out = claim ?: return
        out.key = LockCrypto.unhex(hex, LockCrypto.KEY_BYTES)?.let { LockCrypto.claimKey(out.pair, it) }
    }

    private fun onClaimed() {
        val out = claim ?: return
        claim = null
        claiming = false
        deadline?.cancel()
        // One claim per window; the first claim of an open piano also ends guest play started while it was open.
        lock = lock.copy(piano = LockWord.Claimed, guestSeconds = if (out.wasOpen) 0 else lock.guestSeconds)
        val key = out.key
        scope.launch {
            val kept = key != null && address != null && keys.keep(address, key)
            set(lock.copy(keyStored = kept))
            trust(if (kept) "claimed; the tablet keeps the piano's key" else "claimed")
            if (!kept) {
                log("Lock: the tablet couldn't keep the piano's key" + if (out.wasOpen) "; the claim is undone" else "")
                if (out.wasOpen) command(FORGET) else set(lock.copy(said = KEY_NOT_KEPT))
            }
        }
    }

    private fun onClaimRefused(text: String) {
        val out = claim ?: return
        when {
            text.contains(WAIT) -> {   // the piano does one claim's work a second, whoever asks: again in a second
                retry?.cancel()
                retry = scope.launch {
                    delay(ATTEMPT_GAP_MS)
                    if (claim === out) sendClaim(out)
                }
            }
            (text.contains("bad key") || text.contains("could not save")) && out.tries == 0 -> {
                claim = null
                claiming = false
                retry?.cancel()
                retry = scope.launch {
                    delay(ATTEMPT_GAP_MS)
                    claim(out.wasOpen, tries = 1)
                }
            }
            else -> {
                claim = null
                claiming = false
                log("Lock: the piano refused the claim ($text)")
                when {
                    text.contains("not open") -> refuse(LockPhase.LockedOut, "another device claimed the piano first")
                    text.contains("already 4") -> {
                        lock = lock.copy(said = text)
                        refuse(LockPhase.LockedOut, "the piano knows 4 devices already")
                    }
                    out.wasOpen -> deadline?.cancel()   // still open, still trusted: claimed at the next connection
                    else -> refuse(LockPhase.LockedOut, text)
                }
            }
        }
    }

    // ---- The lock's commands --------------------------------------------------------------------------------------

    private fun onForgotten(saved: Boolean) {
        if (saved && address != null) scope.launch { keys.forget(address) }
        commandDone(lock.copy(piano = LockWord.Open, keyStored = lock.keyStored && !saved, guestSeconds = 0, said = null))
    }

    private fun commandDone(next: PianoLock) {
        val was = command
        command = null
        commandTimer?.cancel()
        set(next.copy(busy = false))
        if (was != null) refresh()
    }

    // ---- Phases ---------------------------------------------------------------------------------------------------

    private fun trust(why: String) {
        deadline?.cancel()
        log("Lock: trusted ($why)")
        set(lock.copy(phase = LockPhase.Trusted))
    }

    private fun refuse(phase: LockPhase, why: String) {
        deadline?.cancel()
        retry?.cancel()
        claim = null
        claiming = false
        proving = null
        provedWith = null
        waitingNonce = false
        if (lock.phase != phase) log("Lock: read only ($why)")
        set(lock.copy(phase = phase))
    }

    /** The piano's word within 3 s of the claim or the proof, or this connection is read only (an open piano stays trusted). */
    private fun startDeadline() {
        deadline?.cancel()
        deadline = scope.launch {
            delay(HOLD_MS)
            val out = claim
            if (out != null) {
                claim = null
                claiming = false
                if (out.wasOpen) {
                    log("Lock: no answer to the claim within ${HOLD_MS / MS_PER_S} s; the piano stays open")
                } else {
                    refuse(LockPhase.NotAccepted, "no answer to the claim within ${HOLD_MS / MS_PER_S} s")
                }
            } else if (proving != null || waitingNonce) {
                refuse(LockPhase.NotAccepted, "no answer within ${HOLD_MS / MS_PER_S} s")
            }
        }
    }

    private fun readGuest(value: String) {
        val seconds = value.trim().toLongOrNull() ?: return
        set(lock.copy(guestSeconds = seconds.coerceAtLeast(0), guestReadAt = now()))
    }

    /** The one way the lock changes: the session hears it, and writes go or are dropped as the phase turns. */
    private fun set(next: PianoLock) {
        val was = lock.phase
        lock = next
        changed(next)
        if (!was.writable && next.phase.writable) trusted()
        if (!was.readOnly && next.phase.readOnly) refused()
    }

    companion object {
        /** The facts of the lock, after `!trips` in the dump (without their "!"). */
        const val LOCKPROTO = "lockproto"
        const val LOCK = "lock"
        const val AUTH = "auth"
        const val NONCE = "nonce"
        const val GUEST = "guest"

        /** The lock protocol this app speaks. */
        const val PROTOCOL = "1"

        /** The lock's commands (BLE_LOCK.md › Management). */
        const val PAIR_WINDOW = "lock pairwindow"
        const val FORGET = "lock forget"
        fun guest(minutes: Int): String = "lock guest ${minutes.coerceIn(0, GUEST_MAX_MIN)}"

        /** Guest play's longest, in minutes (the piano's 1–240; 0 ends it). */
        const val GUEST_MAX_MIN = 240

        /** How long notes and setting writes wait for the piano's word (the brief's 3 s). */
        const val HOLD_MS = 3_000L

        /** At most one proof, or one claim's work, a second. */
        const val ATTEMPT_GAP_MS = 1_000L

        /** How long a lock command may go unanswered. */
        const val COMMAND_TIMEOUT_MS = 5_000L

        /** The line the piano gives its public key on (no two leading spaces: it is a fact's form). */
        const val CLAIM_ANSWER = "!claim="

        /** What [PianoLock.said] says when a lock command had no answer, and when a claimed key couldn't be kept. */
        const val NO_ANSWER = "The piano didn't answer."
        const val KEY_NOT_KEPT = "The tablet couldn't keep the piano's key."

        private const val WAIT = "wait 1 s"
        private const val SECONDS_PER_MINUTE = 60L
        private const val MS_PER_S = 1_000L
    }
}
