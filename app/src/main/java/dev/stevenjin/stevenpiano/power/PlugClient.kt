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
        ask(method, params, to, timeoutMs).answer

    /** [method] to the plug at [to] under the one lock: its answer, or what kept it ([Miss]). */
    private suspend fun ask(method: String, params: JSONObject?, to: String?, timeoutMs: Long): Outcome =
        withContext(io) {
            calls.withLock {
                val target = to ?: address() ?: return@withLock Outcome(null, Miss.NoConnection)
                val outcome = exchange(target, method, params, timeoutMs)
                _reachable.value = outcome.answer != null
                scheduleIdle()
                outcome
            }
        }

    /** One exchange's end: the [answer], or the [miss] that kept it. */
    private class Outcome(val answer: RpcAnswer?, val miss: Miss?)

    /** Why there was no answer: no connection; the plug refusing this tablet; connected and nothing came back; a reply out of step. */
    private enum class Miss { NoConnection, Refused, NoReply, Garbled }

    private suspend fun exchange(target: String, method: String, params: JSONObject?, timeoutMs: Long): Outcome {
        if (!transport.connected || connectedTo != target) {
            connectedTo = null
            val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { transport.connect(target) } == true
            if (!up) {
                // Reached, but no RPC service to ask (v1.26 — M62): as silent as a plug that never replies.
                val reached = transport.reached
                transport.disconnect()
                // Once, not at every poll while away.
                if (_reachable.value != false) log("Plug: " + if (reached) "$target connected without its RPC service" else "no connection to $target")
                return Outcome(null, if (reached) Miss.NoReply else Miss.NoConnection)
            }
            connectedTo = target
        }
        val id = nextId++
        val bytes = transport.exchange(ShellyRpc.request(id, method, params).toByteArray(Charsets.UTF_8), timeoutMs)
        val answer = bytes?.let { ShellyRpc.answer(String(it, Charsets.UTF_8)) }
        if (answer == null || (answer.id != null && answer.id != id)) {
            // No answer, or another request's: the frames are out of step, so the next call starts on a fresh connection.
            val refused = bytes == null && transport.refused
            transport.disconnect()
            connectedTo = null
            if (_reachable.value != false) log("Plug: $method — " + if (bytes == null) "no answer" else "an answer that isn't this request's")
            return Outcome(null, if (bytes != null) Miss.Garbled else if (refused) Miss.Refused else Miss.NoReply)
        }
        if (!answer.ok) log("Plug: $method — error ${answer.code} ${answer.message}")
        return Outcome(answer, null)
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

    /**
     * The plug at [to] (the paired one by default) read for what it is (v1.26 — M62; SMART_PLUG.md › One-time setup):
     * `Shelly.GetDeviceInfo` given [SILENT_MS] to answer. A plug that connects but gives no reply in that time, or has no
     * RPC service at all, is [PlugRead.Silent]: with no bond, a `locked` plug (its RPC over Bluetooth is off until a power
     * cycle); a bonded plug in `complete` always answers.
     */
    suspend fun read(to: String? = null): PlugRead {
        val outcome = ask("Shelly.GetDeviceInfo", null, to, SILENT_MS)
        outcome.answer?.let { answer ->
            return answer.takeIf { it.ok }?.let { PlugInfo.of(it.result) }?.let(PlugRead::Read) ?: PlugRead.Unreachable
        }
        return when (outcome.miss) {
            Miss.Refused -> PlugRead.Refused
            Miss.NoReply -> PlugRead.Silent
            else -> PlugRead.Unreachable
        }
    }

    /** ARM (`on` with [toggleAfter]) or CUT: true when the plug took it. */
    suspend fun switchSet(on: Boolean, toggleAfter: Int? = null, to: String? = null): Boolean =
        call("Switch.Set", ShellyRpc.switchSet(on, toggleAfter), to)?.ok == true

    suspend fun switchStatus(to: String? = null): PlugStatus? =
        call("Switch.GetStatus", JSONObject().put("id", 0), to)?.takeIf { it.ok }?.let { plugStatusOf(it.result) }?.also { _status.value = it }

    companion object {
        const val CALL_TIMEOUT_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val IDLE_MS = 30_000L

        /** A plug with no bond that connects and gives no reply in this long is `locked` (v1.26 — M62; SMART_PLUG.md). */
        const val SILENT_MS = 5_000L
    }
}

/** What reading a plug found (v1.26 — M62; [PlugClient.read]). */
sealed interface PlugRead {
    /** It answered: what it said of itself. */
    data class Read(val info: PlugInfo) : PlugRead

    /** No connection (out of range, Bluetooth off, restarting), or an answer that didn't read. */
    data object Unreachable : PlugRead

