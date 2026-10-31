// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Pair screen's steps (v1.36 — M72): find the SwitchBot plugs nearby, tap one for its on-and-off check, then paired. */
enum class PairStep {
    /** 1. A 15 s scan for SwitchBot Plug Minis, listed by signal with the MAC's last 4 digits. */
    Find,

    /** 2. The one tapped: connected, off, read off, on, read on, off. */
    Check,

    /** 3. It clicked on and off: its address kept, and the plug switches the rail. */
    Done,
}

/**
 * The Pair screen as it stands: the [step], what the scan [found] (strongest signal first; whether it is [searching]), the
 * plug chosen, whether its check [clicked] on and off, whether something runs ([working]), and the [problem] in words.
 */
data class PairState(
    val step: PairStep = PairStep.Find,
    val found: List<FoundPlug> = emptyList(),
    val searching: Boolean = false,
    val plug: FoundPlug? = null,
    val clicked: Boolean = false,
    val working: Boolean = false,
    val problem: String? = null,
)

/**
 * The Pair screen's work (v1.26 — M60; the SwitchBot Plug Mini since v1.36 — M72): no Wi-Fi, no bond, no reset, no Matter,
 * and nothing done in the SwitchBot app. **Find** scans [SCAN_MS] for Plug Minis (company 2409 with fd3d service data 'g'
 * or 'j'); **a tap** connects to the one chosen and switches it off, reads it off, on, reads it on, and off again ("The plug
 * clicked on and off"), never leaving it on; only then its address is **kept**, its switch on, replacing any plug paired
 * before (the newest wins). A plug that doesn't answer says so, and nothing is kept. Runs in [scope] (the app's), so leaving
 * the page cuts nothing short. Main thread.
 */
class PairFlow(
    private val client: PlugClient,
    private val store: PairingStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(PairState())
    val state: StateFlow<PairState> = _state.asStateFlow()
    private var job: Job? = null

    /** From the top. */
    fun restart() {
        job?.cancel()
        _state.value = PairState()
    }

    /** Step 1: the plugs nearby, the strongest first, as their adverts come in. */
    fun search() = run {
        _state.value = PairState(searching = true, working = true)
        val why = client.bluetooth.scan(SCAN_MS) { found ->
            _state.update { now ->
                val others = now.found.filterNot { it.address == found.address }
                now.copy(found = (others + found).sortedByDescending { it.rssi ?: Int.MIN_VALUE })
            }
        }
        _state.update { it.copy(searching = false, problem = why ?: if (it.found.isEmpty()) NOT_FOUND else null) }
    }

    /** Step 2, the plug tapped (the search stops): its on-and-off check, then (step 3) its address kept. */
    fun choose(plug: FoundPlug) {
        if (_state.value.searching) job?.cancel()
        tap(plug)
    }

    private fun tap(plug: FoundPlug) = run {
        _state.update { it.copy(step = PairStep.Check, plug = plug, clicked = false, problem = null) }
        val problem = clicks(plug.address)
        client.drop()
        if (problem != null) {
            log("Plug pairing: …${SwitchBot.lastFour(plug.mac)} — $problem")
            _state.update { it.copy(problem = problem) }
            return@run
        }
        store.pair(PlugPairing(plug.address, plug.mac), on = true)
        log("Plug paired: the SwitchBot …${SwitchBot.lastFour(plug.mac)} (${plug.address}) clicked on and off")
        _state.update { it.copy(step = PairStep.Done, clicked = true) }
    }

    /**
     * Off, read off, on, read on, off: null when every step answered as asked; else what to say. Whatever went wrong after
     * the on, the plug is switched off again (twice if need be), and a plug left on says so.
     */
    private suspend fun clicks(address: String): String? {
        if (!client.switch(on = false, to = address) || client.state(to = address) != false) return NO_ANSWER
        val on = client.switch(on = true, to = address) && client.state(to = address) == true
        val off = client.switch(on = false, to = address) || client.switch(on = false, to = address)
        return when {
            !off -> LEFT_ON
            !on -> NO_ANSWER
            else -> null
        }
    }

    /** [block] in the app's scope, one at a time, [PairState.working] meanwhile. */
    private fun run(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(working = true) }
            try {
                block()
            } finally {
                _state.update { it.copy(working = false, searching = false) }
            }
        }
    }

    companion object {
        const val SCAN_MS = 15_000L

        const val NOT_FOUND = "No SwitchBot plug found. Check it is plugged into the wall and near this tablet, then search again."
        const val NO_ANSWER = "The plug didn't answer. Bring the tablet nearer to it, then tap it again. Nothing was paired."
        const val LEFT_ON =
            "The plug didn't switch off at the end of its check: the 24 V may be on. Unplug the 24 V supply from it, then tap it again. Nothing was paired."
    }
}
