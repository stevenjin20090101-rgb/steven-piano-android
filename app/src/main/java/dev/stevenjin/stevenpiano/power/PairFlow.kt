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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The Pair screen's steps, in the setup's order (SMART_PLUG.md › One-time setup). */
enum class PairStep {
    /** 1. The Shelly app on the phone, over a hotspot: firmware 2.0.0 or later, the cloud declined. Ticked by the person. */
    ShellyApp,

    /** 2. Find the plug (a scan for names beginning ShellyPlugUSG4-) and connect: its model, firmware and setup state. */
    Find,

    /** 3. Its settings, Bluetooth RPC, the pairing window, the bond, the cloud off, while it is still `confirmed`. */
    SetUp,

    /** 4. Wait for `complete`, the hotspot still on. */
    Complete,

    /** 5. Off every network, the access point closed. */
    Network,

    /** 6. The check: the 24 V on for 5 s, every board answering, then off by itself. */
    Verify,

    /** Paired, and the plug switches the rail. */
    Done,
}

/** One task of [PairStep.SetUp], ticked as it goes. */
enum class SetUpTask { Settings, Bluetooth, Window, Bond, Listed, Cloud }

/** What the Pair screen's check saw: the boards answering as it went, and where it stands. */
data class VerifyState(val answered: Int? = null, val total: Int = Boards.TOTAL, val on: Boolean = false, val finished: Boolean = false, val passed: Boolean = false)

/**
 * The Pair screen as it stands: the [step], what the scan [found] (and whether it is [searching]), the plug chosen, what it
 * said of itself ([info]), the setup's ticked [tasks], the check's reading ([verify]), whether something runs ([working]),
 * and the [problem] in words (null: none). [fallback] is the factory-reset path (the plug finished its setup without this
 * tablet), [overBluetooth] the provisioning over Bluetooth after it (a new plug set up from here).
 */
data class PairState(
    val step: PairStep = PairStep.ShellyApp,
    val found: List<FoundPlug> = emptyList(),
    val searching: Boolean = false,
    val plug: FoundPlug? = null,
    val info: PlugInfo? = null,
    val tasks: Set<SetUpTask> = emptySet(),
    val bonds: Int? = null,
    val verify: VerifyState? = null,
    val working: Boolean = false,
    val problem: String? = null,
    val fallback: Boolean = false,
    val overBluetooth: Boolean = false,
)

/**
 * The Pair screen's work (v1.26 — M60; SMART_PLUG.md › One-time setup), step by step as Steven follows it on the day:
 * the Shelly app first; then, while the plug is still `confirmed`, its settings (a mains blip leaves the rail off, its
 * button can't hold it on), RPC over Bluetooth (a restart when it asks), the pairing window and Android's bond, the cloud
 * off; then `complete` awaited; then every network forgotten and the access point closed; then the check: the 24 V on for
 * 5 s with every board answering, off again by itself. A plug `complete` without this tablet is the fallback (a factory
 * reset, the bond while `pending`, the network over Bluetooth); `locked` asks for a power cycle: a locked plug answers
 * nothing over Bluetooth, so one with no bond that connects and gives no reply in 5 s is taken as locked (v1.26 — M62,
 * [PlugClient.read]). Runs in [scope] (the app's), so leaving the page cuts nothing short. Main thread.
 */
