// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The emulator's fake SwitchBot ([EmulatedSwitchBot]) over Bluetooth for tests (v1.36 — M72), at once (a silent plug takes
 * the caller's whole time): every command's frame in [frames] as hex, every connection counted in [connects]. A search
 * finds the plug and [others]; the advert watch hears what [advertise] sends while it runs. [away]: out of range.
 */
class FakePlugTransport(private val plug: EmulatedSwitchBot) : PlugTransport {
    private var up = false
    private var listener: ((PlugAdvert) -> Unit)? = null
    val frames = mutableListOf<String>()
    var connects = 0
    var away = false

    /** Other plugs a search finds beside this one. */
    val others = mutableListOf<FoundPlug>()

    /** Whether the advert watch's scan runs now, and how ([fast]). */
    val watching: Boolean get() = listener != null
    var fast: Boolean? = null

    override fun blocker(): String? = null

    override suspend fun scan(timeoutMs: Long, found: (FoundPlug) -> Unit): String? {
        if (!away) found(FoundPlug(EmulatedSwitchBot.ADDRESS, plug.mac, SwitchBot.model(plug.serviceData())!!, RSSI))
        others.forEach(found)
        return null
    }

    override suspend fun watch(address: String, mac: String?, fast: Boolean, heard: (PlugAdvert) -> Unit): String {
        listener = heard
        this.fast = fast
        try {
            awaitCancellation()
        } finally {
            listener = null
        }
    }

    /** The plug's advert as it stands now, to the watch (none while away). */
    fun advertise() {
        if (!away) listener?.invoke(SwitchBot.advert(plug.manufacturer())!!)
    }

    override suspend fun connect(address: String): Boolean {
        connects++
        up = !away && address.equals(EmulatedSwitchBot.ADDRESS, ignoreCase = true)
        return up
    }

    override val connected: Boolean get() = up && !away

    override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
        if (!connected) return null
        frames += frame.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
        val reply = plug.handle(frame)
        if (reply == null) delay(timeoutMs)
        return reply
    }

    override fun disconnect() {
        up = false
    }

    companion object {
        const val RSSI = -60
    }
}
