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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** How the piano shows itself over Bluetooth LE (firmware: BLE-MIDI 2.2 on NimBLE). */
object PianoBluetooth {
    /** In the scan response, not the advertisement: scans filter by service instead. Also the GAP Device Name. */
    const val NAME = "Steven Piano"
    val SERVICE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")

    /** Generic Access (0x1800) and its Device Name (0x2A00), which NimBLE sets to [NAME]: a connection can read it. */
    val GAP_SERVICE_UUID: UUID = UUID.fromString("00001800-0000-1000-8000-00805F9B34FB")
    val GAP_DEVICE_NAME_UUID: UUID = UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB")
}

/**
 * The connection to the piano. MIDI only goes out; what comes back is [state] and, on firmware
 * that has it, the piano's text console ([console]) on the same connection. Implementations
 * must be safe to call from any thread.
 */
interface PianoLink : MidiSink {
    val state: StateFlow<LinkState>

    /**
     * The piano's console (its settings), while connected to a piano whose firmware offers it;
     * null when not connected or when the piano has no console. Set before [state] turns Connected.
     */
    val console: ConsoleChannel?

    /**
     * Finds and connects to the piano. [address] (the last one used, or one the person chose) is
     * the only piano connected to by itself; another advertising as "Steven Piano" is offered
     * ([LinkError.OtherPiano]), never taken. With no address, the first Steven Piano found is: named
     * in its scan response, or else a nameless BLE-MIDI device whose GAP Device Name reads Steven
     * Piano. A piano another app on this device holds is connected to directly; one this device is
     * paired with is not connected to at all ([LinkError.Paired]).
     */
    fun connect(address: String? = null)

    /** Drops the connection and stops reconnecting. Pause the player first so the piano is silenced. */
    fun disconnect()

    /** Queues [batch] for the piano, paced; dropped while not connected. */
    override fun send(batch: MidiBatch, dropPending: Boolean)

    /** Blocks until every queued message is written or [timeoutMs] passes; true when drained. Not on the main thread for long. */
    fun flush(timeoutMs: Long): Boolean

    /**
     * The last resort, for the crash handler: writes the stop sequence (CC64 = 0, then CC123) straight
     * to the piano, past the paced queue and the link's own thread (which may be the one crashing),
     * retrying a busy stack for at most [timeoutMs]. Callable from any thread; true when the stop went
     * out. Nothing else may use it: it bypasses the one-operation-at-a-time rule.
     */
    fun emergencySilence(timeoutMs: Long): Boolean

    /**
     * The piano's firmware version as its Device Information reports it (0x2A26, "2.0.0+a1b2c3d";
     * BLE_OTA.md › 2), read on each connection before [state] turns Connected. Null while not
     * connected, and on firmware older than 2.0.0, which has none.
     */
    val firmwareVersion: StateFlow<String?> get() = NoFirmwareVersion

    /**
     * The piano's update service (BLE_OTA.md › 3), found before [state] turns Connected; null while
     * not connected, and on firmware that can't be updated over Bluetooth (older than 2.0.0).
     */
    val ota: OtaChannel? get() = null

    /**
     * The piano is about to restart on purpose (an update's OK): for [withinMs] a drop is expected,
     * so the link reconnects to it whatever auto-connect says, and looks for it sooner, rather than
     * giving up on it.
     */
    fun expectRestart(withinMs: Long) {}
}

/** [PianoLink.firmwareVersion] for a link that never knows one. */
private val NoFirmwareVersion: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

sealed interface LinkState {
    data object Disconnected : LinkState
    data object Scanning : LinkState
    data object Connecting : LinkState

    /**
     * [epoch] changes with every new connection, and whenever the link lost a packet the piano
     * needed: the player re-syncs (silence, then the pedal again) when it sees a new one, even if
     * the drop between two connections was too quick for it to see.
     */
    data class Connected(val name: String, val mtu: Int, val epoch: Long = 0) : LinkState
    data class Reconnecting(val attempt: Int) : LinkState

    /** [otherAddress]: for [LinkError.OtherPiano], the piano that was found, which the person may choose. */
    data class Error(val reason: LinkError, val message: String, val otherAddress: String? = null) : LinkState
}

/**
 * Why a connection failed, so the Piano tab can offer the right fix. [message] is the copy to show;
 * the reasons that carry a number (a GATT status, a scan error) show it, for the person to pass on.
 */
sealed interface LinkError {
    val message: String

    fun toState(otherAddress: String? = null): LinkState.Error = LinkState.Error(this, message, otherAddress)

    /**
     * A search ended without the piano (the only reason a scan's timeout gives). [locationOff]: Location
     * Services were off, which some devices need for Bluetooth scanning even on Android 12 and newer.
     */
    data class NotFound(val locationOff: Boolean = false) : LinkError {
        override val message: String
            get() = if (locationOff) "$NOT_FOUND_COPY $LOCATION_HINT_COPY" else NOT_FOUND_COPY
    }

    /** This device is paired (bonded) with the piano in Bluetooth settings; the piano refuses encryption, so connecting fails. */
    data object Paired : LinkError {
        override val message = "This device is paired with Steven Piano in Bluetooth settings. Forget it there, then tap Retry."
    }

    /** The piano was found, but connecting to it failed twice: [status] is the last GATT status, null when it timed out. */
    data class ConnectFailed(val status: Int?) : LinkError {
        override val message: String
            get() = "Found Steven Piano but the connection failed (${status?.let { "code $it" } ?: "timeout"}). " +
                "Tap Retry; if it keeps failing, restart the piano."
    }

    /** Android would not scan: [code] is the scan's error code (ScanCallback.SCAN_FAILED_*). */
    data class ScanFailed(val code: Int) : LinkError {
        override val message: String
            get() = "Bluetooth scanning isn't available right now (code $code). Turn Bluetooth off and on, then tap Retry."
    }

    data object BluetoothOff : LinkError {
        override val message = "Bluetooth is off. Turn it on to connect to the piano."
    }

    data object PermissionMissing : LinkError {
        override val message = "Steven Piano needs permission to find and connect to the piano."
    }

    data object LocationOff : LinkError {
        override val message = "Location is off. This phone needs it on to find Bluetooth devices."
    }

    data object Unsupported : LinkError {
        override val message = "This phone doesn't support Bluetooth LE, so it can't reach the piano."
    }

    /** Connected, but the piano's BLE-MIDI characteristic never showed up. */
    data object Failed : LinkError {
        override val message = "The connection to Steven Piano failed. Try again."
    }

    data object OtherPiano : LinkError {
        override val message = "Another piano called Steven Piano is nearby, not the one this phone knows. Connect to it only if it's yours."
    }
}

private const val NOT_FOUND_COPY = "Can't find Steven Piano. Make sure it's powered on, within range, and that no iPad, phone " +
    "or Mac is connected to it — it hides while another device is connected."
private const val LOCATION_HINT_COPY = "On some devices, Bluetooth scanning also needs Location turned on."