    /** It refuses this tablet: pairing enforced, and no bond with it (GATT authentication or encryption). */
    data object Refused : PlugRead

    /** It connected and gave no reply in [PlugClient.SILENT_MS], or has no RPC service: with no bond, a `locked` plug. */
    data object Silent : PlugRead
}

/**
 * The rail's plug over [client] ([RailPlug]): ARM, CUT, the switch's status, and (v1.29 — M66) its settings put back; since
 * v1.32 (M70) its Matter kept off too, quietly the first time ([matterOff] unset: a 1.31 pairing), said after that.
 */
class ShellyRail(
    private val client: PlugClient,
    /** Whether this tablet turned the plug's Matter off already (v1.32 — M70; [PlugPairing.matterOff]). */
    private val matterOff: () -> Boolean,
    /** Keeps that it has (v1.32 — M70; [PlugStore.matterTurnedOff]). */
    private val keepMatterOff: suspend () -> Unit,
    private val log: (String) -> Unit = {},
) : RailPlug {
    /** The model refused `in_mode` at a put-back (its retry took `in_locked` alone): not compared again. */
    private var inModeTaken = true

    override suspend fun arm(seconds: Int): Boolean = client.switchSet(on = true, toggleAfter = seconds)

    override suspend fun cut(): Boolean = client.switchSet(on = false)

    override suspend fun status(): PlugStatus? = client.switchStatus()

    /** The switch's settings, the cloud and the network read; what differs put back (the pairing's values); then Matter. */
    override suspend fun putBack(): Boolean? {
        val switch = client.call("Switch.GetConfig", JSONObject().put("id", 0)) ?: return null
        val cloud = client.call("Cloud.GetConfig") ?: return null
        val wifi = client.call("WiFi.GetConfig") ?: return null
        var put = false
        // The switch as the tablet set it (off at power-up, no automatic on): only then may Matter's restart come.
        var switchAsSet = switch.ok
        if (switch.ok && ShellyRpc.switchChanged(switch.result, inModeTaken)) {
            var set = client.call("Switch.SetConfig", ShellyRpc.switchConfig(inMode = inModeTaken))
            if (set != null && !set.ok && inModeTaken) {
                set = client.call("Switch.SetConfig", ShellyRpc.switchConfig(inMode = false))
                if (set?.ok == true) inModeTaken = false
            }
            switchAsSet = set?.ok == true
            put = true
        }
        if (cloud.ok && ShellyRpc.cloudEnabled(cloud.result)) {
            client.call("Cloud.SetConfig", ShellyRpc.cloudOff())
            put = true
        }
        if (wifi.ok && ShellyRpc.networkEnabled(wifi.result)) {
            client.call("WiFi.SetConfig", ShellyRpc.networkOffAll())
            put = true
        }
        if (matterPutBack(switchAsSet)) put = true
        return put
    }

    /**
     * Matter (v1.32 — M70), last: `Matter.GetConfig`; on, `Matter.SetConfig` off and the restart it asks for. The watch runs
     * only while nothing wants the 24 V, and the restart waits for the switch as the tablet set it, so the plug comes back
     * off; it is waited out here, the plug read again, before the watch goes on (its CUT and weld look, its next look). The
     * first time (nothing kept: a 1.31 pairing, or a setup whose `Matter.SetConfig` went unanswered) it is quiet, logged
     * only, and kept, Matter found off included; after that, Matter on again is a change put back (true). An error
     * (firmware without Matter) or no answer is skipped, nothing said.
     */
    private suspend fun matterPutBack(switchAsSet: Boolean): Boolean {
        val config = client.call("Matter.GetConfig")?.takeIf { it.ok } ?: return false
        val first = !matterOff()
        if (!ShellyRpc.matterEnabled(config.result)) {
            if (first) keepMatterOff()   // off already: Matter on from now on is a change
            return false
        }
        if (!switchAsSet) {
            log("Plug watch: the plug's Matter is on; left until its switch's settings are back")
            return false
        }
        val off = client.call("Matter.SetConfig", ShellyRpc.matterOff())
        if (off?.ok != true) {
            log("Plug watch: the plug's Matter is on and it didn't take Matter off")
            return false
        }
        if (first) {
            log("Plug watch: the plug's Matter was on; turned off (the first look since pairing, so nothing said)")
            keepMatterOff()
        }
        if (ShellyRpc.restartRequired(off)) {
            log("Plug watch: the plug restarts for Matter off, with the 24 V off at power-up")
            client.call("Shelly.Reboot")
            client.drop()
            delay(PairFlow.REBOOT_WAIT_MS)
            client.switchStatus()   // back, and connected again before the watch goes on
        }
        return !first
    }
}
