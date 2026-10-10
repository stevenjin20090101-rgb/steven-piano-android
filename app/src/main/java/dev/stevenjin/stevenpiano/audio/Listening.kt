// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.ui.ListenCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * What the tablet's microphone is doing (v1.23 — M55), as the banner, the System page and the panel's state say it: [on]
 * while it hears; [until], when a panel's live listening stops by itself (epoch ms; null without one); [allowed], whether
 * Listening from the panel is on and Android allows the microphone; [panel], whether a web panel hears it now (live, or
 * the key test it started), else it hears for the tablet's own key test alone.
 */
data class ListenState(val on: Boolean = false, val until: Long? = null, val allowed: Boolean = false, val panel: Boolean = false)

/** How a start went: started, or refused with a short [Refused.code] and the words that say why. */
sealed interface ListenAnswer {
    data object Started : ListenAnswer

    data class Refused(val code: String, val message: String) : ListenAnswer

    companion object {
        const val OFF = "listen-off"
        const val AWAY = "away"
        const val BUSY = "busy"
        const val MICROPHONE = "microphone"
        const val NOT_CONNECTED = "not-connected"
        const val PLAYING = "playing"
        const val QUIET = "quiet"
        const val UPDATING = "updating"
        const val TESTING = "testing"
    }
}

/** The microphone as the key test uses it ([Listening]; a stand-in in the tests). Main thread. */
interface Ears {
    /** Listening from the panel is on and Android allows the microphone. */
    fun allowed(): Boolean

    /**
     * The key test hears: [frames] gets every frame (on the microphone's thread) and the panel of the session [token], if
     * any, hears them too; [ended] if the microphone stops under it (the app left the screen, the banner's Stop).
     */
    fun tap(token: String?, frames: (MicFrame) -> Unit, ended: () -> Unit): ListenAnswer

    /** The key test is over: it hears no more. */
    fun untap()
}

/**
 * The tablet's microphone for the web panel (v1.23 — M55; DESIGN.md › v1.23 — M55), one per process in AppGraph. Two may
 * hear through it: a panel's live listening ([startLive], one at a time, by the session [token] that asked, for
 * [LIVE_MS] at most) and the key test ([tap]); the microphone hears while either does, and stops when neither does.
 *
 * - **Safeguards.** Nothing hears unless Listening from the panel is on and Android allows the microphone ([allowedNow]),
 *   and only while the app is in the foreground ([foreground]: Android forbids starting the microphone from the background);
 *   otherwise a start is refused in words. Turning the switch off, the app leaving the screen and the banner's Stop end
 *   every hearing ([stopAll]).
 * - **Where the sound goes.** Each 100 ms frame becomes one socket message ([ListenWire]) sent ([send]) only to the
 *   sockets of the sessions that hear: the live listener's and the key test's starter's, never anyone else's and never the
 *   guests'. Nothing is recorded or kept: a frame goes out and is gone. Sending happens on a thread of its own
 *   ([sender]), which drops the oldest frames rather than ever hold up the microphone.
 * - **It ends by itself**: live listening after [LIVE_MS], when its session ends (logged out, the PIN changed), or when no
 *   panel of its session has been reached for [UNHEARD_MS]; the check runs every [TICK_MS].
 *
 * Main thread ([scope]'s), but [onFrame] (the microphone's thread) and the sender's.
 */
