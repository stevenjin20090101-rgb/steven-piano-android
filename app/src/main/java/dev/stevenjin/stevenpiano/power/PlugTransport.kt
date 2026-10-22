// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

/** A plug a scan found: its Bluetooth [address], its advertised [name] ("ShellyPlugUSG4-A8032AB5C1D2") and its signal. */
data class FoundPlug(val address: String, val name: String, val rssi: Int? = null)

/**
 * The plug's Bluetooth (v1.26 — M60): the seam between [PlugClient] and Android ([AndroidPlugTransport]; in debug builds on
 * the emulator, the fake plug beside the emulated piano). One GATT connection at a time, apart from the piano's own; each
 * call is made by one caller at a time ([PlugClient] keeps one request in flight).
 */
interface PlugTransport {
    /** Why Bluetooth can't be used now (off, no permission, no adapter), in a sentence; null when it can. */
    fun blocker(): String?

    /**
     * Looks for plugs (names beginning [ShellyGatt.NAME_PREFIX]) for [timeoutMs], each reported to [found] once, from any
     * thread. Null when the scan ran its time; else why it couldn't.
     */
    suspend fun scan(timeoutMs: Long, found: (FoundPlug) -> Unit): String?

    /** Connects to the plug at [address] (any connection before is dropped): true once its RPC service is ready. */
    suspend fun connect(address: String): Boolean

    /** Whether the connection [connect] made is up. */
    val connected: Boolean

    /** One request frame out and its reply's bytes back ([ShellyGatt]); null on a failure, or after [timeoutMs]. */
    suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray?

    /** Whether the last failure was the plug refusing this tablet (pairing enforced and no bond: GATT auth or encryption). */
    val refused: Boolean

    fun disconnect()

    /** Pairs this tablet with the plug at [address] (Just Works; Android may ask the person): true once bonded. */
    suspend fun bond(address: String, timeoutMs: Long): Boolean

    /** Whether this tablet is bonded with [address]. */
    fun bonded(address: String): Boolean
}
