// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The emulator's fake plug ([EmulatedShelly]) over Bluetooth for tests, at once (a locked plug's silence takes the caller's
 * whole time): every request's method is in [methods] (Android's bond as "bond"), its frame in [frames], and what the plug
 * was at it in [seen]. A refusal is the GATT one, cleared at the next connection as Android's is. Moved out of
 * `PairFlowTest` in v1.32 (M70), for the plug watch's tests too.
 */
class FakePlugTransport(private val shelly: EmulatedShelly) : PlugTransport {
    private var up = false
    val methods = mutableListOf<String>()
    val frames = mutableListOf<String>()

    /** At each request: the plug's setup state, and whether this tablet was bonded with it. */
    val seen = mutableListOf<Pair<Provision, Boolean>>()
    override var refused: Boolean = false
    override var reached: Boolean = false

    override fun blocker(): String? = null

    override suspend fun scan(timeoutMs: Long, found: (FoundPlug) -> Unit): String? {
        found(FoundPlug(EmulatedShelly.ADDRESS, EmulatedShelly.NAME, -60))
        return null
    }

    override suspend fun connect(address: String): Boolean {
        refused = false
        up = !shelly.rebooting()
        reached = up
        return up
    }

    override val connected: Boolean get() = up && !shelly.rebooting()

    override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
        if (!connected) return null
        val text = String(frame, Charsets.UTF_8)
        methods += JSONObject(text).getString("method")
        frames += text
        seen += shelly.provisionNow() to shelly.bonded(EmulatedShelly.TABLET)
        return when (val answer = shelly.handle(EmulatedShelly.TABLET, text)) {
            is EmulatedShelly.Answer.Reply -> answer.json.toByteArray(Charsets.UTF_8)
            EmulatedShelly.Answer.Refused -> {
                refused = true
                null
            }
            EmulatedShelly.Answer.Silent -> {
                delay(timeoutMs)
                null
            }
        }
    }

    override fun disconnect() {
        up = false
    }

    override suspend fun bond(address: String, timeoutMs: Long): Boolean = shelly.bond(EmulatedShelly.TABLET).also { methods += "bond" }

    override fun bonded(address: String): Boolean = shelly.bonded(EmulatedShelly.TABLET)
}