class Listening(
    private val scope: CoroutineScope,
    private val microphone: Microphone,
    private val allowedNow: () -> Boolean,
    private val sessionValid: (String) -> Boolean,
    /** Sends a message to the open sockets of a session (its token); how many it reached. */
    private val send: (String, String) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sender: Executor = quietSender(),
    /** Where the sessions are looked up each tick (a look may write the sessions' file): off the main thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Ears {
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    /** A panel's live listening: its session, when it stops by itself, when a socket of it was last reached. */
    private class Live(val token: String, val until: Long, reached: Long) {
        @Volatile
        var reachedAt: Long = reached
    }

    /** The key test hearing: its starter's session (null from the tablet), where its frames go, what ends it. */
    private class Tap(val token: String?, val frames: (MicFrame) -> Unit, val ended: () -> Unit)

    @Volatile
    private var live: Live? = null

    @Volatile
    private var test: Tap? = null

    private var hearing = false
    private var foregroundNow = false
    private var ticker: Job? = null
    private val seq = AtomicLong()

    override fun allowed(): Boolean = allowedNow()

    /** The app came to the foreground ([on]) or left it: leaving it ends every hearing. */
    fun foreground(on: Boolean) {
        foregroundNow = on
        if (on) publish() else stopAll()
    }

    /** The switch or Android's permission may have changed: off, every hearing ends. */
    fun refresh() {
        if (!allowedNow()) stopAll() else publish()
    }

    /**
     * A panel's Listen, for the session [token]: the room live for [LIVE_MS] (again from now when that session asks again);
     * refused while listening is off, the app is not in the foreground, another session listens, or the microphone can't
     * start.
     */
    fun startLive(token: String): ListenAnswer {
        refusal()?.let { return it }
        val current = live
        if (current != null && current.token != token && sessionValid(current.token)) return ListenAnswer.Refused(ListenAnswer.BUSY, ListenCopy.BUSY)
        if (!hear()) return ListenAnswer.Refused(ListenAnswer.MICROPHONE, ListenCopy.MICROPHONE)
        val now = clock()
        live = Live(token, now + LIVE_MS, now)
        publish()
        return ListenAnswer.Started
    }

    /** A panel's Stop (any signed-in session may): live listening ends; a key test hears on. */
    fun stopLive() {
        live = null
        settle()
    }

    override fun tap(token: String?, frames: (MicFrame) -> Unit, ended: () -> Unit): ListenAnswer {
        refusal()?.let { return it }
        if (!hear()) return ListenAnswer.Refused(ListenAnswer.MICROPHONE, ListenCopy.MICROPHONE)
        test = Tap(token, frames, ended)
        publish()
        return ListenAnswer.Started
    }

    override fun untap() {
        test = null
        settle()
    }

    /** Everything that hears stops: the banner's Stop, the switch turned off, the app leaving the screen. */
    fun stopAll() {
        val tap = test
        live = null
        test = null
        settle()
        tap?.ended?.invoke()
    }

    private fun refusal(): ListenAnswer.Refused? = when {
        !allowedNow() -> ListenAnswer.Refused(ListenAnswer.OFF, ListenCopy.OFF)
        !foregroundNow -> ListenAnswer.Refused(ListenAnswer.AWAY, ListenCopy.AWAY)
        else -> null
    }

    /** The microphone hears (it may already); false when it can't start. */
    private fun hear(): Boolean {
        if (hearing) return true
        seq.set(0)
        hearing = microphone.start(::onFrame, ::onEnded)
        if (hearing) watch()
        return hearing
    }

    /** With nothing left hearing, the microphone stops; the state follows either way. */
    private fun settle() {
        if (live == null && test == null && hearing) {
            hearing = false
            microphone.stop()
            ticker?.cancel()
            ticker = null
        }
        publish()
    }

    /** The microphone stopped by itself (its thread): every hearing ends, on the main thread. */
    private fun onEnded() {
        scope.launch {
            if (!hearing) return@launch
            hearing = false
            ticker?.cancel()
            ticker = null
            val tap = test
            live = null
            test = null
            publish()
            tap?.ended?.invoke()
        }
    }

    /** The microphone's thread: the key test's ears first, then a message to each session that hears. */
    private fun onFrame(frame: MicFrame) {
        val tap = test
        tap?.frames?.invoke(frame)
        val listener = live
        val tokens = listOfNotNull(listener?.token, tap?.token).distinct()
        if (tokens.isEmpty()) return
        val text = ListenWire.audio(seq.getAndIncrement(), Mulaw.encode(frame.samples))
        sender.execute {
            for (token in tokens) {
                if (send(token, text) > 0 && listener?.token == token) listener.reachedAt = clock()
            }
        }
    }

    /** Every [TICK_MS] while the microphone hears: the switch, the time, the sessions. */
    private fun watch() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val tokens = listOfNotNull(live?.token, test?.token)
                val valid = if (tokens.isEmpty()) emptyMap() else withContext(io) { tokens.associateWith(sessionValid) }
                check(valid)
            }
        }
    }

    /** The tick's look, with whether each session that hears is still open ([valid]). */
    private fun check(valid: Map<String, Boolean>) {
        if (!allowedNow()) {
            stopAll()
            return
        }
        val now = clock()
        val listener = live
        if (listener != null && (now >= listener.until || valid[listener.token] == false || now - listener.reachedAt > UNHEARD_MS)) {
            live = null
            settle()
        }
        val tap = test
        val starter = tap?.token
        if (tap != null && starter != null && valid[starter] == false) {   // its session ended: the test goes on, unheard
            test = Tap(null, tap.frames, tap.ended)
            publish()
        }
    }

    private fun publish() {
        _state.value = ListenState(on = hearing, until = live?.until, allowed = allowedNow(), panel = live != null || test?.token != null)
    }

    companion object {
        /** Live listening stops by itself after five minutes. */
        const val LIVE_MS = 5 * 60_000L

        /** Live listening ends when no panel of its session has been reached for this long (its page closed). */
        const val UNHEARD_MS = 15_000L

        /** How often the hearings are looked at. */
        const val TICK_MS = 1_000L

        /** Frames waiting to go out at most; past that the oldest is dropped (a slow socket never holds up the microphone). */
        private const val WAITING_FRAMES = 8

        /** One thread for the sockets, let go after a while without frames; the oldest frame dropped when it falls behind. */
        fun quietSender(): Executor = ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(WAITING_FRAMES),
            { work -> Thread(work, "steven-piano-listen").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
