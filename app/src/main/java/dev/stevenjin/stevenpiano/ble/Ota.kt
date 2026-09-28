// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * How the piano offers firmware updates over Bluetooth (`firmware/docs/BLE_OTA.md` › 3), and its
 * version (› 2): firmware 2.0.0 and later have both; older firmware has neither.
 */
object PianoOta {
    /** The update service. */
    val SERVICE_UUID: UUID = UUID.fromString("7D0A0001-3F5E-4B8C-9A2D-6E1F0C4B8A17")

    /** App to piano: BEGIN, END, ABORT, written with response; piano to app: its answers, as notifications. */
    val CONTROL_UUID: UUID = UUID.fromString("7D0A0002-3F5E-4B8C-9A2D-6E1F0C4B8A17")

    /** App to piano: the image, frame by frame, written without response. */
    val DATA_UUID: UUID = UUID.fromString("7D0A0003-3F5E-4B8C-9A2D-6E1F0C4B8A17")

    /** Device Information (0x180A) and its Firmware Revision String (0x2A26): "2.0.0+a1b2c3d". */
    val DEVICE_INFORMATION_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    val FIRMWARE_REVISION_UUID: UUID = UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB")

    /** The longest version string kept: "2.0.0+a1b2c3d-dirty" is 19 characters. */
    const val MAX_VERSION_CHARS = 64

    /**
     * The Firmware Revision String as text: UTF-8, NUL padding and control characters dropped, at
     * most [MAX_VERSION_CHARS]; null when nothing is left.
     */
    fun version(value: ByteArray?): String? {
        if (value == null) return null
        val text = String(value, Charsets.UTF_8).filter { !it.isISOControl() }.trim().take(MAX_VERSION_CHARS)
        return text.ifEmpty { null }
    }
}

/**
 * BEGIN's fields (BLE_OTA.md › 5): the image's [size] and SHA-256, the Ed25519 signature of those
 * 32 bytes, the image's `FW_VERSION`, and the Data frames the app would send per ACK ([window]).
 * Checked as it is made, as the piano would check it.
 */
class OtaBegin(val size: Long, sha256: ByteArray, signature: ByteArray, val version: String, val window: Int) {
    val sha256: ByteArray = sha256.copyOf()
    val signature: ByteArray = signature.copyOf()

    init {
        require(size in 1..MAX_SIZE) { "size $size" }
        require(sha256.size == 32) { "a SHA-256 is 32 bytes" }
        require(signature.size == 64) { "an Ed25519 signature is 64 bytes" }
        val bytes = version.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..OtaFrames.VERSION_BYTES && version.all { it in VERSION_CHARS }) { "version \"$version\"" }
        require(window in 1..OtaFrames.MAX_WINDOW) { "window $window" }
    }

    private companion object {
        const val MAX_SIZE = 0xFFFF_FFFFL
        val VERSION_CHARS: Set<Char> = (('0'..'9') + ('A'..'Z') + ('a'..'z') + listOf('.', '+', '-')).toSet()
    }
}

/** What the piano answers on Control, read ([OtaFrames.parse]), or how a session ended without its word. */
sealed interface OtaEvent {
    /** Safety done, slot open: Data frames of up to [maxChunk] bytes, an ACK every [window] of them. */
    data class Ready(val maxChunk: Int, val window: Int) : OtaEvent

    /** [bytesReceived] bytes hashed and handed to flash: after each full window, and the last partial one. */
    data class Ack(val bytesReceived: Long) : OtaEvent

    /** END taken: the piano is checking the image. */
    data object Verifying : OtaEvent

    /** Verified and made the boot image: the piano restarts in [restartInMs]. */
    data class Ok(val restartInMs: Int) : OtaEvent

    /** ABORT honoured: the slot is discarded, the running firmware unchanged. */
    data object Aborted : OtaEvent

    /** Refused or failed, the session over and the slot discarded ([code]: BLE_OTA.md › 5's table). */
    data class Error(val code: Int) : OtaEvent

    /** An answer the app can't read: a known opcode of the wrong length ([hex]). The session can't go on. */
    data class Malformed(val hex: String) : OtaEvent

    /** The session ended without the piano's word: the link went, or a write to it failed ([reason]). */
    data class Lost(val reason: String) : OtaEvent

    /** Whether the session is over with this event. */
    val ends: Boolean get() = this is Ok || this is Aborted || this is Error || this is Lost
}

/**
 * The update's frames, byte for byte (BLE_OTA.md › 5): little-endian, the opcode first on Control;
 * Data frames are a 16-bit sequence number and the payload. Pure, so the worked example checks it.
 */
object OtaFrames {
    const val BEGIN: Int = 0x01
    const val END: Int = 0x02
    const val ABORT: Int = 0x03
    const val READY: Int = 0x81
    const val VERIFYING: Int = 0x82
    const val OK: Int = 0x83
    const val ABORTED: Int = 0x84
    const val ACK: Int = 0x85
    const val ERR: Int = 0xE1

    /** BEGIN: opcode, size u32, sha256[32], sig[64], version[16], window u8. */
    const val BEGIN_BYTES = 118
    const val VERSION_BYTES = 16

