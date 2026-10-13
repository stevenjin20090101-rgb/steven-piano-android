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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The piano's side of an update session (BLE_OTA.md › 5–6) as a model, for the emulator's stand-in
 * link and for tests: fed what the app writes to Control and Data, it answers as the firmware
 * would. BEGIN's checks (length, size, window, version; the signature against [Script.publicKey]
 * when one is given), READY with the MTU's chunk and the window asked for, a sequence number and
 * length check on every Data frame, an ACK after each full window and the last partial one, END
 * with every byte, then VERIFYING and OK when the SHA-256 of the bytes received is BEGIN's (ERR 5
 * otherwise); ABORT answered ABORTED until END, ignored after it. [Script] makes it fail on
 * purpose. From firmware 2.1.0 (v1.25 — M58, BLE_OTA.md › 5's ERR table): a piano whose Bluetooth
 * lock doesn't trust the connection refuses BEGIN with ERR 11, and one running the version tag's
 * checks ([Script.runningVersion]) refuses with ERR 12 a BEGIN whose version is not newer, and after
 * END (VERIFYING first) an image whose signed tag (`SJPV1:<version>`, NUL ended) is missing or not
 * newer: § 5's worked example, which has no tag, then ends `82 · E1 0C`, and the same image tagged
 * ([dev.stevenjin.stevenpiano.firmware.OtaExample.taggedImage]) `82 · 83 DC 05`. It never touches a
 * real piano; not thread-safe.
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
        /** The link drops right after END, before VERIFYING: the piano verifies and restarts without the app. */
        val dropAfterEnd: Boolean = false,
        val restartInMs: Int = 1_500,
        /** Whether the piano's Bluetooth lock trusts this connection (2.1.0); ERR 11 at BEGIN when it doesn't. */
        val authorised: () -> Boolean = { true },
        /**
         * The version a piano with 2.1.0's version checks runs (its `FW_VERSION`); null for 2.0.x, which has none: then
         * BEGIN's version and the image's tag are never weighed.
         */
        val runningVersion: String? = null,
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

    /** The bytes received, kept for the version tag's scan when the piano checks it ([Script.runningVersion]). */
    private val kept = ByteArrayOutputStream()

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
        if (script.runningVersion != null) kept.write(frame, OtaFrames.DATA_HEADER, length)
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
        if (!script.authorised()) return error(NOT_AUTHORISED)
        script.runningVersion?.let { running -> if (!newer(String(version, Charsets.US_ASCII), running)) return error(NOT_NEWER) }
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
        kept.reset()
        state = State.Receiving
        return listOf(notify(OtaFrames.READY, byteArrayOf((chunk and 0xFF).toByte(), (chunk ushr 8).toByte(), window.toByte())))
    }

    private fun end(): List<Out> {
        if (state != State.Receiving) return emptyList()
        if (received != size) return error(4)
        state = State.Verifying
        val verifying = notify(OtaFrames.VERIFYING)
        val good = !script.hashMismatch && digest.digest().contentEquals(expected)
        if (script.dropAfterEnd) return if (good) listOf(Out.Drop, Out.Restart(script.restartInMs)) else listOf(Out.Drop)
        if (!good) return listOf(verifying) + error(5)
        script.runningVersion?.let { running ->
            val tag = tagOf(kept.toByteArray())
            if (tag == null || !newer(tag, running)) return listOf(verifying) + error(NOT_NEWER)
        }
        val ms = script.restartInMs
        return listOf(verifying, notify(OtaFrames.OK, byteArrayOf((ms and 0xFF).toByte(), (ms ushr 8).toByte())), Out.Restart(ms))
    }

    private fun error(code: Int): List<Out> {
        state = State.Idle
        return listOf(notify(OtaFrames.ERR, byteArrayOf(code.toByte())))
    }

    private fun notify(opcode: Int, rest: ByteArray = ByteArray(0)) = Out.Notify(byteArrayOf(opcode.toByte()) + rest)

    private fun u32(value: Long) = ByteArray(4) { ((value ushr (8 * it)) and 0xFF).toByte() }

    companion object {
        private const val DEFAULT_MTU = 255

        /** app0 / app1 in default_16MB.csv: 0x640000 bytes. */
        private const val SLOT_BYTES = 6_553_600L
        private val VERSION_CHARS: Set<Char> = (('0'..'9') + ('A'..'Z') + ('a'..'z') + listOf('.', '+', '-')).toSet()

        /** ERR 11 and 12 (firmware 2.1.0). */
        private const val NOT_AUTHORISED = 11
        private const val NOT_NEWER = 12

        /** The version tag's marker; the tag is the marker, 1..16 of [VERSION_CHARS], then a NUL. */
        private val MARKER = "SJPV1:".toByteArray(Charsets.US_ASCII)
        private const val TAG_MAX = 16

        /** The version tag as the firmware builds it into every image from 2.1.0: `SJPV1:` [version], NUL ended. */
        fun tag(version: String): ByteArray = MARKER + version.toByteArray(Charsets.US_ASCII) + 0

        /** The first well-formed version tag in [image] (BLE_OTA.md › 5: a decoy before it doesn't count), or null. */
        fun tagOf(image: ByteArray): String? {
            var from = 0
            while (true) {
                val at = indexOf(image, MARKER, from)
                if (at < 0) return null
                val start = at + MARKER.size
                var end = start
                while (end < image.size && end - start < TAG_MAX && (image[end].toInt() and 0xFF).toChar() in VERSION_CHARS) end++
                if (end > start && end < image.size && image[end] == 0.toByte()) return String(image, start, end - start, Charsets.US_ASCII)
                from = at + 1
            }
        }

        /**
         * Whether [version] is newer than [running], as the firmware weighs it: MAJOR.MINOR.PATCH as numbers, a "-…" or
         * "+…" suffix ignored; equal is not newer, and nothing that doesn't read is.
         */
        fun newer(version: String, running: String): Boolean {
            val a = numbers(version) ?: return false
            val b = numbers(running) ?: return false
            for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }

        private fun numbers(version: String): List<Long>? {
            val core = version.trim().substringBefore('+').substringBefore('-').split('.')
            if (core.size != 3) return null
            return core.map { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        }

        private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in from..bytes.size - needle.size) {
                for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
