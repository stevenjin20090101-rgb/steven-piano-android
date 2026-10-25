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
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * The plug's RPC over Bluetooth (v1.26 — M60; SMART_PLUG.md › One-time setup): the Mongoose OS RPC-over-GATT service the
 * Shelly Plug US Gen4 runs. One request: the frame's length as 4 bytes big-endian to [TX_CTL] (which drops any half-written
 * frame: one frame in flight), the frame to [DATA] in writes of the MTU less 3, then [RX_CTL] read for the reply's length
 * (0 while it isn't ready) and [DATA] read until the reply is whole.
 */
object ShellyGatt {
    val SERVICE: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f53-56435f49445f")
    val DATA: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f64-6174615f5f5f")
    val TX_CTL: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f74-785f63746c5f")
    val RX_CTL: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f72-785f63746c5f")

    /** How the plug names itself while it advertises: "ShellyPlugUSG4-" and its MAC. */
    const val NAME_PREFIX = "ShellyPlugUSG4-"

    /** The ATT header a write's payload shares the MTU with. */
    private const val ATT_HEADER = 3

    /** The smallest MTU (BLE's default). */
    const val MIN_MTU = 23

    /** The longest reply taken: more is a confused plug, not an answer. */
    const val MAX_FRAME = 16 * 1024

    /** [n] as the 4 bytes big-endian TX control takes. */
    fun length(n: Int): ByteArray = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    /** RX control's 4 bytes big-endian as a length; null when there aren't four. */
    fun lengthOf(bytes: ByteArray?): Int? {
        if (bytes == null || bytes.size < 4) return null
        return ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
    }

    /** [frame] in the writes one [mtu] carries (the MTU less the ATT header; 20 bytes at the least). */
    fun chunks(frame: ByteArray, mtu: Int): List<ByteArray> {
        val size = (mtu - ATT_HEADER).coerceAtLeast(MIN_MTU - ATT_HEADER)
        return (frame.indices step size).map { frame.copyOfRange(it, minOf(it + size, frame.size)) }
    }
}

/** The plug's answer to one request: [result] (null for a method that returns none), or its error [code] and [message]. */
data class RpcAnswer(val id: Int?, val result: JSONObject?, val code: Int? = null, val message: String? = null) {
    val ok: Boolean get() = code == null
}

/**
 * The plug's JSON-RPC frames (Shelly Gen2+ API): `{"id":n,"src":"steven-piano","method":"…","params":{…}}` out, and
 * `{"id":n,"src":"…","dst":"steven-piano","result":{…}}` or `…"error":{"code":…,"message":"…"}` back.
 */
object ShellyRpc {
    /** Who the frames come from: the plug answers to it. */
    const val SRC = "steven-piano"

    /** One request's frame. */
    fun request(id: Int, method: String, params: JSONObject? = null): String = JSONObject().apply {
        put("id", id)
        put("src", SRC)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    /** A reply's frame read; null when it isn't one. */
    fun answer(text: String): RpcAnswer? = try {
        val frame = JSONObject(text)
        val id = if (frame.has("id")) frame.optInt("id") else null
        val error = frame.optJSONObject("error")
        when {
            error != null -> RpcAnswer(id, null, error.optInt("code", -1), error.optString("message", ""))
            frame.has("result") -> RpcAnswer(id, frame.optJSONObject("result"))
            else -> null
        }
    } catch (e: JSONException) {
        null
    }

    // ---- The requests the app makes -----------------------------------------------------------------------------

    /** ARM (with [toggleAfter] seconds) or CUT (off, no timer): `Switch.Set {id:0, on, toggle_after}`. */
    fun switchSet(on: Boolean, toggleAfter: Int? = null): JSONObject = JSONObject().apply {
        put("id", 0)
        put("on", on)
        if (toggleAfter != null) put("toggle_after", toggleAfter)
    }

    /**
     * The plug's settings (SMART_PLUG.md › One-time setup): a mains blip leaves the rail off, and the plug's own button can't
     * turn it on and leave it on. [inMode] false for a model that refuses `in_mode` (then `in_locked` alone does it).
     */
    fun switchConfig(inMode: Boolean = true): JSONObject = JSONObject().apply {
        put("id", 0)
        put(
            "config",
            JSONObject().apply {
                put("name", "Piano 24 V")
                put("initial_state", "off")
                put("auto_on", false)
                put("auto_off", false)
                if (inMode) put("in_mode", "detached")
                put("in_locked", true)
            },
        )
    }

    fun config(config: JSONObject): JSONObject = JSONObject().put("config", config)

    /** RPC over Bluetooth on: `BLE.SetConfig {config:{rpc:{enable:true}}}`. */
    fun bleRpcOn(): JSONObject = config(JSONObject().put("rpc", JSONObject().put("enable", true)))

    fun cloudOff(): JSONObject = config(JSONObject().put("enable", false))

    /**
     * Matter off (v1.32 — M70): `Matter.SetConfig {config:{enable:false}}`. The Gen4 plug runs Matter, a second way to hold
     * the 24 V on (no 30 s timer), and advertises for commissioning after every boot. Its answer may ask for a restart.
     */
    fun matterOff(): JSONObject = config(JSONObject().put("enable", false))

    /** `Matter.GetConfig`: whether Matter is on (v1.32 — M70). */
    fun matterEnabled(config: JSONObject?): Boolean = config?.optBoolean("enable", false) == true

    /** Off every network, the access point closed: `WiFi.SetConfig {config:{sta:{enable:false}, ap:{enable:false}}}`. */
    fun networkOff(): JSONObject = config(
        JSONObject()
            .put("sta", JSONObject().put("enable", false))
            .put("ap", JSONObject().put("enable", false)),
    )

    /** The phone's hotspot over Bluetooth (a new plug's provisioning, the update's network): `WiFi.SetConfig {config:{sta:{ssid, pass, enable:true}}}`. */
    fun networkOn(ssid: String, pass: String): JSONObject =
        config(JSONObject().put("sta", JSONObject().put("ssid", ssid).put("pass", pass).put("enable", true)))

    // ---- The plug watch (v1.29 — M66) -------------------------------------------------------------------------------

    /**
     * Whether `Switch.GetConfig` differs from [switchConfig] where it matters: on at power-up, an automatic on, the button
     * able to hold the 24 V (`in_locked`, and `in_mode` unless [inModeTaken] is false: the model refused it). Keys the
     * plug doesn't report are no difference.
     */
    fun switchChanged(config: JSONObject?, inModeTaken: Boolean = true): Boolean {
        config ?: return false
        if (config.has("initial_state") && config.optString("initial_state") != "off") return true
        if (config.optBoolean("auto_on", false)) return true
        if (config.has("in_locked") && !config.optBoolean("in_locked", false)) return true
        return inModeTaken && config.has("in_mode") && config.optString("in_mode") != "detached"
    }

    /** `Cloud.GetConfig`: whether the cloud is on. */
    fun cloudEnabled(config: JSONObject?): Boolean = config?.optBoolean("enable", false) == true

    /** `WiFi.GetConfig`: whether a network (`sta`, `sta1`) or its own access point (`ap`) is on. */
    fun networkEnabled(config: JSONObject?): Boolean =
        listOf("sta", "sta1", "ap").any { config?.optJSONObject(it)?.optBoolean("enable", false) == true }

    /** Off every network, the second one too, the access point closed: the watch's [networkOff], and (v1.32 — M70) the setup's. */
    fun networkOffAll(): JSONObject = networkOff().also { it.getJSONObject("config").put("sta1", JSONObject().put("enable", false)) }

    /** Whether a `*.SetConfig` answer asks for a restart. */
    fun restartRequired(answer: RpcAnswer?): Boolean = answer?.result?.optBoolean("restart_required", false) == true

    // ---- The update (v1.28 — M65) ---------------------------------------------------------------------------------

    /** The 24 V off at power-up, through the update's restarts: `Switch.SetConfig {id:0, config:{initial_state:"off"}}`. */
    fun offAtPowerUp(): JSONObject = JSONObject().put("id", 0).put("config", JSONObject().put("initial_state", "off"))

    /** Shelly's stable firmware: `Shelly.Update {stage:"stable"}`. */
    fun updateStable(): JSONObject = JSONObject().put("stage", "stable")

    /** Whether `WiFi.GetStatus` says the plug is online: `status` "got ip". */
    fun online(result: JSONObject?): Boolean = result?.optString("status")?.trim()?.lowercase() == "got ip"

    /** The stable firmware `Shelly.CheckForUpdate` offers (`stable.version`); null with none (its result is `{}` then). */
    fun stableOffered(result: JSONObject?): String? =
        result?.optJSONObject("stable")?.optString("version", "")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /**
     * The addresses `BLE.ListPairedDevices` lists, upper case. Its shape isn't pinned down in Shelly's docs, so any array
     * in the result is read: of strings, or of objects with an `addr` (or `mac`).
     */
    fun pairedAddresses(result: JSONObject?): List<String> {
        result ?: return emptyList()
        val found = mutableListOf<String>()
        for (key in result.keys()) {
            val array: JSONArray = result.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val entry = array.opt(i)
                val address = when (entry) {
                    is String -> entry
                    is JSONObject -> entry.optString("addr").ifEmpty { entry.optString("mac") }
                    else -> ""
                }
                if (address.isNotBlank()) found += address.trim().uppercase()
            }
        }
        return found
    }
}

/** How far the plug's one-time setup has come (`Shelly.GetDeviceInfo` → `provision`, SMART_PLUG.md). */
enum class Provision(val word: String) {
    /** New: never provisioned. */
    Pending("pending"),

    /** Provisioned (on a network once): RPC over Bluetooth still answers without pairing. */
    Confirmed("confirmed"),

    /** About 5 minutes after it: pairing is enforced for every RPC over Bluetooth. */
    Complete("complete"),

    /** The setup window ran out without the plug ever going online: Bluetooth RPC is off until it is power-cycled. */
    Locked("locked"),

    /** A word this app doesn't know. */
    Unknown(""),
    ;

    companion object {
        fun of(word: String?): Provision = entries.firstOrNull { it.word.isNotEmpty() && it.word == word?.trim()?.lowercase() } ?: Unknown
    }
}

/** `Shelly.GetDeviceInfo`: the plug's [id] ("shellyplugusg4-…"), [mac], [model], firmware [ver] and [provision]. */
data class PlugInfo(val id: String?, val mac: String?, val model: String?, val ver: String?, val provision: Provision) {
    /** Firmware 2.0.0 or later: the pairing this setup needs (`BLE.StartPairing`). */
    val pairs: Boolean get() = versionAtLeast(ver, 2, 0)

    companion object {
        fun of(result: JSONObject?): PlugInfo? {
            result ?: return null
            fun text(name: String) = result.optString(name, "").trim().takeIf { it.isNotEmpty() && it != "null" }
            return PlugInfo(text("id"), text("mac"), text("model"), text("ver"), Provision.of(text("provision")))
        }

        /** Whether [version] ("2.0.0", "2.1.3-beta1") is [major].[minor] or later; false when it doesn't read. */
        fun versionAtLeast(version: String?, major: Int, minor: Int): Boolean {
            val parts = version?.trim()?.split('.', '-', '+')?.mapNotNull { it.toIntOrNull() } ?: return false
            val ma = parts.getOrNull(0) ?: return false
            val mi = parts.getOrNull(1) ?: 0
            return ma > major || (ma == major && mi >= minor)
        }
    }
}

/** `Switch.GetStatus {id:0}` read: the output, the timer's length while one runs, the supply's draw. */
fun plugStatusOf(result: JSONObject?): PlugStatus? {
    result ?: return null
    if (!result.has("output")) return null
    val timer = if (result.has("timer_duration")) result.optDouble("timer_duration").takeIf { it.isFinite() } else null
    val watts = if (result.has("apower")) result.optDouble("apower").takeIf { it.isFinite() } else null
    return PlugStatus(result.optBoolean("output"), timer, watts)
}