    /** The window the app asks for: 16 frames of 250 bytes, about one flash sector. */
    const val WINDOW = 16
    const val MAX_WINDOW = 32

    /** The piano's cap on a Data payload, whatever the MTU (Data's maximum length is 252). */
    const val MAX_CHUNK = 250

    /** A Data frame's sequence number. */
    const val DATA_HEADER = 2

    /** ATT's own header on a write. */
    const val ATT_HEADER = 3

    /** The largest payload a Data frame carries at [mtu]: min(MTU − 5, 250); 250 at NimBLE's 255, 18 at ATT's default 23. */
    fun maxChunk(mtu: Int): Int = (mtu - ATT_HEADER - DATA_HEADER).coerceIn(0, MAX_CHUNK)

    fun begin(header: OtaBegin): ByteArray {
        val out = ByteArray(BEGIN_BYTES)
        out[0] = BEGIN.toByte()
        putU32(out, 1, header.size)
        header.sha256.copyInto(out, 5)
        header.signature.copyInto(out, 37)
        header.version.toByteArray(Charsets.UTF_8).copyInto(out, 101)   // zero-padded to 16
        out[117] = header.window.toByte()
        return out
    }

    fun end(): ByteArray = byteArrayOf(END.toByte())

    fun abort(): ByteArray = byteArrayOf(ABORT.toByte())

    /** Data frame [seq] (taken modulo 65,536) carrying [length] bytes of [image] from [offset]. */
    fun data(seq: Int, image: ByteArray, offset: Int = 0, length: Int = image.size - offset): ByteArray {
        val out = ByteArray(DATA_HEADER + length)
        out[0] = (seq and 0xFF).toByte()
        out[1] = ((seq ushr 8) and 0xFF).toByte()
        image.copyInto(out, DATA_HEADER, offset, offset + length)
        return out
    }

    /**
     * The piano's answer in [bytes], or null for an opcode this app doesn't know (a later firmware's
     * compatible addition: ignored). A known opcode of the wrong length is [OtaEvent.Malformed].
     */
    fun parse(bytes: ByteArray): OtaEvent? {
        if (bytes.isEmpty()) return null
        fun wrongLength() = OtaEvent.Malformed(hex(bytes))
        return when (bytes[0].toInt() and 0xFF) {
            READY -> if (bytes.size == 4) OtaEvent.Ready(u16(bytes, 1), bytes[3].toInt() and 0xFF) else wrongLength()
            ACK -> if (bytes.size == 5) OtaEvent.Ack(u32(bytes, 1)) else wrongLength()
            VERIFYING -> if (bytes.size == 1) OtaEvent.Verifying else wrongLength()
            OK -> if (bytes.size == 3) OtaEvent.Ok(u16(bytes, 1)) else wrongLength()
            ABORTED -> if (bytes.size == 1) OtaEvent.Aborted else wrongLength()
            ERR -> if (bytes.size == 2) OtaEvent.Error(bytes[1].toInt() and 0xFF) else wrongLength()
            else -> null
        }
    }

    /** An ERR code's name, for the link's log (BLE_OTA.md › 5). */
    fun errorName(code: Int): String = when (code) {
        1 -> "not quiet"
        2 -> "too large"
        3 -> "already updating"
        4 -> "bad header or sequence gap"
        5 -> "hash mismatch"
        6 -> "bad signature"
        7 -> "flash write failed"
        8 -> "image invalid"
        9 -> "timeout"
        10 -> "refused by safety"
        else -> "unknown"
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun putU32(out: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) out[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }
}

/**
 * The piano's update service on this connection (BLE_OTA.md › 3–6), while the link is connected to a
 * piano whose firmware has it ([PianoLink.ota]). One session at a time: [begin], then the Data
 * frames a window at a time, each window answered by an ACK before the next, then [end]. Safe to
 * call from any thread.
 */
interface OtaChannel {
    /** The largest Data payload this connection's MTU allows: min(MTU − 5, 250). READY's own figure, never above this, is what a transfer uses. */
    val maxChunk: Int

    /** The Data frames per ACK the app asks for ([OtaFrames.WINDOW]); READY may lower it. */
    val window: Int

    /**
     * One update session: switches on Control's notifications (once a connection), asks for high
     * connection priority, writes [header] as BEGIN, and gives the piano's answers as they come
     * until the session ends ([OtaEvent.ends]): OK, ABORTED, an ERR, or [OtaEvent.Lost] when the
     * link goes. Stopping the collection before [end] sends ABORT.
     */
    fun begin(header: OtaBegin): Flow<OtaEvent>

    /**
     * Queues Data frame [seq] (the frame's index since READY; taken modulo 65,536) carrying
     * [payload] (1 to [maxChunk] bytes). False when it can't: no session, END already sent, a
     * payload of the wrong size, or too many frames waiting.
     */
    fun write(seq: Int, payload: ByteArray): Boolean

    /** Every byte sent: queues END. From here the piano verifies and restarts; ABORT is no longer heard. */
    fun end()

    /** Stops the session: frames still waiting are dropped and ABORT goes (nothing after [end]). */
    fun abort()
}
