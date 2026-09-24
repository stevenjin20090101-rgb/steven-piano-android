// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.MidiSink
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** How the piano shows itself over Bluetooth LE (firmware: BLE-MIDI 2.2 on NimBLE). */
object PianoBluetooth {
    /** In the scan response, not the advertisement: scans filter by service instead. */
    const val NAME = "Steven Piano"
    val SERVICE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")
}

/**
 * The connection to the piano. Nothing ever comes back from the piano, so [state] is all
 * the app can know about it. Implementations must be safe to call from any thread.
 */
interface PianoLink : MidiSink {
    val state: StateFlow<LinkState>

    /** Finds and connects to the piano, preferring [address] (the last one used) when given. */
    fun connect(address: String? = null)

    /** Drops the connection and stops reconnecting. Pause the player first so the piano is silenced. */
    fun disconnect()

    /** Queues [batch] for the piano, paced; dropped while not connected. */
    override fun send(batch: MidiBatch, dropPending: Boolean)

    /** Blocks until every queued message is written or [timeoutMs] passes; true when drained. Not on the main thread for long. */
    fun flush(timeoutMs: Long): Boolean
}

sealed interface LinkState {
    data object Disconnected : LinkState
    data object Scanning : LinkState
    data object Connecting : LinkState
    data class Connected(val name: String, val mtu: Int) : LinkState
    data class Reconnecting(val attempt: Int) : LinkState
    data class Error(val reason: LinkError, val message: String) : LinkState
}

/** Why a connection failed, so the Piano tab can offer the right fix. Messages are the copy to show. */
enum class LinkError(val message: String) {
    NotFound("Can't reach Steven Piano. Make sure it's powered on and within range."),
    BluetoothOff("Bluetooth is off. Turn it on to connect to the piano."),
    PermissionMissing("Steven Piano needs permission to find and connect to the piano."),
    LocationOff("Location is off. This phone needs it on to find Bluetooth devices."),
    Unsupported("This phone doesn't support Bluetooth LE, so it can't reach the piano."),
    Failed("The connection to Steven Piano failed. Try again."),
    ;

    fun toState(): LinkState.Error = LinkState.Error(this, message)
}
