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
    /** 1. Get it ready (v1.28 — M65): the 24 V supply on the plug, the plug in the wall, the phone's hotspot on. Ticked by the person. */
    Ready,

    /** 2. Find the plug (a scan for names beginning ShellyPlugUSG4-) and connect: its model, firmware and setup state. */
    Find,

    /** 3. Firmware older than 2.0.0 only (v1.28 — M65): its update from Shelly over the hotspot, then its setup cleared. */
    Update,

    /** 4. Its settings, Bluetooth RPC, the pairing window, the bond, the cloud off: while `confirmed`, or the bond first while `pending`. */
    SetUp,

    /** 5. Wait for `complete`, the hotspot still on. */
    Complete,

    /** 6. Off every network, the access point closed. */
    Network,

    /** 7. The check: the 24 V on for 5 s, every board answering, then off by itself. */
    Verify,

    /** Paired, and the plug switches the rail. */
    Done,
}

/** One task of [PairStep.SetUp], ticked as it goes. */
enum class SetUpTask { Settings, Bluetooth, Window, Bond, Listed, Cloud }

/**
 * One part of [PairStep.Update] (v1.28 — M65), ticked as it lands: the 24 V off through the restarts, the plug on the
 * hotspot, Shelly's update found, then started, the plug back on 2.0.0 or later, its setup cleared.
 */
enum class UpdateTask { RailOff, Online, Offered, Started, Installed, Cleared }

/** The Update step (v1.28 — M65): the firmware the plug was found on ([from]), the one Shelly offered ([to]), the parts done. */
data class UpdateState(val from: String?, val to: String? = null, val tasks: Set<UpdateTask> = emptySet())

/**
 * The phone's hotspot (v1.32 — M70: or any 2.4 GHz Wi-Fi) as typed on the Pair screen (v1.28 — M65): in memory for this
 * setup alone, never stored or logged, and never in a string by accident.
 */
class Hotspot(val ssid: String, val pass: String) {
    override fun toString(): String = "Hotspot"
}

/** What the Pair screen's check saw: the boards answering as it went, and where it stands. */
data class VerifyState(val answered: Int? = null, val total: Int = Boards.TOTAL, val on: Boolean = false, val finished: Boolean = false, val passed: Boolean = false)

/**
 * The Pair screen as it stands: the [step], what the scan [found] (and whether it is [searching]), the plug chosen, what it
 * said of itself ([info]), the setup's ticked [tasks], the check's reading ([verify]), whether something runs ([working]),
 * and the [problem] in words (null: none). [fallback] is the factory-reset path (the plug finished its setup without this
 * tablet), [overBluetooth] the provisioning over Bluetooth (a new plug set up from here). [firmwareUpdate] is the Update
 * step on firmware older than 2.0.0 (null: none in this setup), [hotspot] the phone's hotspot once typed (v1.28 — M65:
 * forgotten once the plug is off every network, at Finish and at Start over).
 */
data class PairState(
    val step: PairStep = PairStep.Ready,
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
    val firmwareUpdate: UpdateState? = null,
    val hotspot: Hotspot? = null,
    /** Used as it is (v1.29 — M66): old firmware, no update, no bond; it works, but isn't locked to this tablet. */
    val asIs: Boolean = false,
    /** The as-is way waits for the hotspot (v1.29 — M66): the plug must get online once to finish its setup. */
    val hotspotWanted: Boolean = false,
)