class PairFlow(
    private val client: PlugClient,
    private val store: PairingStore,
    private val scope: CoroutineScope,
    /** The piano's boards (the CHECK), for the check at the end; null when it doesn't answer. */
    private val check: suspend () -> Boards?,
    /** A monotonic clock in ms (`SystemClock.elapsedRealtime`; the test scheduler's in tests). */
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(PairState())
    val state: StateFlow<PairState> = _state.asStateFlow()
    private var job: Job? = null
    private var watch: Job? = null

    private val address: String? get() = _state.value.plug?.address

    /** From the top. */
    fun restart() {
        job?.cancel()
        watch?.cancel()
        _state.value = PairState()
    }

    /** Step 1 done: the Shelly app has set the plug up (or the person goes on without it). */
    fun shellyAppDone() {
        _state.update { it.copy(step = PairStep.Find, problem = null) }
        search()
    }

    /** Looks for plugs nearby (2.5 s on the emulator, 10 s on a tablet). */
    fun search() = run {
        _state.update { it.copy(searching = true, found = emptyList(), plug = null, info = null, fallback = false, problem = null) }
        val why = client.bluetooth.scan(SCAN_MS) { found ->
            _state.update { now -> if (now.found.any { it.address == found.address }) now else now.copy(found = now.found + found) }
        }
        _state.update { it.copy(searching = false, problem = why ?: if (it.found.isEmpty()) NOT_FOUND else null) }
    }

    /** The plug chosen: connect and read it. */
    fun choose(plug: FoundPlug) = run {
        _state.update { it.copy(plug = plug, info = null, problem = null) }
        read()
    }

    /** Reads the plug again (its setup state): what it is decides the next step. */
    fun refresh() = run { read() }

    private suspend fun read() {
        val plug = address ?: return
        val info = when (val heard = client.read(to = plug)) {
            is PlugRead.Read -> heard.info
            PlugRead.Refused -> {
                _state.update { it.copy(info = null, fallback = true, problem = REFUSED) }
                return
            }
            // v1.26 — M62 (SMART_PLUG.md): a plug with no bond that connects but gives no reply in 5 s is `locked`.
            PlugRead.Silent -> {
                val locked = !client.bluetooth.bonded(plug)
                if (locked) log("Plug: no reply in ${PlugClient.SILENT_MS / 1_000} s and no bond: locked")
                _state.update { it.copy(info = null, fallback = false, problem = if (locked) LOCKED else UNREACHABLE) }
                return
            }
            PlugRead.Unreachable -> {
                _state.update { it.copy(info = null, fallback = false, problem = UNREACHABLE) }
                return
            }
        }
        _state.update { it.copy(info = info, problem = null, fallback = false) }
        when {
            !info.pairs -> _state.update { it.copy(problem = TOO_OLD) }
            info.provision == Provision.Locked -> _state.update { it.copy(problem = LOCKED) }
            info.provision == Provision.Complete && client.bluetooth.bonded(plug) -> joinSetUp(info)
            info.provision == Provision.Complete -> _state.update { it.copy(fallback = true, problem = REFUSED) }
            else -> Unit   // pending or confirmed: the setup's step waits for the person's tap
        }
    }

    /**
     * Step 3, while `confirmed`: the settings (retried with `in_locked` alone if the model refuses `in_mode`), RPC over
     * Bluetooth (a restart if asked), the pairing window and the bond, the list of bonds, the cloud off. Each ticks as it
     * lands; the first that fails stops, saying which.
     */
    fun setUp() = run {
        val plug = address ?: return@run
        _state.update { it.copy(step = PairStep.SetUp, problem = null) }
        if (!configure(plug)) return@run
        if (!bondNow(plug)) return@run
        val cloud = client.call("Cloud.SetConfig", ShellyRpc.cloudOff(), to = plug)
        if (cloud?.ok != true) {
            fail(SetUpTask.Cloud)
            return@run
        }
        tick(SetUpTask.Cloud)
        _state.update { it.copy(step = PairStep.Complete) }
        awaitComplete()
    }

    /** The settings and RPC over Bluetooth, with the restart it may ask for. */
    private suspend fun configure(plug: String): Boolean {
        var settings = client.call("Switch.SetConfig", ShellyRpc.switchConfig(inMode = true), to = plug)
        if (settings != null && !settings.ok) settings = client.call("Switch.SetConfig", ShellyRpc.switchConfig(inMode = false), to = plug)
        if (settings?.ok != true) return fail(SetUpTask.Settings)
        tick(SetUpTask.Settings)
        val rpc = client.call("BLE.SetConfig", ShellyRpc.bleRpcOn(), to = plug)
        if (rpc?.ok != true) return fail(SetUpTask.Bluetooth)
        if (ShellyRpc.restartRequired(rpc)) {
            client.call("Shelly.Reboot", to = plug)
            client.drop()
            delay(REBOOT_WAIT_MS)
            if (client.deviceInfo(to = plug) == null) return fail(SetUpTask.Bluetooth)
        }
        tick(SetUpTask.Bluetooth)
        return true
    }

    /** The pairing window (3 minutes), Android's bond (the person may be asked), and the plug's list of bonds after it. */
    private suspend fun bondNow(plug: String): Boolean {
        val before = client.call("BLE.ListPairedDevices", to = plug)?.takeIf { it.ok }?.let { ShellyRpc.pairedAddresses(it.result) }.orEmpty()
        val window = client.call("BLE.StartPairing", JSONObject().put("timeout", PAIRING_WINDOW_S), to = plug)
        if (window?.ok != true) return fail(SetUpTask.Window)
        tick(SetUpTask.Window)
        if (!client.bluetooth.bond(plug, BOND_MS)) return fail(SetUpTask.Bond)
        tick(SetUpTask.Bond)
        val after = client.call("BLE.ListPairedDevices", to = plug)?.takeIf { it.ok }?.let { ShellyRpc.pairedAddresses(it.result) }
            ?: return fail(SetUpTask.Listed)
        tick(SetUpTask.Listed)
        val info = _state.value.info
        val entry = (after - before.toSet()).singleOrNull()
        store.pair(PlugPairing(plug, info?.id, info?.model, info?.ver, entry), on = false)
        _state.update { it.copy(bonds = after.size) }
        log("Plug paired: ${after.size} bond" + (if (after.size == 1) "" else "s") + " on the plug")
        return true
    }

    /**
     * Step 4: the setup state read every 5 s until `complete` (the hotspot stays on meanwhile). A plug that went silent with
     * no bond has locked (v1.26 — M62): its setup window ran out before it got online.
     */
    private suspend fun awaitComplete() {
        val plug = address ?: return
        while (scope.isActive) {
            val heard = client.read(to = plug)
            if (heard == PlugRead.Silent && !client.bluetooth.bonded(plug)) {
                _state.update { it.copy(problem = LOCKED) }
                return
            }
            val info = (heard as? PlugRead.Read)?.info
            if (info != null) _state.update { it.copy(info = info) }
            when (info?.provision) {
                Provision.Complete -> {
                    _state.update { it.copy(step = PairStep.Network, problem = null) }
                    return
                }
                Provision.Locked -> {
                    _state.update { it.copy(problem = LOCKED) }
                    return
                }
                else -> delay(COMPLETE_POLL_MS)
            }
        }
    }

    /** Step 5: off every network and the access point closed: from here the bonded tablet alone talks to the plug. */
    fun closeNetwork() = run {
        val plug = address ?: return@run
        val answer = client.call("WiFi.SetConfig", ShellyRpc.networkOff(), to = plug)
        if (answer?.ok != true) {
            _state.update { it.copy(problem = NETWORK_FAILED) }
            return@run
        }
        _state.update { it.copy(step = PairStep.Verify, problem = null) }
    }

    /**
     * Step 6, the check: the piano answers (its CHECK first), then the 24 V on for 5 s (`toggle_after:5`); the boards are
     * read every 500 ms until the plug switches itself off again (12 s at most). It passes when every board answered with
     * the rail up and none once it went off.
     */
    fun verify() = run {
        val plug = address ?: return@run
        if (check() == null) {
            _state.update { it.copy(problem = PIANO_FIRST) }
            return@run
        }
        if (!client.switchSet(on = true, toggleAfter = VERIFY_S, to = plug)) {
            _state.update { it.copy(problem = UNREACHABLE) }
            return@run
        }
        _state.update { it.copy(verify = VerifyState(on = true), problem = null) }
        var allUp = false
        var offAfter = false
        val started = now()
        while (now() - started < VERIFY_WATCH_MS) {
            delay(VERIFY_EVERY_MS)
            val boards = check() ?: continue
            val elapsed = now() - started
            if (boards.all) allUp = true
            val on = elapsed < VERIFY_S * MS_PER_S
            if (!on && boards.answered == 0 && allUp) offAfter = true
            _state.update { it.copy(verify = VerifyState(boards.answered, boards.total, on = on)) }
            if (offAfter) break
        }
        val passed = allUp && offAfter
        _state.update { it.copy(verify = it.verify?.copy(finished = true, passed = passed), problem = if (passed) null else VERIFY_FAILED) }
    }

    /** Done: the plug switches the rail from now on. */
    fun finish() = run {
        store.setOn(true)
        _state.update { it.copy(step = PairStep.Done, problem = null) }
    }

    // ---- The fallback ------------------------------------------------------------------------------------------------

    /**
     * After a factory reset the plug is `pending`: bond first (its pairing window, then Android's bond), then the network
     * over Bluetooth ([provision]).
     */
    fun bondWhilePending() = run {
        val plug = address ?: return@run
        _state.update { it.copy(overBluetooth = true, problem = null, step = PairStep.SetUp) }
        bondNow(plug)
    }

    /** The hotspot's name and password to the plug (`WiFi.SetConfig` with `sta`), then `confirmed` awaited and the rest of the setup. */
    fun provision(ssid: String, pass: String) = run {
        val plug = address ?: return@run
        val answer = client.call("WiFi.SetConfig", ShellyRpc.networkOn(ssid.trim(), pass), to = plug)
        if (answer?.ok != true) {
            _state.update { it.copy(problem = NETWORK_FAILED) }
            return@run
        }
        repeat(CONFIRM_POLLS) {
            delay(COMPLETE_POLL_MS)
            val info = client.deviceInfo(to = plug) ?: return@repeat
            _state.update { it.copy(info = info) }
            if (info.provision == Provision.Confirmed || info.provision == Provision.Complete) {
                if (!configure(plug)) return@run
                val cloud = client.call("Cloud.SetConfig", ShellyRpc.cloudOff(), to = plug)
                if (cloud?.ok != true) {
                    fail(SetUpTask.Cloud)
                    return@run
                }
                tick(SetUpTask.Cloud)
                _state.update { it.copy(step = PairStep.Complete) }
                awaitComplete()
                return@run
            }
        }
        _state.update { it.copy(problem = NOT_ONLINE) }
    }

    /** A plug another tablet set up and opened its pairing window for: Android's bond, then the plug read and kept. */
    fun join() = run {
        val plug = address ?: return@run
        if (!client.bluetooth.bond(plug, BOND_MS)) {
            _state.update { it.copy(problem = JOIN_FAILED) }
            return@run
        }
        val info = client.deviceInfo(to = plug)
        if (info == null) {
            _state.update { it.copy(problem = JOIN_FAILED) }
            return@run
        }
        joinSetUp(info)
    }

    /** A `complete` plug that answers this bonded tablet: kept as it is, set up already; the check comes next. */
    private suspend fun joinSetUp(info: PlugInfo) {
        val plug = address ?: return
        val listed = client.call("BLE.ListPairedDevices", to = plug)?.takeIf { it.ok }?.let { ShellyRpc.pairedAddresses(it.result) }
        store.pair(PlugPairing(plug, info.id, info.model, info.ver, null), on = false)
        _state.update {
            it.copy(info = info, step = PairStep.Verify, tasks = SetUpTask.entries.toSet(), bonds = listed?.size, fallback = false, problem = null)
        }
    }

    // ---- Parts -------------------------------------------------------------------------------------------------------

    private fun tick(task: SetUpTask) = _state.update { it.copy(tasks = it.tasks + task) }

    private fun fail(task: SetUpTask): Boolean {
        _state.update { it.copy(problem = failed(task)) }
        return false
    }

    /** [block] in the app's scope, one at a time, [PairState.working] meanwhile. */
    private fun run(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(working = true) }
            try {
                block()
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    companion object {
        const val SCAN_MS = 10_000L
        const val PAIRING_WINDOW_S = 180
        const val BOND_MS = 60_000L
        const val REBOOT_WAIT_MS = 6_000L
        const val COMPLETE_POLL_MS = 5_000L
        const val CONFIRM_POLLS = 24

        /** The check's 5 s on: SMART_PLUG.md › One-time setup › 3. */
        const val VERIFY_S = 5
        const val VERIFY_EVERY_MS = 500L
        const val VERIFY_WATCH_MS = 12_000L
        private const val MS_PER_S = 1_000L

        const val NOT_FOUND = "No power plug found. Check it is plugged in and near this tablet, then search again."
        const val UNREACHABLE = "The plug didn't answer. Bring the tablet nearer to it, then try again."
        const val REFUSED = "This plug finished its setup without this tablet, so it refuses it."
        const val LOCKED = "Unplug the power plug for 5 seconds and plug it back in, then open Pair the power plug again."
        const val TOO_OLD = "The plug's firmware is older than 2.0.0. Update it in the Shelly app first, then search again."
        const val NETWORK_FAILED = "The plug didn't take the network setting. Try again."
        const val NOT_ONLINE = "The plug didn't get online. Check the hotspot's name and password, and that it has internet."
        const val PIANO_FIRST = "Connect to the piano first (Piano › Instrument): the check reads its power boards."
        const val VERIFY_FAILED = "Not every board answered with the 24 V on, or they didn't go when it went off. Check the supply is on the plug, then try again."
        const val JOIN_FAILED = "This tablet couldn't pair. On the tablet the plug knows, open Piano › Power plug › Admit a replacement tablet, then try again within 3 minutes."

        fun failed(task: SetUpTask): String = when (task) {
            SetUpTask.Settings -> "The plug didn't take its settings."
            SetUpTask.Bluetooth -> "The plug didn't turn on its Bluetooth control."
            SetUpTask.Window -> "The plug didn't open its pairing window (it needs firmware 2.0.0 or later)."
            SetUpTask.Bond -> "Pairing didn't finish. When Android asks to pair with the plug, tap Pair."
            SetUpTask.Listed -> "The plug didn't list its paired devices."
            SetUpTask.Cloud -> "The plug didn't turn its cloud off."
        } + " Try again."
    }
}
