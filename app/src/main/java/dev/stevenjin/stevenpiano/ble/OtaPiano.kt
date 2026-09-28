// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.firmware.Ed25519
import java.security.MessageDigest

/**
 * The piano's side of an update session (BLE_OTA.md › 5–6) as a model, for the emulator's stand-in
 * link and for tests: fed what the app writes to Control and Data, it answers as the firmware
 * would. BEGIN's checks (length, size, window, version; the signature against [Script.publicKey]
 * when one is given), READY with the MTU's chunk and the window asked for, a sequence number and
 * length check on every Data frame, an ACK after each full window and the last partial one, END
 * with every byte, then VERIFYING and OK when the SHA-256 of the bytes received is BEGIN's (ERR 5
 * otherwise); ABORT answered ABORTED until END, ignored after it. [Script] makes it fail on
 * purpose. It never touches a real piano; not thread-safe.
 */
class OtaPiano(private val mtu: Int = DEFAULT_MTU, private val script: Script = Script()) {
    /** How this piano fails, if at all. */
    data class Script(
        /** An ERR instead of READY (1: not quiet, 3: already updating, 10: refused by safety). */
        val errorAtBegin: Int? = null,
        /** When set, BEGIN's signature is checked against it (ERR 6 otherwise), as the piano does. */
        val publicKey: ByteArray? = null,
        /** The link drops after this many ACKs (a disconnect mid-transfer). */
        val dropAfterAcks: Int? = null,
        /** ERR [second] after [first] ACKs. */
        val errorAfterAcks: Pair<Int, Int>? = null,
        /** ERR 5 after END whatever arrived (the image changed on the way). */
        val hashMismatch: Boolean = false,
        val restartInMs: Int = 1_500,
    )

    /** What the piano does in answer. */
    sealed interface Out {
        /** A notification on Control. */
        class Notify(val bytes: ByteArray) : Out {
            override fun toString(): String = OtaFrames.hex(bytes)
        }

        /** The link goes (the scripted disconnect). */
        data object Drop : Out

        /** After OK: the piano restarts in [inMs] (it drops the link itself). */
        data class Restart(val inMs: Int) : Out
    }

    private enum class State { Idle, Receiving, Verifying }

    private var state = State.Idle
    private var size = 0L
    private var expected = ByteArray(0)
    private var window = OtaFrames.WINDOW
    private var chunk = OtaFrames.maxChunk(mtu)
    private var seq = 0
    private var received = 0L
    private var inWindow = 0
    private var acks = 0
    private val digest = MessageDigest.getInstance("SHA-256")

    /** Bytes received in the current (or last) session. */
    val bytesReceived: Long get() = received

    /** A write to Control. */
    fun control(frame: ByteArray): List<Out> {
        if (frame.isEmpty()) return error(4)
        return when (frame[0].toInt() and 0xFF) {
            OtaFrames.BEGIN -> begin(frame)
            OtaFrames.END -> end()
            OtaFrames.ABORT -> when (state) {
                State.Verifying -> emptyList()   // END is the point of no return
                else -> {
                    state = State.Idle
                    listOf(notify(OtaFrames.ABORTED))
                }
            }
            else -> error(4)
        }
    }

    /** A write to Data. Outside RECEIVING it is a stale or foreign frame: no reply. */
    fun data(frame: ByteArray): List<Out> {
        if (state != State.Receiving) return emptyList()
        if (frame.size < OtaFrames.DATA_HEADER + 1) return error(4)
        val frameSeq = (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8)
        val length = frame.size - OtaFrames.DATA_HEADER
        if (frameSeq != (seq and 0xFFFF) || length > chunk || received + length > size) return error(4)
        digest.update(frame, OtaFrames.DATA_HEADER, length)
        received += length
        seq++
        inWindow++
        if (inWindow < window && received < size) return emptyList()
        inWindow = 0
        acks++
        val out = mutableListOf<Out>(notify(OtaFrames.ACK, u32(received)))
        if (script.dropAfterAcks == acks) {
            state = State.Idle
            out += Out.Drop
        }
        script.errorAfterAcks?.let { (after, code) -> if (after == acks) return out + error(code) }
        return out
    }

    private fun begin(frame: ByteArray): List<Out> {
        if (state != State.Idle) return error(3)
        if (frame.size != OtaFrames.BEGIN_BYTES) return error(4)
        val requestedSize = (0 until 4).fold(0L) { acc, i -> acc or ((frame[1 + i].toLong() and 0xFF) shl (8 * i)) }
        val sha = frame.copyOfRange(5, 37)
        val sig = frame.copyOfRange(37, 101)
        val version = frame.copyOfRange(101, 117).takeWhile { it != 0.toByte() }.toByteArray()
        val requestedWindow = frame[117].toInt() and 0xFF
        val versionOk = version.isNotEmpty() && version.all { it.toInt().toChar() in VERSION_CHARS }
        if (requestedSize == 0L || requestedWindow !in 1..OtaFrames.MAX_WINDOW || !versionOk) return error(4)
        script.errorAtBegin?.let { return error(it) }
        if (requestedSize > SLOT_BYTES) return error(2)
        script.publicKey?.let { if (!Ed25519.verify(it, sha, sig, platformFirst = false)) return error(6) }
        size = requestedSize
        expected = sha
        window = requestedWindow
        chunk = OtaFrames.maxChunk(mtu)
        seq = 0
        received = 0
        inWindow = 0
        acks = 0
        digest.reset()
        state = State.Receiving
        return listOf(notify(OtaFrames.READY, byteArrayOf((chunk and 0xFF).toByte(), (chunk ushr 8).toByte(), window.toByte())))
    }

    private fun end(): List<Out> {
        if (state != State.Receiving) return emptyList()
        if (received != size) return error(4)
        state = State.Verifying
        val verifying = notify(OtaFrames.VERIFYING)
        if (script.hashMismatch || !digest.digest().contentEquals(expected)) return listOf(verifying) + error(5)
        val ms = script.restartInMs
        return listOf(verifying, notify(OtaFrames.OK, byteArrayOf((ms and 0xFF).toByte(), (ms ushr 8).toByte())), Out.Restart(ms))
    }

    private fun error(code: Int): List<Out> {
        state = State.Idle
        return listOf(notify(OtaFrames.ERR, byteArrayOf(code.toByte())))
    }

    private fun notify(opcode: Int, rest: ByteArray = ByteArray(0)) = Out.Notify(byteArrayOf(opcode.toByte()) + rest)

    private fun u32(value: Long) = ByteArray(4) { ((value ushr (8 * it)) and 0xFF).toByte() }

    private companion object {
        const val DEFAULT_MTU = 255

        /** app0 / app1 in default_16MB.csv: 0x640000 bytes. */
        const val SLOT_BYTES = 6_553_600L
        val VERSION_CHARS: Set<Char> = (('0'..'9') + ('A'..'Z') + ('a'..'z') + listOf('.', '+', '-')).toSet()
    }
}
