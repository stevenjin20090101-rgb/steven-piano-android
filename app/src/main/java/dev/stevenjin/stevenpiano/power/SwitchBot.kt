// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import java.util.Locale
import java.util.UUID

/**
 * One advert of a SwitchBot Plug Mini (v1.36 — M72): the plug's [mac] as the advert carries it ("68:EE:8F:28:47:CA"), its
 * sequence number, whether its switch is [on], its Wi-Fi signal (dBm) and the supply's draw ([watts]).
 */
data class PlugAdvert(val mac: String, val seq: Int, val on: Boolean, val wifiRssi: Int, val watts: Double)

/**
 * The SwitchBot Smart Plug Mini (15 A, Wi-Fi and Bluetooth; v1.36 — M72), as Steven's real plug showed it on 2026-10-31
 * (read from the Mac with bleak): no name, about 3 adverts a second, and a GATT service with no pairing and no encryption.
 *
 * - **Its adverts:** manufacturer data under [COMPANY] (2409), 12 bytes: 0–5 the plug's MAC, 6 a sequence number, 7 bit 7
 *   the switch, 9 the Wi-Fi signal negated, 10–11 the draw ([watts]); and service data under [SERVICE_DATA] whose first
 *   byte is the model, 'g' ([PLUG_MINI_US]) or 'j' ([PLUG_MINI_JP]).
 * - **Its commands:** written with a response to [RX], answered by a notification on [TX]: [STATUS], [ON], [OFF]. A reply's
 *   byte 0 is 0x01 when it took it, and byte 1 bit 7 is the switch now ([reply]).
 */
object SwitchBot {
    /** SwitchBot's (Woan Technology's) Bluetooth company identifier. */
    const val COMPANY = 0x0969

    val SERVICE_DATA: UUID = UUID.fromString("0000fd3d-0000-1000-8000-00805f9b34fb")

    /** The service data's model byte (bit 7 masked): the Plug Mini, US ('g') and JP ('j'). */
    const val PLUG_MINI_US = 0x67
    const val PLUG_MINI_JP = 0x6A

    /** Commands go here (write with a response) … */
    val RX: UUID = UUID.fromString("cba20002-224d-11e6-9fb8-0002a5d5c51b")

    /** … and their replies come back here, as notifications. */
    val TX: UUID = UUID.fromString("cba20003-224d-11e6-9fb8-0002a5d5c51b")

    /** The Client Characteristic Configuration descriptor that turns [TX]'s notifications on. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The manufacturer data's length. */
    const val ADVERT_BYTES = 12

    private val STATUS = bytes(0x57, 0x0f, 0x51, 0x01)
    private val ON = bytes(0x57, 0x0f, 0x50, 0x01, 0x01, 0x80)
    private val OFF = bytes(0x57, 0x0f, 0x50, 0x01, 0x01, 0x00)

    /** The status request: its reply's byte 1 says the switch. */
    fun status(): ByteArray = STATUS.copyOf()

    /** The switch on (ARM) or off (CUT): its reply says the switch now. */
    fun switch(on: Boolean): ByteArray = (if (on) ON else OFF).copyOf()

    /** A reply read: the switch now (true: on), or null when it isn't one the plug took (byte 0 not 0x01, or too short). */
    fun reply(bytes: ByteArray?): Boolean? {
        if (bytes == null || bytes.size < 2 || bytes[0].toInt() and 0xFF != 0x01) return null
        return bytes[1].toInt() and 0x80 != 0
    }

    /** Whether the fd3d service data names a Plug Mini (US or JP). */
    fun isPlugMini(serviceData: ByteArray?): Boolean = model(serviceData) != null

    /** The Plug Mini's model from its fd3d service data: "Plug Mini (US)", "Plug Mini (JP)"; null for anything else. */
    fun model(serviceData: ByteArray?): String? = when (serviceData?.firstOrNull()?.toInt()?.and(0x7F)) {
        PLUG_MINI_US -> "Plug Mini (US)"
        PLUG_MINI_JP -> "Plug Mini (JP)"
        else -> null
    }

    /** The manufacturer data (company 2409's, without its identifier) read; null when it isn't 12 bytes or more. */
    fun advert(manufacturer: ByteArray?): PlugAdvert? {
        if (manufacturer == null || manufacturer.size < ADVERT_BYTES) return null
        fun at(i: Int) = manufacturer[i].toInt() and 0xFF
        return PlugAdvert(
            mac = mac(manufacturer),
            seq = at(6),
            on = at(7) and 0x80 != 0,
            wifiRssi = -at(9),
            watts = watts(at(10), at(11)),
        )
    }

    /**
     * The draw from bytes 10 and 11: `((b10 << 8) | b11) & 0x7FFF`, in tenths of a watt. The formula is pySwitchBot's
     * (`adv_parsers/plug.py`), not yet seen under load on the real plug (it read 0.0 W idle with nothing drawing): the bench
     * confirms it with the 47 Ω dummy load (about 12 W), and this is the one place to change.
     */
    fun watts(b10: Int, b11: Int): Double = (((b10 and 0xFF) shl 8 or (b11 and 0xFF)) and 0x7FFF) / 10.0

    /** The first six bytes as a MAC, "68:EE:8F:28:47:CA". */
    fun mac(bytes: ByteArray): String = (0 until 6).joinToString(":") { String.format(Locale.ROOT, "%02X", bytes[it].toInt() and 0xFF) }

    /** [mac] ("68:EE:8F:28:47:CA", either case) as its six bytes; null when it isn't one. */
    fun macBytes(mac: String): ByteArray? {
        val parts = mac.trim().split(':')
        if (parts.size != 6) return null
        return ByteArray(6) { parts[it].toIntOrNull(16)?.takeIf { b -> b in 0..0xFF }?.toByte() ?: return null }
    }

    /** The MAC's last 4 hex digits, as the Pair screen lists a plug: "47CA". */
    fun lastFour(mac: String): String = mac.filter { it.isLetterOrDigit() }.takeLast(4).uppercase(Locale.ROOT)

    /** Whether [a] and [b] are the same MAC or address, whatever their case. */
    fun same(a: String?, b: String?): Boolean = a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
