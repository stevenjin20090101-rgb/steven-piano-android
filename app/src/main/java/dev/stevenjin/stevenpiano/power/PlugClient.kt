// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The plug's JSON-RPC over its Bluetooth (v1.26 — M60): one request in flight, [CALL_TIMEOUT_MS] for each answer, to the
 * paired plug ([address]) or the one being paired (`to`). The connection is made on the first call, kept while [hold] says
 * the rail is wanted, let go [IDLE_MS] after the last call otherwise, and made again after a failure: a request with no
 * answer (or another's) drops it, so the next starts on a fresh one. [status] keeps the switch's last reading. Any thread.
 */
class PlugClient(
    private val transport: PlugTransport,
    private val scope: CoroutineScope,
    /** The paired plug's address; null with none. */
    private val address: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) {
    private val calls = Mutex()
    private var nextId = 1
    private var connectedTo: String? = null

    @Volatile
    private var held = false
    private var idle: Job? = null

    private val _status = MutableStateFlow<PlugStatus?>(null)

    /** The switch as the plug last said (`Switch.GetStatus`); null before a reading. */
    val status: StateFlow<PlugStatus?> = _status.asStateFlow()

    private val _reachable = MutableStateFlow<Boolean?>(null)

    /** Whether the last call was answered; null before one. */
    val reachable: StateFlow<Boolean?> = _reachable.asStateFlow()

    /** The transport, for the Pair screen's scan and bond. */
    val bluetooth: PlugTransport get() = transport

    /** Keeps the connection up between calls while [on] (the rail is wanted); else it goes [IDLE_MS] after the last call. */
    fun hold(on: Boolean) {
        held = on
        if (on) idle?.cancel() else scheduleIdle()
    }

    /** [method] with [params] to the plug at [to] (the paired one by default); null when it couldn't be asked or didn't answer. */
    suspend fun call(method: String, params: JSONObject? = null, to: String? = null, timeoutMs: Long = CALL_TIMEOUT_MS): RpcAnswer? =
        withContext(io) {
            calls.withLock {
                val target = to ?: address() ?: return@withLock null
                val answer = exchange(target, method, params, timeoutMs)
                _reachable.value = answer != null
                scheduleIdle()
                answer
            }
        }

    private suspend fun exchange(target: String, method: String, params: JSONObject?, timeoutMs: Long): RpcAnswer? {
        if (!transport.connected || connectedTo != target) {
            connectedTo = null
            val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { transport.connect(target) } == true
            if (!up) {
                transport.disconnect()
                if (_reachable.value != false) log("Plug: no connection to $target")   // once, not at every poll while away
                return null
            }
            connectedTo = target
        }
        val id = nextId++
        val bytes = transport.exchange(ShellyRpc.request(id, method, params).toByteArray(Charsets.UTF_8), timeoutMs)
        val answer = bytes?.let { ShellyRpc.answer(String(it, Charsets.UTF_8)) }
        if (answer == null || (answer.id != null && answer.id != id)) {
            // No answer, or another request's: the frames are out of step, so the next call starts on a fresh connection.
            transport.disconnect()
            connectedTo = null
            if (_reachable.value != false) log("Plug: $method — " + if (bytes == null) "no answer" else "an answer that isn't this request's")
            return null
        }
        if (!answer.ok) log("Plug: $method — error ${answer.code} ${answer.message}")
        return answer
    }

    /** The connection goes [IDLE_MS] after the last call, unless the rail is wanted meanwhile. */
    private fun scheduleIdle() {
        idle?.cancel()
        if (held) return
        idle = scope.launch {
            delay(IDLE_MS)
            calls.withLock {
                if (!held) {
                    transport.disconnect()
                    connectedTo = null
                }
            }
        }
    }

    /** Lets go of the plug now (it was forgotten). */
    suspend fun drop() = withContext(io) {
        calls.withLock {
            transport.disconnect()
            connectedTo = null
        }
    }

    // ---- What the app asks -------------------------------------------------------------------------------------

    suspend fun deviceInfo(to: String? = null): PlugInfo? = call("Shelly.GetDeviceInfo", to = to)?.takeIf { it.ok }?.let { PlugInfo.of(it.result) }

    /** ARM (`on` with [toggleAfter]) or CUT: true when the plug took it. */
    suspend fun switchSet(on: Boolean, toggleAfter: Int? = null, to: String? = null): Boolean =
        call("Switch.Set", ShellyRpc.switchSet(on, toggleAfter), to)?.ok == true

    suspend fun switchStatus(to: String? = null): PlugStatus? =
        call("Switch.GetStatus", JSONObject().put("id", 0), to)?.takeIf { it.ok }?.let { plugStatusOf(it.result) }?.also { _status.value = it }

    companion object {
        const val CALL_TIMEOUT_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val IDLE_MS = 30_000L
    }
}

/** The rail's plug over [client] ([RailPlug]): ARM, CUT and the switch's status. */
class ShellyRail(private val client: PlugClient) : RailPlug {
    override suspend fun arm(seconds: Int): Boolean = client.switchSet(on = true, toggleAfter = seconds)

    override suspend fun cut(): Boolean = client.switchSet(on = false)

    override suspend fun status(): PlugStatus? = client.switchStatus()
}
