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
 * The SwitchBot Plug Mini as the emulator's fake plug answers it (v1.36 — M72; debug builds only), for the emulator's
 * stand-in and for tests, as Steven's real plug showed it ([SwitchBot]):
 *
 * - **Its commands:** the status, on and off frames, each answered `01 80` (on) or `01 00` (off); anything else `05`, a
 *   reply it didn't take. [silent] answers nothing (a plug out of its GATT's reach).
 * - **Its adverts** ([manufacturer], [serviceData]): its MAC, a sequence number, the switch, a Wi-Fi signal and the draw:
 *   [IDLE_WATTS] while the 24 V supply is up, plus [extraWatts] (the power watch's debug path raises it: a coil stuck on is
 *   12 W), 0 while it is off.
 * - **No timer, on at power-up:** it comes on by itself when plugged in ([powerCut]), and the SwitchBot app can switch it
 *   ([switchOnFromOutside]).
 * - [weld] keeps the relay closed whatever is asked (the switch reads off, the supply still draws): the welded relay.
 *
 * [clock] is the time its relay closed by. Thread-safe: every call is synchronized.
 */
class EmulatedSwitchBot(private val clock: () -> Long = System::currentTimeMillis, val mac: String = MAC, on: Boolean = true) {
    private var output = on
    private var onSince: Long? = if (on) clock() else null
    private var seq = 0

    /** The relay stuck closed: a CUT reads off but the supply stays up. */
    @Volatile
    var weld: Boolean = false

    /** What the supply draws beyond its idle while it is up, W (a coil stuck on: 12). */
    @Volatile
    var extraWatts: Double = 0.0

    /** Its GATT answers nothing. */
    @Volatile
    var silent: Boolean = false

    /** Every command it answered, in order: "status", "on", "off". */
    val commands = mutableListOf<String>()

    /** One command's frame and its reply; null when it answers nothing. */
    @Synchronized
    fun handle(frame: ByteArray): ByteArray? {
        if (silent) return null
        val word = when {
            frame.contentEquals(SwitchBot.status()) -> "status"
            frame.contentEquals(SwitchBot.switch(true)) -> "on".also { set(true) }
            frame.contentEquals(SwitchBot.switch(false)) -> "off".also { set(false) }
            else -> return byteArrayOf(0x05)
        }
        commands += word
        return byteArrayOf(0x01, if (output) 0x80.toByte() else 0x00)
    }

    /** Its switch as it says it. */
    @Synchronized
    fun output(): Boolean = output

    /** Someone with the SwitchBot app switched it on. */
    @Synchronized
    fun switchOnFromOutside() = set(true)

    /** Its mains cut and back: it comes on by itself, as the real one did. */
    @Synchronized
    fun powerCut() {
        output = true
        onSince = clock()
    }

    /** Whether the 24 V supply on it is up (on, or welded closed since it was). */
    @Synchronized
    fun powered(): Boolean = output || weld && onSince != null

    /** Since when the supply has been up; null while it isn't. */
    @Synchronized
    fun poweredSince(): Long? = if (powered()) onSince else null

    /** What the supply draws now, W. */
    @Synchronized
    fun watts(): Double = if (powered()) IDLE_WATTS + extraWatts else 0.0

    /** Its manufacturer data (company 2409's 12 bytes), the next sequence number each time. */
    @Synchronized
    fun manufacturer(): ByteArray {
        val bytes = SwitchBot.macBytes(mac) ?: ByteArray(6)
        val tenths = (watts() * 10).toInt().coerceIn(0, 0x7FFF)
        seq = (seq + 1) and 0xFF
        return bytes + byteArrayOf(seq.toByte(), if (output) 0x80.toByte() else 0x00, 0x00, WIFI_RSSI.toByte(), (tenths shr 8).toByte(), tenths.toByte())
    }

    /** Its fd3d service data: a Plug Mini (US). */
    fun serviceData(): ByteArray = byteArrayOf(SwitchBot.PLUG_MINI_US.toByte(), 0x00, 0x64)

    private fun set(on: Boolean) {
        if (on && !output && !(weld && onSince != null)) onSince = clock()
        output = on
        if (!on && !weld) onSince = null
    }

    companion object {
        /** The fake plug's Bluetooth address and advert MAC (the same here), and Steven's real plug's MAC ends …47CA too. */
        const val ADDRESS = "68:EE:8F:28:47:CA"
        const val MAC = ADDRESS
        const val IDLE_WATTS = 6.4
        private const val WIFI_RSSI = 58
    }
}