/**
 * The Pair screen's work (v1.26 — M60; SMART_PLUG.md › One-time setup), step by step as Steven follows it on the day.
 * Since v1.28 (M65) the Shelly app is optional and the plug's state after Find chooses the way:
 * - firmware older than 2.0.0 updates itself from Shelly over the phone's hotspot, its 24 V kept off through the restarts;
 *   its setup is cleared at once, and it goes on as a new plug with the same hotspot ([update]);
 * - a new plug (`pending`) bonds first, then takes the hotspot over Bluetooth, its settings and the cloud off, waits for
 *   `complete`, closes its network and is checked, by itself;
 * - one set up in the Shelly app (`confirmed`) takes [setUp] at the person's tap: its settings (a mains blip leaves the rail
 *   off, its button can't hold it on), RPC over Bluetooth (a restart when it asks), the pairing window and Android's bond,
 *   the cloud off, `complete` awaited, then the network closed and the check at their taps.
 *
 * The pairing is kept only once the plug is off every network and its access point closed, so a setup that stops halfway
 * leaves nothing paired, and the app keeps not switching the 24 V. A plug `complete` without this tablet is the fallback
 * (a factory reset, the bond while `pending`, the network over Bluetooth); `locked` asks for a power cycle: a locked plug
 * answers nothing over Bluetooth, so one with no bond that connects and gives no reply in 5 s is taken as locked (v1.26 —
 * M62, [PlugClient.read]). Since v1.32 (M70) the real plug's refusal shows at Find with no facts read (it refuses its device
 * info too), and silence there gets the power cycle first and the reset after it (Android may report a refusal as silence).
 * Every way through [configure] turns the plug's Matter off beside its Bluetooth control, one restart serving both, and
 * the network closes with the second network (`sta1`) too. The hotspot (any 2.4 GHz Wi-Fi since v1.32) is held in
 * [PairState] alone. Runs in [scope] (the app's), so leaving the page cuts nothing short. Main thread.
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

    /** This tablet as the plug's list named it after the bond, kept with the pairing once the network is closed (v1.28 — M65). */
    private var bondEntry: String? = null

    /** The plug went off every network in this setup: only then can the setup finish (v1.28 — M65). */
    private var closed = false

    /** The plug took Matter off in this setup (v1.32 — M70): kept with the pairing. */
    private var matterOff = false

    private val address: String? get() = _state.value.plug?.address

    /** From the top, the hotspot forgotten. */
    fun restart() {
        job?.cancel()
        watch?.cancel()
        bondEntry = null
        closed = false
        matterOff = false
        _state.value = PairState()
    }

    /** Step 1 done (v1.28 — M65): the plug plugged in and the hotspot on (or the Shelly app done); the search starts. */
    fun ready() {
        _state.update { it.copy(step = PairStep.Find, problem = null) }
        search()
    }

    /** Looks for plugs nearby (2.5 s on the emulator, 10 s on a tablet), back at Find from any step (v1.28 — M65), the hotspot kept. */
    fun search() = run {
        bondEntry = null
        closed = false
        matterOff = false
        _state.update {
            it.copy(
                step = PairStep.Find, searching = true, found = emptyList(), plug = null, info = null, tasks = emptySet(),
                bonds = null, verify = null, fallback = false, overBluetooth = false, firmwareUpdate = null, problem = null,
                asIs = false, hotspotWanted = false,
            )
        }
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

    /**
     * The plug read; what it is chooses the way on (v1.28 — M65). Locked: a power cycle. `complete` on 2.0.0 or later: kept
     * if this tablet is bonded with it, else the factory reset's way. Firmware older than 2.0.0 (`pending`, `confirmed`, no
     * `provision` before 1.7.5, or v1.29 — M66 `complete` and still answering): the Update step, with its choice ([asIs] or
     * [update]). `pending` on 2.0.0 or later: set up from here by itself. `confirmed`: the person's tap ([setUp]).
     * v1.32 — M70: refused (the real plug refuses an unpaired tablet everything, its device info too) or silent with no bond,
     * the reset's way shows at once, with no facts read; silence says to unplug it for 5 seconds first.
     */
    private suspend fun read() {
        val plug = address ?: return
        val info = when (val heard = client.read(to = plug)) {
            is PlugRead.Read -> heard.info
            PlugRead.Refused -> {
                _state.update { it.copy(info = null, fallback = true, problem = REFUSED) }
                return
            }
            // v1.26 — M62 (SMART_PLUG.md): a plug with no bond that connects but gives no reply in 5 s may be `locked`, and
            // (v1.32 — M70) may be one that finished its setup elsewhere, its refusal reported as silence: a power cycle,
            // then the reset.
            PlugRead.Silent -> {
                val unbonded = !client.bluetooth.bonded(plug)
                if (unbonded) log("Plug: no reply in ${PlugClient.SILENT_MS / 1_000} s and no bond: locked, or refusing this tablet")
                _state.update { it.copy(info = null, fallback = unbonded, problem = if (unbonded) SILENT else UNREACHABLE) }
                return
            }
            PlugRead.Unreachable -> {
                _state.update { it.copy(info = null, fallback = false, problem = UNREACHABLE) }
                return
            }
        }
        _state.update { it.copy(info = info, problem = null, fallback = false) }
        when {
            info.provision == Provision.Locked -> _state.update { it.copy(problem = LOCKED) }
            info.provision == Provision.Complete && info.pairs && client.bluetooth.bonded(plug) -> joinSetUp(info)
            info.provision == Provision.Complete && info.pairs -> _state.update { it.copy(fallback = true, problem = REFUSED) }
            // v1.28 — M65 / v1.29 — M66: old firmware (a `complete` one that still answers too) chooses: as it is, or updated and locked.
            !info.pairs -> _state.update { it.copy(step = PairStep.Update, firmwareUpdate = UpdateState(from = info.ver)) }
            info.provision == Provision.Pending -> overBluetooth(plug)
            else -> Unit   // confirmed (the Shelly app's way): the setup's step waits for the person's tap
        }
    }

    // ---- The update (v1.28 — M65) ---------------------------------------------------------------------------------------

    /**
     * Step 3 on firmware older than 2.0.0 (v1.28 — M65): the 24 V off through the restarts, the plug on the phone's hotspot,
     * Shelly's update found and started (whether the plug takes it over Bluetooth is Shelly's to say: a refusal says so), the
     * plug back on 2.0.0 or later, its setup cleared at once (it went `confirmed` on the hotspot and must not reach `complete`
     * unbonded), then on by itself as a new plug with the same hotspot. Each part ticks as it lands; the first that fails
     * stops, saying what to do. Only from the Update step: past it the plug has its new firmware, and its network is the
     * setup's to open and close.
     */
    fun update(ssid: String, pass: String) = run {
        val plug = address ?: return@run
        if (_state.value.step != PairStep.Update) return@run
        val hotspot = Hotspot(ssid.trim(), pass)
        _state.update {
            it.copy(step = PairStep.Update, hotspot = hotspot, problem = null, fallback = false, firmwareUpdate = UpdateState(from = it.firmwareUpdate?.from ?: it.info?.ver))
        }
        updateNow(plug, hotspot)
    }

    private suspend fun updateNow(plug: String, hotspot: Hotspot) {
        val from = _state.value.firmwareUpdate?.from
        // 1. The 24 V off, and off at power-up through the update's restarts.
        if (!client.switchSet(on = false, to = plug)) return failUpdate(UpdateTask.RailOff)
        if (client.call("Switch.SetConfig", ShellyRpc.offAtPowerUp(), to = plug)?.ok != true) {
            log("Plug update: Switch.SetConfig initial_state off refused on firmware $from; going on")
        }
        tick(UpdateTask.RailOff)
        // 2. On the hotspot: `got ip` awaited.
        if (client.call("WiFi.SetConfig", ShellyRpc.networkOn(hotspot.ssid, hotspot.pass), to = plug)?.ok != true) {
            _state.update { it.copy(problem = NETWORK_FAILED) }
            return
        }
        if (!awaitOnline(plug)) {
            _state.update { it.copy(problem = NOT_ONLINE) }
            return
        }
        tick(UpdateTask.Online)
        // 3. What Shelly offers: 2.0.0 or later, or nothing worth having.
        val offer = client.call("Shelly.CheckForUpdate", to = plug, timeoutMs = CHECK_UPDATE_MS)
        if (offer?.ok != true) return failUpdate(UpdateTask.Offered)
        val to = ShellyRpc.stableOffered(offer.result)
        if (to == null || !PlugInfo.versionAtLeast(to, 2, 0)) {
            log("Plug update: Shelly offers " + (to ?: "nothing") + " for firmware $from")
            _state.update { it.copy(problem = NO_UPDATE) }
            return
        }
        _state.update { it.copy(firmwareUpdate = it.firmwareUpdate?.copy(to = to)) }
        tick(UpdateTask.Offered)
        // 4. Install: an error or no reply is the plug refusing it over Bluetooth.
        if (client.call("Shelly.Update", ShellyRpc.updateStable(), to = plug, timeoutMs = UPDATE_CALL_MS)?.ok != true) {
            log("Plug update: Shelly.Update refused over Bluetooth on firmware $from")
            _state.update { it.copy(problem = UPDATE_REFUSED) }
            return
        }
        log("Plug update: $from to $to started")
        tick(UpdateTask.Started)
        // 5. The restart: the link drops; the plug read until it answers on 2.0.0 or later.
        client.drop()
        val updated = awaitFirmware(plug) ?: return
        log("Plug update: firmware ${updated.ver} answering, ${updated.provision.name.lowercase()}")
        tick(UpdateTask.Installed)
        // 6. Its setup cleared at once, `pending` awaited.
        if (!clearSetup(plug, updated)) return
        tick(UpdateTask.Cleared)
        // 7. On by itself as a new plug, with the hotspot just typed.
        overBluetooth(plug)
    }

    /** `WiFi.GetStatus` every 2 s until the plug says `got ip`, 60 s at most. */
    private suspend fun awaitOnline(plug: String): Boolean {
        val started = now()
        while (now() - started < ONLINE_WAIT_MS) {
            delay(ONLINE_POLL_MS)
            val status = client.call("WiFi.GetStatus", to = plug)
            if (status?.ok == true && ShellyRpc.online(status.result)) return true
        }
        return false
    }

    /**
     * The update's restart: the plug read every 5 s until it answers on 2.0.0 or later, 6 minutes at most; null when it
     * didn't (the problem said). One that comes back refusing this tablet reached `complete` meanwhile: the factory reset's way.
     */
    private suspend fun awaitFirmware(plug: String): PlugInfo? {
        val started = now()
        while (now() - started < INSTALL_WAIT_MS) {
            delay(COMPLETE_POLL_MS)
            when (val heard = client.read(to = plug)) {
                is PlugRead.Read -> if (heard.info.pairs) {
                    _state.update { it.copy(info = heard.info) }
                    return heard.info
                }
                PlugRead.Refused -> {
                    refusedNow()
                    return null
                }
                else -> Unit   // away while it downloads and restarts
            }
        }
        _state.update { it.copy(problem = RESTART_TIMEOUT) }
        return null
    }

    /**
     * Its setup cleared: `Shelly.FactoryReset` keeps the new firmware and drops the network, the bonds and the provisioning
     * (SMART_PLUG.md); then the plug read until it answers `pending`, and its 24 V switched off once more (the relay after a
     * reset is the bench's to see). A plug already `complete` refuses this tablet: the reset by its button (the fallback).
     */
    private suspend fun clearSetup(plug: String, info: PlugInfo): Boolean {
        if (info.provision == Provision.Complete && !client.bluetooth.bonded(plug)) return refusedNow()
        val reset = client.call("Shelly.FactoryReset", to = plug)
        if (reset != null && !reset.ok) {
            failUpdate(UpdateTask.Cleared)
            return false
        }
        // No reply may be the reset itself, under way: the plug read as it comes back says.
        client.drop()
        val started = now()
        while (now() - started < RESET_WAIT_MS) {
            delay(COMPLETE_POLL_MS)
            when (val heard = client.read(to = plug)) {
                is PlugRead.Read -> when (heard.info.provision) {
                    Provision.Pending -> {
                        _state.update { it.copy(info = heard.info) }
                        if (!client.switchSet(on = false, to = plug)) log("Plug update: the 24 V off after the reset wasn't answered")
                        log("Plug update: setup cleared, pending")
                        return true
                    }
                    Provision.Complete -> return refusedNow()
                    else -> Unit
                }
                PlugRead.Refused -> return refusedNow()
                else -> Unit   // restarting
            }
        }
        _state.update { it.copy(problem = RESTART_TIMEOUT) }
        return false
    }

    // ---- Set up -----------------------------------------------------------------------------------------------------

    /**
     * Step 4, while `confirmed` (the Shelly app's way): the settings (retried with `in_locked` alone if the model refuses
     * `in_mode`), RPC over Bluetooth (a restart if asked), the pairing window and the bond, the list of bonds, the cloud off.
     * Each ticks as it lands; the first that fails stops, saying which. A `pending` plug goes the over-Bluetooth way instead
     * (v1.28 — M65): it has no network to reach `complete` on.
     */
    fun setUp() = run {
        val plug = address ?: return@run
        if (_state.value.info?.provision == Provision.Pending) return@run overBluetooth(plug)
        _state.update { it.copy(step = PairStep.SetUp, problem = null) }
        if (!configure(plug) || !bondNow(plug) || !cloudOff(plug)) return@run
        _state.update { it.copy(step = PairStep.Complete) }
        awaitComplete()
    }

    /**
     * The settings and RPC over Bluetooth, and (v1.32 — M70) Matter off beside it, with the one restart either may ask for.
     * Every way through the setup comes here: the Shelly app's, a new or reset plug's, the update's, as it is, and [joinSetUp].
     */
    private suspend fun configure(plug: String): Boolean {
        var settings = client.call("Switch.SetConfig", ShellyRpc.switchConfig(inMode = true), to = plug)
        if (settings != null && !settings.ok) settings = client.call("Switch.SetConfig", ShellyRpc.switchConfig(inMode = false), to = plug)
        if (settings?.ok != true) return fail(SetUpTask.Settings)
        tick(SetUpTask.Settings)
        val rpc = client.call("BLE.SetConfig", ShellyRpc.bleRpcOn(), to = plug)
        if (rpc?.ok != true) return fail(SetUpTask.Bluetooth)
        // Matter: no 30 s timer, a second way to hold the 24 V on. Firmware without it ("No handler"), an error or no answer:
        // logged, and the setup goes on (the plug watch looks again).
        val matter = client.call("Matter.SetConfig", ShellyRpc.matterOff(), to = plug)
        if (matter?.ok == true) {
            matterOff = true
        } else {
            log("Plug setup: Matter off " + (if (matter == null) "unanswered" else "refused (${matter.code} ${matter.message})") + "; going on")
        }
        if (ShellyRpc.restartRequired(rpc) || ShellyRpc.restartRequired(matter)) {
            client.call("Shelly.Reboot", to = plug)
            client.drop()
            delay(REBOOT_WAIT_MS)
            if (client.deviceInfo(to = plug) == null) return fail(SetUpTask.Bluetooth)
        }
        tick(SetUpTask.Bluetooth)
        return true
    }

    /**
     * The pairing window (3 minutes), Android's bond (the person may be asked), and the plug's list of bonds after it. Nothing
     * is kept yet (v1.28 — M65): the pairing is, once the plug is off every network ([closeNetworkNow]).
     */
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
        (after - before.toSet()).singleOrNull()?.let { bondEntry = it }
        _state.update { it.copy(bonds = after.size) }
        log("Plug bonded: ${after.size} bond" + (if (after.size == 1) "" else "s") + " on the plug")
        return true
    }

    /** The cloud off. */
    private suspend fun cloudOff(plug: String): Boolean {
        val cloud = client.call("Cloud.SetConfig", ShellyRpc.cloudOff(), to = plug)
        if (cloud?.ok != true) return fail(SetUpTask.Cloud)
        tick(SetUpTask.Cloud)
        return true
    }

    /**
     * Step 5: the setup state read every 5 s until `complete` (the hotspot stays on meanwhile); true once it is. A plug that
     * went silent with no bond has locked (v1.26 — M62): its setup window ran out before it got online. One that refuses this
     * tablet, or reads `pending` with no network to finish on, stops the wait (v1.28 — M65).
     */
    private suspend fun awaitComplete(): Boolean {
        val plug = address ?: return false
        while (scope.isActive) {
            val heard = client.read(to = plug)
            if (heard == PlugRead.Silent && !client.bluetooth.bonded(plug)) {
                _state.update { it.copy(problem = LOCKED) }
                return false
            }
            if (heard == PlugRead.Refused) return refusedNow()
            val info = (heard as? PlugRead.Read)?.info
            if (info != null) _state.update { it.copy(info = info) }
            when (info?.provision) {
                Provision.Complete -> {
                    _state.update { it.copy(step = PairStep.Network, problem = null) }
                    return true
                }
                Provision.Locked -> {
                    _state.update { it.copy(problem = LOCKED) }
                    return false
                }
                Provision.Pending -> {
                    _state.update { it.copy(problem = NOT_ONLINE) }
                    return false
                }
                else -> delay(COMPLETE_POLL_MS)
            }
        }
        return false
    }

    /** Step 6: off every network and the access point closed: from here the bonded tablet alone talks to the plug. */
    fun closeNetwork() = run {
        val plug = address ?: return@run
        closeNetworkNow(plug)
    }

    /**
     * Off every network and the access point closed; only then the pairing kept (v1.28 — M65: never before, so a setup that
     * stops halfway leaves nothing paired), its switch off until Finish, and the hotspot forgotten. v1.32 — M70: the second
     * network (`sta1`, which the Shelly app may have set) closes too, so the plug watch finds nothing changed; a model that
     * refuses `sta1` gets the two alone. Kept with it: whether the plug took Matter off.
     */
    private suspend fun closeNetworkNow(plug: String): Boolean {
        var answer = client.call("WiFi.SetConfig", ShellyRpc.networkOffAll(), to = plug)
        if (answer != null && !answer.ok) answer = client.call("WiFi.SetConfig", ShellyRpc.networkOff(), to = plug)
        if (answer?.ok != true) {
            _state.update { it.copy(step = PairStep.Network, problem = NETWORK_FAILED) }
            return false
        }
        closed = true
        val info = _state.value.info
        val asIs = _state.value.asIs
        store.pair(PlugPairing(plug, info?.id, info?.model, info?.ver, bondEntry.takeUnless { asIs }, locked = !asIs, matterOff = matterOff), on = false)
        log("Plug " + (if (asIs) "kept as it is, not locked" else "paired") + ": off every network, its access point closed")
        _state.update { it.copy(step = PairStep.Verify, problem = null, hotspot = null) }
        return true
    }

    /**
     * Step 7, the check: the piano answers (its CHECK first), then the 24 V on for 5 s (`toggle_after:5`); the boards are
     * read every 500 ms until the plug switches itself off again (12 s at most). It passes when every board answered with
     * the rail up and none once it went off.
     */
    fun verify() = run {
        val plug = address ?: return@run
        runCheck(plug, asked = true)
    }

    /** The check, [asked] for by the person; run by itself (v1.28 — M65) it waits quietly for the tap while the piano is away. */
    private suspend fun runCheck(plug: String, asked: Boolean) {
        if (check() == null) {
            if (asked) _state.update { it.copy(problem = PIANO_FIRST) }
            return
        }
        if (!client.switchSet(on = true, toggleAfter = VERIFY_S, to = plug)) {
            _state.update { it.copy(problem = UNREACHABLE) }
            return
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

    /** Done: the plug switches the rail from now on; only once it went off every network in this setup (v1.28 — M65). */
    fun finish() = run {
        if (!closed) return@run
        store.setOn(true)
        _state.update { it.copy(step = PairStep.Done, problem = null, hotspot = null) }
    }

    // ---- As it is (v1.29 — M66) -----------------------------------------------------------------------------------------

    /**
     * Use it as it is (v1.29 — M66): old firmware kept, no update and no bond, from the Update step (also after the update
     * was refused or nothing was on offer). The 24 V off, the tablet's settings, RPC over Bluetooth and the cloud off; then a
     * plug with setup states (1.7.5 and later) gets online once on the hotspot and reaches `complete` (else it would end
     * `locked`), and must still answer this unpaired tablet; then its network and access point close, and only then is it
     * kept, not locked; then the check. Nothing is kept on any other end.
     */
    fun asIs() = run {
        val plug = address ?: return@run
        if (_state.value.step != PairStep.Update) return@run
        _state.update { it.copy(step = PairStep.SetUp, asIs = true, overBluetooth = false, tasks = emptySet(), problem = null, fallback = false) }
        val info = when (val heard = client.read(to = plug)) {
            is PlugRead.Read -> heard.info.also { fresh -> _state.update { it.copy(info = fresh) } }
            PlugRead.Refused, PlugRead.Silent -> return@run refusedAsIs()
            PlugRead.Unreachable -> {
                _state.update { it.copy(problem = UNREACHABLE) }
                return@run
            }
        }
        if (!client.switchSet(on = false, to = plug)) {
            _state.update { it.copy(problem = failed(UpdateTask.RailOff)) }
            return@run
        }
        if (!configure(plug) || !cloudOff(plug)) return@run
        when (info.provision) {
            Provision.Pending, Provision.Confirmed -> {
                // It must get online once, or it ends `locked`: the hotspot, typed already or asked for now.
                val hotspot = _state.value.hotspot
                if (hotspot == null) _state.update { it.copy(hotspotWanted = true) } else asIsOnline(plug, hotspot)
            }
            Provision.Locked -> _state.update { it.copy(problem = LOCKED) }
            else -> asIsClose(plug)   // no setup states (before 1.7.5), or `complete` and answering
        }
    }

    /** The as-is way's network once (v1.29 — M66): the hotspot, `got ip`, `complete` awaited (about 6 minutes at most), then closed. */
    private suspend fun asIsOnline(plug: String, hotspot: Hotspot) {
        _state.update { it.copy(problem = null) }
        if (client.call("WiFi.SetConfig", ShellyRpc.networkOn(hotspot.ssid, hotspot.pass), to = plug)?.ok != true) {
            _state.update { it.copy(problem = NETWORK_FAILED) }
            return
        }
        if (!awaitOnline(plug)) {
            _state.update { it.copy(problem = NOT_ONLINE) }
            return
        }
        _state.update { it.copy(step = PairStep.Complete) }
        val started = now()
        while (now() - started < AS_IS_COMPLETE_MS) {
            delay(COMPLETE_POLL_MS)
            when (val heard = client.read(to = plug)) {
                is PlugRead.Read -> {
                    _state.update { it.copy(info = heard.info) }
                    when (heard.info.provision) {
                        Provision.Complete -> return asIsClose(plug)
                        Provision.Locked -> {
                            _state.update { it.copy(problem = LOCKED) }
                            return
                        }
                        else -> Unit
                    }
                }
                // Pairing enforced from `complete` on firmware that can't pair: it needs its update after all.
                PlugRead.Refused -> return refusedAsIs()
                else -> Unit
            }
        }
        _state.update { it.copy(problem = RESTART_TIMEOUT) }
    }

    /**
     * The as-is way's end (v1.29 — M66): the plug read once more unpaired (some firmware lets only paired tablets in once set
     * up), its network and access point closed, then kept, not locked, and the check by itself.
     */
    private suspend fun asIsClose(plug: String) {
        when (val heard = client.read(to = plug)) {
            is PlugRead.Read -> _state.update { it.copy(info = heard.info) }
            PlugRead.Refused, PlugRead.Silent -> return refusedAsIs()
            PlugRead.Unreachable -> {
                _state.update { it.copy(problem = UNREACHABLE) }
                return
            }
        }
        _state.update { it.copy(step = PairStep.Network) }
        if (!closeNetworkNow(plug)) {
            if (client.bluetooth.refused) refusedAsIs()
            return
        }
        runCheck(plug, asked = false)
    }

    /** This firmware lets only paired tablets in once set up, and can't pair (v1.29 — M66): its update after all, from a reset. */
    private fun refusedAsIs(): Unit = _state.update { it.copy(problem = AS_IS_REFUSED, hotspotWanted = false) }

    // ---- A new plug, and the fallback --------------------------------------------------------------------------------

    /**
     * A new plug (`pending`) set up from here (v1.28 — M65: by itself after Find, or after the update): the bond first, then
     * the hotspot over Bluetooth ([provisionNow]) if it was typed already; if not, the form waits for it.
     */
    private suspend fun overBluetooth(plug: String) {
        _state.update { it.copy(step = PairStep.SetUp, overBluetooth = true, problem = null) }
        if (!bondNow(plug)) return
        val hotspot = _state.value.hotspot ?: return
        provisionNow(plug, hotspot)
    }

    /** After a factory reset the plug is `pending`: bond first, then the network over Bluetooth (Try again's way in). */
    fun bondWhilePending() = run {
        val plug = address ?: return@run
        overBluetooth(plug)
    }

    /** The hotspot's name and password for the plug, kept in memory for this setup (v1.28 — M65), then the rest of the setup. */
    fun provision(ssid: String, pass: String) = run {
        val plug = address ?: return@run
        val hotspot = Hotspot(ssid.trim(), pass)
        _state.update { it.copy(hotspot = hotspot, hotspotWanted = false) }
        if (_state.value.asIs) asIsOnline(plug, hotspot) else provisionNow(plug, hotspot)
    }

    /**
     * The hotspot to the plug (`WiFi.SetConfig` with `sta`), then `confirmed` awaited and the rest of the setup: its settings,
     * the cloud off, `complete` awaited, then (v1.28 — M65) the network closed and the check, by themselves.
     */
    private suspend fun provisionNow(plug: String, hotspot: Hotspot) {
        _state.update { it.copy(problem = null) }
        val answer = client.call("WiFi.SetConfig", ShellyRpc.networkOn(hotspot.ssid, hotspot.pass), to = plug)
        if (answer?.ok != true) {
            _state.update { it.copy(problem = NETWORK_FAILED) }
            return
        }
        repeat(CONFIRM_POLLS) {
            delay(COMPLETE_POLL_MS)
            val info = client.deviceInfo(to = plug) ?: return@repeat
            _state.update { it.copy(info = info) }
            if (info.provision == Provision.Confirmed || info.provision == Provision.Complete) {
                if (!configure(plug) || !cloudOff(plug)) return
                _state.update { it.copy(step = PairStep.Complete) }
                if (!awaitComplete() || !closeNetworkNow(plug)) return
                runCheck(plug, asked = false)
                return
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

    /**
     * A `complete` plug that answers this bonded tablet: set up by another tablet, or a setup here cut short after its bond.
     * Either way it ends as the setup does (v1.28 — M65): its settings and the cloud off once more (the same values: nothing
     * changes on a plug set up already), its network closed, then the check.
     */
    private suspend fun joinSetUp(info: PlugInfo) {
        val plug = address ?: return
        val listed = client.call("BLE.ListPairedDevices", to = plug)?.takeIf { it.ok }?.let { ShellyRpc.pairedAddresses(it.result) }
        _state.update {
            it.copy(info = info, step = PairStep.SetUp, tasks = setOf(SetUpTask.Window, SetUpTask.Bond, SetUpTask.Listed), bonds = listed?.size, fallback = false, problem = null)
        }
        if (!configure(plug) || !cloudOff(plug)) return
        closeNetworkNow(plug)
    }

    // ---- Parts -------------------------------------------------------------------------------------------------------

    private fun tick(task: SetUpTask) = _state.update { it.copy(tasks = it.tasks + task) }

    private fun tick(task: UpdateTask) = _state.update { pair -> pair.copy(firmwareUpdate = pair.firmwareUpdate?.let { it.copy(tasks = it.tasks + task) }) }

    private fun fail(task: SetUpTask): Boolean {
        _state.update { it.copy(problem = failed(task)) }
        return false
    }

    private fun failUpdate(task: UpdateTask) = _state.update { it.copy(problem = failed(task)) }

    /** The plug finished its setup without this tablet: the factory reset (v1.32 — M70: the Shelly app's, or its button's). */
    private fun refusedNow(): Boolean {
        _state.update { it.copy(fallback = true, problem = REFUSED) }
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

        /** The update (v1.28 — M65): `got ip` asked every 2 s for 60 s; Shelly's answers given longer than a setting's. */
        const val ONLINE_POLL_MS = 2_000L
        const val ONLINE_WAIT_MS = 60_000L
        const val CHECK_UPDATE_MS = 15_000L
        const val UPDATE_CALL_MS = 10_000L

        /** The as-is way (v1.29 — M66): `complete` awaited about 6 minutes at most, read every 5 s. */
        const val AS_IS_COMPLETE_MS = 6 * 60_000L

        /** The plug back on its new firmware within 6 minutes, and `pending` within 2 after its reset (read every 5 s). */
        const val INSTALL_WAIT_MS = 6 * 60_000L
        const val RESET_WAIT_MS = 2 * 60_000L

        const val NOT_FOUND = "No power plug found. Check it is plugged in and near this tablet, then search again."
        const val UNREACHABLE = "The plug didn't answer. Bring the tablet nearer to it, then try again."
        /** v1.32 — M70: the real plug set up elsewhere refuses an unpaired tablet everything; the reset's way follows it. */
        const val REFUSED = "This plug finished its setup somewhere else, such as the Shelly app, so it only answers devices it already paired with."

        /** Silent with no bond at Find (v1.32 — M70): locked, or a refusal reported as silence. The reset's way follows it. */
        const val SILENT =
            "The plug didn't answer. Unplug it for 5 seconds and plug it back in, then find it again. If it still won't answer, reset it:"
        const val LOCKED = "Unplug the power plug for 5 seconds and plug it back in, then open Pair the power plug again."
        const val NETWORK_FAILED = "The plug didn't take the network setting. Try again."

        /** Not online (v1.32 — M70: a phone's hotspot or a 2.4 GHz Wi-Fi); the update needs internet, and the setup asks for it. */
        const val NOT_ONLINE =
            "The plug didn't get online. Check the Wi-Fi's name and password, and that it has internet. The plug only sees " +
                "2.4 GHz: on an iPhone's hotspot, turn on Maximize Compatibility; on an Android's, choose the 2.4 GHz band."
        const val PIANO_FIRST = "Connect to the piano first (Piano › Instrument): the check reads its power boards."
        const val VERIFY_FAILED = "Not every board answered with the 24 V on, or they didn't go when it went off. Check the supply is on the plug, then try again."
        const val JOIN_FAILED = "This tablet couldn't pair. On the tablet the plug knows, open Piano › Power plug › Admit a replacement tablet, then try again within 3 minutes."

        /** The update's words (v1.28 — M65). */
        const val NO_UPDATE =
            "Shelly offers no update for the plug right now. Try again later, or update it once in the Shelly app (that needs a " +
                "Shelly account), then search again."
        const val UPDATE_REFUSED =
            "The plug won't take its update over Bluetooth. Update it once in the Shelly app (that needs a Shelly account), then search again."
        const val RESTART_TIMEOUT = "Unplug the power plug for 5 seconds, plug it back in, then search again."

        /** The as-is way refused once the plug finished its setup (v1.29 — M66): its update after all, from a reset. */
        const val AS_IS_REFUSED =
            "This plug's firmware lets only paired tablets in once it is set up, and it can't pair: it needs its update after " +
                "all. Unplug it, plug it back in, and within the first minute hold its button for 10 seconds. Then search " +
                "again and choose Update and lock it to this tablet."

        fun failed(task: SetUpTask): String = when (task) {
            SetUpTask.Settings -> "The plug didn't take its settings."
            SetUpTask.Bluetooth -> "The plug didn't turn on its Bluetooth control."
            SetUpTask.Window -> "The plug didn't open its pairing window (it needs firmware 2.0.0 or later)."
            SetUpTask.Bond -> "Pairing didn't finish. When Android asks to pair with the plug, tap Pair."
            SetUpTask.Listed -> "The plug didn't list its paired devices."
            SetUpTask.Cloud -> "The plug didn't turn its cloud off."
        } + " Try again."

        fun failed(task: UpdateTask): String = when (task) {
            UpdateTask.RailOff -> "The plug didn't switch its 24 V off. Try again."
            UpdateTask.Online -> NOT_ONLINE
            UpdateTask.Offered -> "The plug couldn't ask Shelly for its update. Check the hotspot has internet, then try again."
            UpdateTask.Started -> UPDATE_REFUSED
            UpdateTask.Installed -> RESTART_TIMEOUT
            UpdateTask.Cleared -> "The plug didn't clear its setup. Search again."
        }
    }
}
