// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The Shelly Plug US Gen4 as the emulator's fake plug answers it (v1.26 — M60; debug builds only): the RPC methods the app
 * uses, as `firmware/docs/SMART_PLUG.md` and Shelly's Gen2+ API describe them, for the emulator's stand-in and for tests.
 *
 * - **Its switch:** `Switch.Set` with `toggle_after` (the timer runs on [clock]; the output reads off once it ran out),
 *   `Switch.GetStatus` with `apower` (a few watts while the 24 V supply is up), `Switch.SetConfig`.
 * - **Its setup** (`provision`): [Provision.Pending] (new) → [Provision.Confirmed] once on a network → [Provision.Complete]
 *   [completeAfterMs] later while it stays online; offline when its setup window ([windowMs] from the start) runs out
 *   before that, [Provision.Locked]. From Complete pairing is enforced: a central without a bond is refused everything
 *   ([Answer.Refused]). Locked answers nothing at all, `Shelly.GetDeviceInfo` included ([Answer.Silent], v1.26 — M62, as
 *   the firmware chat confirmed in SMART_PLUG.md): a power cycle, [reset], ends it.
 * - **Bonds:** `BLE.StartPairing` opens a window in which [bond] succeeds (20 bonds at most); `BLE.ListPairedDevices` and
 *   `BLE.DeletePairedDevice` list and drop them. `BLE.SetConfig` turning RPC on asks for a restart the first time
 *   (`Shelly.Reboot`: away for [rebootMs]).
 * - **Its firmware** (v1.28 — M65): `WiFi.GetStatus` (`got ip` [onlineAfterMs] after `WiFi.SetConfig` gave it a network),
 *   `Shelly.CheckForUpdate` ([offered] while its firmware is older; online only), `Shelly.Update` (away for [updateMs], back
 *   on the new firmware with its state kept; [refuseUpdate] refuses it, as the real plug may over Bluetooth),
 *   `Shelly.FactoryReset` (new again: no network, no bonds, its settings the defaults, the same firmware; away for
 *   [rebootMs]). Before 1.7.5 its device info has no `provision`.
 * - [weld] keeps the relay closed whatever is asked (output reads off, the supply still draws): the welded relay.
 *
 * Thread-safe: every call is synchronized.
 */
