// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The advert watch (v1.36 — M72; safety logic): while a plug is paired, a scan for its adverts runs for the life of the
 * process ([run]), low-power while the rail is off and fast while it is up. Each advert says whether the plug's switch is on
 * (the keeper CUTs one that is on while nothing wants the 24 V: [RailKeeper.advert]) and what the supply draws, the power
 * watch's reading ([watts]: from an advert under [FRESH_MS] old, else unread, as an unanswered plug was; [SILENT_MS] without
 * one is logged once). [seen] is the plug as the screens show it: its last advert, or null once none came for [SEEN_MS].
 * [now] is a monotonic clock, ms. Any thread.
 */
class PlugAdverts(private val now: () -> Long, private val log: (String) -> Unit = {}) {
    private class Heard(val advert: PlugAdvert, val at: Long)

    @Volatile
    private var last: Heard? = null

    @Volatile
    private var silentSaid = false

    private val _seen = MutableStateFlow<PlugAdvert?>(null)

    /** The plug as its last advert said, or null when none came for [SEEN_MS] (or no plug is paired). */
    val seen: StateFlow<PlugAdvert?> = _seen.asStateFlow()

    /** An advert of the paired plug, heard now. */
    fun heard(advert: PlugAdvert) {
        last = Heard(advert, now())
        silentSaid = false
        _seen.value = advert
    }

    /**
     * The supply's draw from the latest advert, W, when it is under [FRESH_MS] old; else null, unread. The first look after
     * [SILENT_MS] without an advert says so in the log, once until they come back.
     */
    fun watts(t: Long = now()): Double? {
        val heard = last ?: return null
        val age = t - heard.at
        if (age > SILENT_MS && !silentSaid) {
            silentSaid = true
            log("Plug: no advert for ${age / MS_PER_S} s: the supply's draw reads as unread until they come back")
        }
        return heard.advert.watts.takeIf { age <= FRESH_MS && it.isFinite() && it >= 0.0 }
    }

    /** When the latest advert came ([now]'s clock); null before one. */
    fun lastAt(): Long? = last?.at

    /** [seen] goes null once no advert came for [SEEN_MS]. */
    fun expire(t: Long = now()) {
        val heard = last
        if (heard == null || t - heard.at > SEEN_MS) _seen.value = null
    }

    /** No plug: nothing heard. */
    fun clear() {
        last = null
        _seen.value = null
    }

    /**
     * The watch of the plug at [address] whose adverts carry [mac], [fast] or low-power, until cancelled: the transport's
     * scan, started again [RETRY_MS] after it couldn't (said once per reason); each advert of that plug to [heard], then to
     * [onAdvert]. [seen] expires meanwhile.
     */
    suspend fun run(transport: PlugTransport, address: String, mac: String?, fast: Boolean, onAdvert: (PlugAdvert) -> Unit) = coroutineScope {
        val ticker = launch {
            while (true) {
                delay(TICK_MS)
                expire()
            }
        }
        var said: String? = null
        try {
            while (true) {
                val why = transport.watch(address, mac, fast) { advert ->
                    if (mac == null || SwitchBot.same(advert.mac, mac)) {
                        heard(advert)
                        onAdvert(advert)
                    }
                }
                if (why != said) log("Plug: the advert watch couldn't scan: $why")
                said = why
                delay(RETRY_MS)
            }
        } finally {
            ticker.cancel()
        }
    }

    companion object {
        /** A reading this old at most is the supply's draw now (the brief: 3 s). */
        const val FRESH_MS = 3_000L

        /** No advert for this long while the rail is up: logged, and the draw unread (as an unanswered plug was). */
        const val SILENT_MS = 10_000L

        /** No advert for this long: the plug reads "not seen", and Power use's Now "—". */
        const val SEEN_MS = 10_000L

        const val TICK_MS = 1_000L
        const val RETRY_MS = 30_000L
        private const val MS_PER_S = 1_000L
    }
}
