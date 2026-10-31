// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

/**
 * A SwitchBot Plug Mini a scan found (v1.36 — M72): its Bluetooth [address] (what the tablet connects to), the [mac] its
 * adverts carry (what the Pair screen shows the last 4 digits of, and the advert watch looks for), its [model] and signal.
 */
data class FoundPlug(val address: String, val mac: String, val model: String = "Plug Mini", val rssi: Int? = null)

/**
 * The plug's Bluetooth (v1.26 — M60; the SwitchBot since v1.36 — M72): the seam between [PlugClient], [PlugAdverts] and
 * Android ([AndroidPlugTransport]; in debug builds on the emulator, the fake plug beside the emulated piano). One GATT
 * connection at a time, apart from the piano's own; each call is made by one caller at a time ([PlugClient] keeps one
 * request in flight). The advert watch's scan ([watch]) runs beside it for the life of the process.
 */
interface PlugTransport {
    /** Why Bluetooth can't be used now (off, no permission, no adapter), in a sentence; null when it can. */
    fun blocker(): String?

    /**
     * Looks for SwitchBot Plug Minis (company 2409, with fd3d service data 'g' or 'j') for [timeoutMs], each reported to
     * [found] as its adverts come (its signal changing), from any thread. Null when the scan ran its time; else why it couldn't.
     */
    suspend fun scan(timeoutMs: Long, found: (FoundPlug) -> Unit): String?

    /**
     * The advert watch's scan (v1.36 — M72): the adverts of the plug at [address] whose MAC is [mac] (null: not known), each
     * to [heard], from any thread, until cancelled. [fast] while the rail is up (a reading every second or two for the power
     * watch), else a low-power scan. Returns only when it couldn't scan, saying why; the caller tries again later.
     */
    suspend fun watch(address: String, mac: String?, fast: Boolean, heard: (PlugAdvert) -> Unit): String

    /** Connects to the plug at [address] (any connection before is dropped): true once its commands can be sent. */
    suspend fun connect(address: String): Boolean

    /** Whether the connection [connect] made is up. */
    val connected: Boolean

    /** One command out ([SwitchBot]) and the reply's bytes back; null on a failure, or after [timeoutMs]. */
    suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray?

    fun disconnect()
}