class EmulatedShelly(
    start: Provision = Provision.Confirmed,
    private val clock: () -> Long = System::currentTimeMillis,
    private val completeAfterMs: Long = COMPLETE_AFTER_MS,
    private val windowMs: Long = WINDOW_MS,
    private val rebootMs: Long = REBOOT_MS,
    /** Firmware older than 2.0.0 has no pairing (`BLE.StartPairing`). */
    firmware: String = FIRMWARE,
    private val onlineAfterMs: Long = ONLINE_AFTER_MS,
    private val updateMs: Long = UPDATE_MS,
) {
    /** Its firmware now: what `Shelly.Update` installed, once it did (v1.28 — M65). */
    @Volatile
    var firmware: String = firmware
        private set

    /** What `Shelly.CheckForUpdate` offers while the firmware is older (v1.28 — M65); null: nothing. */
    @Volatile
    var offered: String? = OFFERED

    /** `Shelly.Update` refused over Bluetooth (v1.28 — M65): whether the real plug takes it is Shelly's to say. */
    @Volatile
    var refuseUpdate: Boolean = false

    /**
     * Firmware older than 2.0.0 lets only paired centrals in once `complete` (v1.29 — M66), though it can't pair: every
     * request refused, its device info too. Off: such a plug answers anyone, as the as-is way needs.
     */
    @Volatile
    var refuseUnpaired: Boolean = false

    /**
     * What one request gets: its reply frame, the GATT refusal a stranger to a plug that enforces pairing gets, or nothing
     * ever (a locked plug: the connection is made, and its RPC over Bluetooth stays silent).
     */
    sealed interface Answer {
        data class Reply(val json: String) : Answer

        data object Refused : Answer

        data object Silent : Answer
    }

    private var provision = Provision.Pending
    private var startedAt = clock()
    private var confirmedAt: Long? = null
    private var staOn = false
    private var apOn = true
    private var cloudOn = true
    private var rpcOn = true
    private var rpcRestartAsked = false
    private var output = false
    private var onSince: Long? = null
    private var timerEnd: Long? = null
    private var timerS: Int? = null
    private var pairingUntil = 0L
    private val bonds = LinkedHashSet<String>()
    private var config = JSONObject()
    private var rebootUntil = 0L

    /** When `WiFi.SetConfig` last gave it a network to join (v1.28 — M65); null with none. */
    private var joinedAt: Long? = null

    /** The relay stuck closed: a CUT reads off but the supply stays up. */
    @Volatile
    var weld: Boolean = false

    init {
        reset(start)
    }

    /** As fresh from its box ([Provision.Pending]), set up with the Shelly app ([Provision.Confirmed]), and so on: a power cycle. */
    @Synchronized
    fun reset(state: Provision, bonded: String? = null) {
        val now = clock()
        startedAt = now
        provision = if (state == Provision.Unknown) Provision.Confirmed else state
        confirmedAt = if (provision == Provision.Confirmed) now else null
        staOn = provision == Provision.Confirmed
        joinedAt = if (staOn) now - onlineAfterMs else null   // the Shelly app left it on the hotspot, online
        apOn = provision == Provision.Pending
        cloudOn = provision != Provision.Pending
        rpcOn = true
        rpcRestartAsked = false
        output = false
        onSince = null
        timerEnd = null
        timerS = null
        pairingUntil = 0L
        bonds.clear()
        rebootUntil = 0L
        if (bonded != null) bonds += bonded
        if (provision == Provision.Complete && bonded != null) {
            // Paired, set up and off every network (the end of the Pair screen).
            staOn = false
            joinedAt = null
            apOn = false
            cloudOn = false
            config = ShellyRpc.switchConfig().getJSONObject("config")
        }
    }

    /** Whether it is restarting (`Shelly.Reboot`, `Shelly.Update`, `Shelly.FactoryReset`): no connection meanwhile. */
    @Synchronized
    fun rebooting(): Boolean = clock() < rebootUntil

    /** Whether it is on a network or its own access point is open (v1.28 — M65): the Pair screen must end with neither. */
    @Synchronized
    fun networkUp(): Boolean = staOn || apOn

    /** Whether its relay feeds the 24 V supply now. */
    @Synchronized
    fun powered(): Boolean = outputNow() || weld && onSince != null

    /** Since when the supply has been up ([clock]'s ms); null while it is off. */
    @Synchronized
    fun poweredSince(): Long? = if (powered()) onSince else null

    @Synchronized
    fun provisionNow(): Provision {
        if (older(firmware, FIRST_PROVISION)) return provision   // v1.29 — M66: no setup states before 1.7.5, so no lock
        val now = clock()
        if (provision == Provision.Confirmed && staOn && now >= (confirmedAt ?: now) + completeAfterMs) provision = Provision.Complete
        if ((provision == Provision.Pending || provision == Provision.Confirmed) && !staOn && now >= startedAt + windowMs) provision = Provision.Locked
        return provision
    }

    /** Switched on from outside (v1.29 — M66): another phone's Shelly app, no timer. */
    @Synchronized
    fun switchOnFromOutside() {
        if (!outputNow()) onSince = clock()
        output = true
        timerEnd = null
        timerS = null
    }

    /** One of its switch's settings changed from outside (v1.29 — M66), as the Shelly app would. */
    @Synchronized
    fun changeSetting(key: String, value: Any) {
        config.put(key, value)
    }

    /** Its switch's settings now (v1.29 — M66). */
    @Synchronized
    fun switchSettings(): JSONObject = JSONObject(config.toString())

    /** Whether [central] is bonded. */
    @Synchronized
    fun bonded(central: String): Boolean = central in bonds

    /** Android's bond with [central]: only while a pairing window is open, 20 at most. */
    @Synchronized
    fun bond(central: String): Boolean {
        if (central in bonds) return true
        if (clock() >= pairingUntil || bonds.size >= MAX_BONDS) return false
        bonds += central
        return true
    }

    /** One request from [central] ([address] as `BLE.ListPairedDevices` names it). */
    @Synchronized
    fun handle(central: String, frame: String): Answer {
        val request = try {
            JSONObject(frame)
        } catch (e: org.json.JSONException) {
            return Answer.Reply(error(null, -32700, "Parse error"))
        }
        val id = request.opt("id")
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        val state = provisionNow()
        if (state == Provision.Locked) return Answer.Silent   // RPC over Bluetooth off until a power cycle
        if (state == Provision.Complete && central !in bonds) {
            // v1.29 — M66: before 2.0.0 nothing is enforced, unless [refuseUnpaired] says this firmware does.
            if (PlugInfo.versionAtLeast(firmware, 2, 0) && method != INFO) return Answer.Refused
            if (!PlugInfo.versionAtLeast(firmware, 2, 0) && refuseUnpaired) return Answer.Refused
        }
        if (!rpcOn && method != "BLE.SetConfig" && method != INFO) return Answer.Refused
        return Answer.Reply(
            when (method) {
                INFO -> result(id, info(state))
                "Switch.Set" -> switchSet(id, params)
                "Switch.GetStatus" -> result(id, status())
                "Switch.SetConfig" -> {
                    // Merged, as Shelly's are: the update's `initial_state` alone keeps what was set before.
                    params.optJSONObject("config")?.let { given -> given.keys().forEach { config.put(it, given.get(it)) } }
                    result(id, JSONObject().put("restart_required", false))
                }
                "Switch.GetConfig" -> result(id, JSONObject(config.toString()).put("id", 0))
                "BLE.SetConfig" -> {
                    val rpc = params.optJSONObject("config")?.optJSONObject("rpc")
                    if (rpc != null) rpcOn = rpc.optBoolean("enable", rpcOn)
                    val restart = !rpcRestartAsked
                    rpcRestartAsked = true
                    result(id, JSONObject().put("restart_required", restart))
                }
                "BLE.StartPairing" -> if (PlugInfo.versionAtLeast(firmware, 2, 0)) {
                    pairingUntil = clock() + params.optInt("timeout", PAIRING_DEFAULT_S) * MS_PER_S
                    result(id, null)
                } else {
                    error(id, -114, "Method BLE.StartPairing failed: No handler")
                }
                "BLE.ListPairedDevices" -> result(id, JSONObject().put("devices", JSONArray(bonds.map { JSONObject().put("addr", addressOf(it)) })))
                "BLE.DeletePairedDevice" -> {
                    val gone = params.optString("addr").uppercase(Locale.ROOT)
                    bonds.removeAll { addressOf(it) == gone }
                    result(id, null)
                }
                "Cloud.GetConfig" -> result(id, JSONObject().put("enable", cloudOn).put("server", "shelly-emulator.example:6022/jrpc"))
                "WiFi.GetConfig" -> result(
                    id,
                    JSONObject().put("ap", JSONObject().put("enable", apOn)).put("sta", JSONObject().put("enable", staOn)).put("sta1", JSONObject().put("enable", false)),
                )
                "Cloud.SetConfig" -> {
                    cloudOn = params.optJSONObject("config")?.optBoolean("enable", cloudOn) ?: cloudOn
                    result(id, JSONObject().put("restart_required", false))
                }
                "WiFi.SetConfig" -> wifi(id, params)
                "WiFi.GetStatus" -> result(id, wifiStatus())
                "Shelly.Reboot" -> {
                    rebootUntil = clock() + rebootMs
                    result(id, null)
                }
                "Shelly.CheckForUpdate" -> checkForUpdate(id)
                "Shelly.Update" -> update(id, params)
                "Shelly.FactoryReset" -> {
                    // New again, the same firmware: away while it restarts.
                    reset(Provision.Pending)
                    config = JSONObject()
                    rebootUntil = clock() + rebootMs
                    result(id, null)
                }
                else -> error(id, -114, "Method $method failed: No handler")
            },
        )
    }

    /** On its network with an address (`got ip`): [onlineAfterMs] after `WiFi.SetConfig` gave it one (v1.28 — M65). */
    private fun online(): Boolean = staOn && joinedAt?.let { clock() >= it + onlineAfterMs } == true

    /** `WiFi.GetStatus`; the real plug names its network (`ssid`) too, left out here so the hotspot's name never reaches a log. */
    private fun wifiStatus(): JSONObject = JSONObject().apply {
        val online = online()
        put("sta_ip", if (online) STA_IP else JSONObject.NULL)
        put("status", if (online) "got ip" else if (staOn && joinedAt != null) "connecting" else "disconnected")
        put("rssi", if (online) STA_RSSI else 0)
        put("ap_client_count", 0)
    }

    /** What Shelly offers: [offered] while the firmware is older, found through its network only. */
    private fun newer(): String? = offered?.takeIf { older(firmware, it) }

    private fun checkForUpdate(id: Any?): String {
        if (!online()) return error(id, -114, "Method Shelly.CheckForUpdate failed: no network")
        val to = newer() ?: return result(id, JSONObject())
        return result(id, JSONObject().put("stable", JSONObject().put("version", to).put("build_id", "20260915-120000/$to-gemulator")))
    }

    /** `Shelly.Update`: accepted, the link drops, and it is back [updateMs] later on the new firmware, its state kept. */
    private fun update(id: Any?, params: JSONObject): String {
        val to = newer()
        return when {
            refuseUpdate -> error(id, -114, "Method Shelly.Update failed: not available over Bluetooth")
            !online() || to == null || params.optString("stage", "stable") != "stable" -> error(id, -114, "Method Shelly.Update failed: no update")
            else -> {
                firmware = to
                rebootUntil = clock() + updateMs
                result(id, null)
            }
        }
    }

    private fun switchSet(id: Any?, params: JSONObject): String {
        val on = params.optBoolean("on")
        val wasOn = outputNow()
        if (on) {
            if (!wasOn && !(weld && onSince != null)) onSince = clock()
            output = true
            val after = if (params.has("toggle_after")) params.optInt("toggle_after") else null
            timerS = after
            timerEnd = after?.let { clock() + it * MS_PER_S }
        } else {
            output = false
            timerS = null
            timerEnd = null
            if (!weld) onSince = null
        }
        return result(id, JSONObject().put("was_on", wasOn))
    }

    private fun wifi(id: Any?, params: JSONObject): String {
        val config = params.optJSONObject("config") ?: JSONObject()
        config.optJSONObject("sta")?.let { sta ->
            staOn = sta.optBoolean("enable", staOn)
            val joining = staOn && sta.optString("ssid").isNotEmpty()
            if (joining) joinedAt = clock()   // `got ip` a few seconds later (v1.28 — M65)
            if (!staOn) joinedAt = null
            // On a network with internet: provisioned (a new plug over Bluetooth).
            if (joining && provision == Provision.Pending) {
                provision = Provision.Confirmed
                confirmedAt = clock()
            }
        }
        config.optJSONObject("ap")?.let { apOn = it.optBoolean("enable", apOn) }
        return result(id, JSONObject().put("restart_required", false))
    }

    private fun outputNow(): Boolean {
        val end = timerEnd
        if (output && end != null && clock() >= end) {
            output = false
            timerEnd = null
            timerS = null
            if (!weld) onSince = null
        }
        return output
    }

    private fun status(): JSONObject {
        val on = outputNow()
        val supplied = on || weld && onSince != null
        return JSONObject().apply {
            put("id", 0)
            put("source", "BLE")
            put("output", on)
            put("apower", if (supplied) IDLE_WATTS else 0.0)
            put("voltage", 120.2)
            put("freq", 60.0)
            put("current", if (supplied) 0.07 else 0.0)
            put("temperature", JSONObject().put("tC", 31.4).put("tF", 88.5))
            timerS?.let {
                put("timer_started_at", (timerEnd!! - it * MS_PER_S) / MS_PER_S)
                put("timer_duration", it)
            }
        }
    }

    private fun info(state: Provision): JSONObject = JSONObject().apply {
        put("name", JSONObject.NULL)
        put("id", ID)
        put("mac", MAC)
        put("slot", 0)
        put("model", MODEL)
        put("gen", 4)
        put("fw_id", "20251016-101500/$firmware-gemulator")
        put("ver", firmware)
        put("app", "PlugUSG4")
        put("auth_en", false)
        put("auth_domain", JSONObject.NULL)
        if (!older(firmware, FIRST_PROVISION)) put("provision", state.word)   // v1.28 — M65: none before 1.7.5
    }

    private fun result(id: Any?, result: JSONObject?): String =
        JSONObject().put("id", id ?: JSONObject.NULL).put("src", ID).put("dst", ShellyRpc.SRC).put("result", result ?: JSONObject.NULL).toString()

    private fun error(id: Any?, code: Int, message: String): String =
        JSONObject().put("id", id ?: JSONObject.NULL).put("src", ID).put("dst", ShellyRpc.SRC)
            .put("error", JSONObject().put("code", code).put("message", message)).toString()

    companion object {
        const val ID = "shellyplugusg4-a8032ab5c1d2"
        const val MAC = "A8032AB5C1D2"
        const val MODEL = "S4PL-00116US"
        const val FIRMWARE = "2.0.3"

        /** Its Bluetooth address and advertised name. */
        const val ADDRESS = "A8:03:2A:B5:C1:D2"
        const val NAME = "ShellyPlugUSG4-A8032AB5C1D2"

        /** The 24 V supply's draw while up and idle, W. */
        const val IDLE_WATTS = 6.4

        /** Shortened for the emulator: the real plug takes about 5 minutes from confirmed to complete. */
        const val COMPLETE_AFTER_MS = 120_000L

        /** Its setup window: past it, offline and not complete, it locks. */
        const val WINDOW_MS = 10 * 60_000L
        const val REBOOT_MS = 3_000L
        const val MAX_BONDS = 20
        const val PAIRING_DEFAULT_S = 180
        private const val INFO = "Shelly.GetDeviceInfo"
        private const val MS_PER_S = 1_000L

        /** What Shelly offers an older plug (v1.28 — M65). */
        const val OFFERED = "2.1.0"

        /** `got ip` this long after `WiFi.SetConfig` gave it a network (v1.28 — M65). */
        const val ONLINE_AFTER_MS = 3_000L

        /** `Shelly.Update`: downloaded, installed and back in this long (the real plug about 2 minutes; v1.28 — M65). */
        const val UPDATE_MS = 20_000L

        /** The first firmware with `provision` in its device info. */
        private const val FIRST_PROVISION = "1.7.5"

        /** Its address on the hotspot and the signal there: the documentation range, a made-up figure. */
        private const val STA_IP = "192.0.2.17"
        private const val STA_RSSI = -55

        /** Whether firmware [a] is older than [b] ("1.6.2" before "2.1.0"), number by number. */
        fun older(a: String, b: String): Boolean {
            val x = numbers(a)
            val y = numbers(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d < 0
            }
            return false
        }

        private fun numbers(version: String): List<Int> = version.trim().split('.', '-', '+').map { it.toIntOrNull() }.takeWhile { it != null }.filterNotNull()

        /** A request frame for the log, the hotspot's name and password blanked (v1.28 — M65: never in a log, the emulator's included). */
        fun redacted(frame: String): String = SECRET.replace(frame) { "\"${it.groupValues[1]}\":\"…\"" }

        private val SECRET = Regex("\"(ssid|pass)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"")

        /** A central's address as the plug lists its bonds: the emulator's tablet, from the documentation range. */
        fun addressOf(central: String): String = if (central == TABLET) "00:00:5E:00:53:A1" else central.uppercase(Locale.ROOT)

        /** The emulator's tablet, as the fake plug knows it. */
        const val TABLET = "tablet"
    }
}
