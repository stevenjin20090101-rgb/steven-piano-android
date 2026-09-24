// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import kotlinx.coroutines.flow.SharedFlow
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * The piano's text console over Bluetooth: the Nordic UART Service the firmware runs beside
 * BLE-MIDI on the same connection (`firmware/docs/BLE_SETTINGS.md`). Console commands go in as
 * lines; the replies come back as lines. It exists only while the link is connected to a piano
 * that offers it, so [PianoLink.console] is null otherwise. Safe to call from any thread.
 */
interface ConsoleChannel {
    /**
     * Sends [text] as one console command; "\n" is added. At most [MAX_LINE] characters and no
     * line breaks, or it is not sent. Queued behind any MIDI waiting to go, never ahead of it.
     */
    fun sendLine(text: String)

    /** The piano's reply lines, reassembled on "\n", without the "> command" echo. Nothing is replayed. */
    val lines: SharedFlow<String>

    companion object {
        /** The firmware's line buffer is 80 bytes, its terminator included. */
        const val MAX_LINE = 79
    }
}

/** How the console shows itself: the Nordic UART Service and its two characteristics. */
object PianoConsole {
    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** App to piano: WRITE and WRITE_NR. */
    val RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Piano to app: NOTIFY, switched on through its CCCD. */
    val TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** The Client Characteristic Configuration Descriptor (0x2902). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** The bytes of one command line with its "\n", or null when [text] is too long or holds a line break. */
    fun encode(text: String): ByteArray? {
        if (text.any { it == '\n' || it == '\r' }) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > ConsoleChannel.MAX_LINE) return null
        return bytes + NEWLINE
    }

    private const val NEWLINE = '\n'.code.toByte()
}

/**
 * Turns the piano's notifications back into lines. A notification may end mid-line, even
 * mid-character (the firmware writes "—" and "→"), so bytes gather until "\n" and only then
 * decode as UTF-8. A trailing "\r" (Arduino's println) is dropped, and so is the firmware's
 * "> command" echo. A line longer than [maxBytes] keeps its first [maxBytes] bytes.
 */
class ConsoleLineAssembler(private val maxBytes: Int = 1024) {
    private val pending = ByteArrayOutputStream()

    fun feed(chunk: ByteArray, emit: (String) -> Unit) {
        for (byte in chunk) {
            if (byte == LF) {
                val line = String(pending.toByteArray(), Charsets.UTF_8).removeSuffix("\r")
                pending.reset()
                if (!line.startsWith(ECHO)) emit(line)
            } else if (pending.size() < maxBytes) {
                pending.write(byte.toInt())
            }
        }
    }

    /** Forgets a partial line (the connection went). */
    fun clear() = pending.reset()

    private companion object {
        const val LF = '\n'.code.toByte()
        const val ECHO = "> "
    }
}
