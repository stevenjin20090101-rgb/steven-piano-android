// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The plug's commands over its Bluetooth (v1.26 — M60; the SwitchBot Plug Mini's since v1.36 — M72, [SwitchBot]): one
 * command in flight, [CALL_TIMEOUT_MS] for each reply, to the paired plug ([address]) or the one being paired (`to`). The
 * connection is made on the first call and let go [IDLE_MS] after the last (v1.39 — M76: always short, rail up or not,
 * connect, command, drop within about 2 s, so the plug's adverts, the power watches' readings, keep flowing; before it the
 * connection was held all the while the rail was wanted), and made again after a failure: a command with no reply drops it, so the next starts on a fresh one. Each new
 * connection to the paired plug is told to [connected] (the keeper CUTs then unless something wants the rail). Any thread.
 */
class PlugClient(
    private val transport: PlugTransport,
    private val scope: CoroutineScope,
    /** The paired plug's address; null with none. */
    private val address: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    /** A new connection to the paired plug was made (v1.36 — M72), called inside the call: post what it does. */
    private val connected: () -> Unit = {},
) {
    private val calls = Mutex()
    private var connectedTo: String? = null
    private var answered: Boolean? = null

    private var idle: Job? = null

    /** The transport, for the Pair screen's scan and the advert watch. */
    val bluetooth: PlugTransport get() = transport

    /** ARM ([on]) or CUT to the plug at [to] (the paired one by default): true when it answered with its switch so. */
    suspend fun switch(on: Boolean, to: String? = null): Boolean = ask(SwitchBot.switch(on), to) == on

    /** The switch as the plug at [to] says it (true: on); null when it couldn't be asked or didn't answer. */
    suspend fun state(to: String? = null): Boolean? = ask(SwitchBot.status(), to)

    /** [frame] to the plug at [to] under the one lock: the switch its reply says, or null. */
    private suspend fun ask(frame: ByteArray, to: String?): Boolean? = withContext(io) {
        calls.withLock {
            val target = to ?: address() ?: return@withLock null
            val switch = exchange(target, frame)
            answered = switch != null
            scheduleIdle()
            switch
        }
    }

    private suspend fun exchange(target: String, frame: ByteArray): Boolean? {
        if (!transport.connected || connectedTo != target) {
            connectedTo = null
            val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { transport.connect(target) } == true
            if (!up) {
                transport.disconnect()
                if (answered != false) log("Plug: no connection to $target")   // once, not at every look while away
                return null
            }
            connectedTo = target
            if (SwitchBot.same(target, address())) connected()
        }
        val bytes = try {
            transport.exchange(frame, CALL_TIMEOUT_MS)
        } catch (e: CancellationException) {
            // Cut short (the keeper's command took the routine's place): its reply may still come, and would be read as the
            // next command's (the SwitchBot's replies carry no id), so the next starts on a fresh connection.
            transport.disconnect()
            connectedTo = null
            throw e
        }
        val switch = SwitchBot.reply(bytes)
        if (switch == null) {
            // No reply, or one the plug didn't take: the next call starts on a fresh connection.
            transport.disconnect()
            connectedTo = null
            if (answered != false) log("Plug: " + if (bytes == null) "no reply" else "a reply it didn't take (${hex(bytes)})")
        }
        return switch
    }

    /** The connection goes [IDLE_MS] after the last call (a call meanwhile starts the wait again). */
    private fun scheduleIdle() {
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            calls.withLock {
                transport.disconnect()
                connectedTo = null
            }
        }
    }

    /** Lets go of the plug now (it was forgotten, or the Pair screen is done with it). */
    suspend fun drop() = withContext(io) {
        calls.withLock {
            transport.disconnect()
            connectedTo = null
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 0xFF) }

    companion object {
        const val CALL_TIMEOUT_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        /** v1.39 — M76: a second after the last call (30 s, and held while the rail was wanted, before). */
        const val IDLE_MS = 1_000L
    }
}

/**
 * The rail's plug over [client] ([RailPlug]; v1.36 — M72), the SwitchBot Plug Mini: ARM is its switch on (the seconds
 * ignored: it has no timer of its own; each renewal still sends it, which is harmless and proves the link), CUT its switch
 * off, the status its switch as it says over GATT with the draw from its latest advert ([PlugAdverts.watts]: under 3 s old,
 * else unread), and nothing to put back.
 */
class SwitchBotRail(private val client: PlugClient, private val adverts: PlugAdverts) : RailPlug {
    override suspend fun arm(seconds: Int): Boolean = client.switch(on = true)

    override suspend fun cut(): Boolean = client.switch(on = false)

    override suspend fun status(): PlugStatus? {
        val on = client.state() ?: return null
        return PlugStatus(on, timerS = null, watts = adverts.watts())
    }

    /** v1.39 — M76: the latest advert's draw and when it came, no connection made. */
    override suspend fun reading(): PlugReading? = adverts.reading()

    /** The SwitchBot keeps no settings of the tablet's: nothing differs, nothing to say (the contract's false). */
    override suspend fun putBack(): Boolean? = false
}
