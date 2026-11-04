// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import dev.stevenjin.stevenpiano.AppGraph.Companion.DISCONNECT_FLUSH_MS
import dev.stevenjin.stevenpiano.AppGraph.Companion.PIANO_FULL_POWER
import dev.stevenjin.stevenpiano.AppGraph.Companion.RAIL_AWAIT_MS
import dev.stevenjin.stevenpiano.AppGraph.Companion.TAG
import dev.stevenjin.stevenpiano.admin.KeepRunning
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.instruments.KeyboardListener
import dev.stevenjin.stevenpiano.midi.KeyEvents
import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.piano.LockPhase
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.power.AndroidPlugTransport
import dev.stevenjin.stevenpiano.power.Boards
import dev.stevenjin.stevenpiano.power.GuestLink
import dev.stevenjin.stevenpiano.power.HeldKeys
import dev.stevenjin.stevenpiano.power.IdleReading
import dev.stevenjin.stevenpiano.power.OnHours
import dev.stevenjin.stevenpiano.power.PairFlow
import dev.stevenjin.stevenpiano.power.PiecePower
import dev.stevenjin.stevenpiano.power.PlayerNow
import dev.stevenjin.stevenpiano.power.PlugAdverts
import dev.stevenjin.stevenpiano.power.PlugClient
import dev.stevenjin.stevenpiano.power.PlugSettings
import dev.stevenjin.stevenpiano.power.PlugStore
import dev.stevenjin.stevenpiano.power.PlugTransport
import dev.stevenjin.stevenpiano.power.PowerHost
import dev.stevenjin.stevenpiano.power.PowerUse
import dev.stevenjin.stevenpiano.power.RailGate
import dev.stevenjin.stevenpiano.power.RailHold
import dev.stevenjin.stevenpiano.power.RailKeeper
import dev.stevenjin.stevenpiano.power.RailPhase
import dev.stevenjin.stevenpiano.power.RailPower
import dev.stevenjin.stevenpiano.power.SwitchBotRail
import dev.stevenjin.stevenpiano.service.PowerService
import dev.stevenjin.stevenpiano.ui.LockCopy
import dev.stevenjin.stevenpiano.ui.PowerCopy
import dev.stevenjin.stevenpiano.ui.SafetyCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The power's part of [AppGraph]: the app kept running on the school tablet (M77), the 24 V rail on its smart plug (M60), the
 * plug and the safety check together (M62) and the power watch (M67). The graph makes it once and everything else reaches
 * it through the graph's members; what it holds is lazy or asked for, and what starts when stays in [AppGraph.start].
 */
internal class PowerWiring(private val graph: AppGraph, private val app: Application) {
    private val appScope = graph.appScope

    // ==== v1.39 — M77: the app keeps running on the school tablet (DESIGN.md › v1.39 — M77) ==============================

    /** Whether the power watch's service runs ([PowerService]); null while no plug is paired, with nothing to watch. */
    fun powerWatch(): Boolean? = if (plugSettings.value?.pairing == null) null else PowerService.state.value

    /**
     * The power watch follows the pairing (from [start]): on while a plug is paired, off without. Android lets the device
     * owner, or an app without battery limits, start it from the background; otherwise [startPowerWatchIfPaired] does.
     */
    internal fun followPowerWatch() {
        appScope.launch {
            plugSettings.first { it != null }
            plugSettings.map { it?.pairing != null }.distinctUntilChanged().collect { paired ->
                if (paired) startPowerService() else PowerService.stop(app)
            }
        }
    }

    /** The activity came to the front, where a foreground service may always start: the watch, while a plug is paired. */
    fun startPowerWatchIfPaired() {
        if (plugSettings.value?.pairing != null && !PowerService.state.value) startPowerService()
    }

    /** A connected device's service needs the Bluetooth permission (Android 14): without it, the watch isn't started at all. */
    private fun startPowerService() {
        if (BlePermissions.missing(app).isEmpty()) PowerService.start(app)
    }

    /**
     * The first start asks Android, once, to let the app run without battery limits (Android's own dialog): not when it
     * already may, nor while the screen is locked to the app (Android would block the dialog). Piano › System asks any time.
     */
    suspend fun askBatteryOnce(activity: Activity) {
        if (KeepRunning.batteryUnrestricted(app)) return
        val lockTask = activity.getSystemService(ActivityManager::class.java)?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE
        if (lockTask != ActivityManager.LOCK_TASK_MODE_NONE) return
        try {
            if (graph.settingsRepository.batteryAsked()) return
            if (KeepRunning.askBattery(activity)) graph.settingsRepository.markBatteryAsked()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Couldn't keep whether the battery was asked about")
        }
    }

    // ==== end of M77's block ====================================================================================================

    // ==== v1.26 — M60: the 24 V rail on a smart plug (firmware/docs/SMART_PLUG.md; DESIGN.md › v1.26 — M60) ================
    // M60's one block. Outside it, two lines name it: the instrument plays through [railPlays], and [start] calls [startRail].

    /** The plug's own settings (the "power_plug" DataStore): its pairing, its switch, guest play this tablet started. */
    val plugStore: PlugStore by lazy { PlugStore(app) }

    /** The plug's settings as they stand; null until they have been read. */
    val plugSettings: StateFlow<PlugSettings?> by lazy { plugStore.settings.stateIn(appScope, SharingStarted.Eagerly, null) }

    /** Whether the plug switches the rail: paired, and its switch on. Until the settings are read, no. */
    private val plugSwitches: StateFlow<Boolean> by lazy {
        plugSettings.map { it?.switches == true }.stateIn(appScope, SharingStarted.Eagerly, false)
    }

    /** The plug's Bluetooth (the SwitchBot Plug Mini since v1.36 — M72); on the emulator in debug builds, the fake plug. */
    private val plugTransport: PlugTransport by lazy { Emulator.plugTransport() ?: AndroidPlugTransport(app, graph.scanThrottle) }

    /**
     * The plug's commands over Bluetooth (one in flight). v1.36 — M72: each new connection to the paired plug CUTs unless
     * something wants the rail ([RailKeeper.plugConnected]).
     */
    val plug: PlugClient by lazy {
        PlugClient(
            plugTransport,
            appScope,
            address = { plugSettings.value?.pairing?.address },
            log = LinkLog::warn,
            connected = { appScope.launch(Dispatchers.Main) { rail.plugConnected() } },
        )
    }

    /** The advert watch (v1.36 — M72): the paired plug's adverts, its switch and the supply's draw. */
    val plugAdverts: PlugAdverts by lazy { PlugAdverts(SystemClock::elapsedRealtime, LinkLog::warn) }

    /** Piano › Power use (v1.36 — M72): the adverts' draw integrated, kept with the pairing in the plug's store. */
    val powerUse: PowerUse by lazy {
        PowerUse(appScope, System::currentTimeMillis, { ZoneId.systemDefault() }, keep = { address, text -> plugStore.keepEnergy(address, text) })
    }

    /** The CHECK: the piano's `get boards`, answered within 2 s (null: no answer, or no console). */
    private suspend fun railCheck(): Boards? = Boards.parse(graph.pianoSettings.ask(BOARDS_FACT, RAIL_CHECK_MS))

    /** The tablet as the 24 V supply's dead-man's switch ([RailKeeper]); [railPower] is how other features see it. */
    val rail: RailKeeper by lazy {
        RailKeeper(
            scope = appScope,
            plug = SwitchBotRail(plug, plugAdverts),   // v1.36 — M72
            check = ::railCheck,
            connected = stevenConnected,
            stopSequence = ::railStopSequence,
            stopPlaying = ::stopForRail,
            available = plugSwitches,
            now = SystemClock::elapsedRealtime,
            guest = railGuest,
            log = LinkLog::warn,
            power = railWatch,   // v1.30 — M67
            wakeLink = ::wakePianoLink,   // v1.38 — M75
            graceMs = ::railGraceMs,   // v1.39 — M76
            cool = plugStore,   // v1.40 — M78: the cool-down after a fault CUT, kept on the wall clock
        )
    }

    /**
     * v1.38 — M75: the rail woke the piano (its controller is on the plug with the 24 V): Steven Piano's link connects now,
     * to the remembered piano, rather than waiting for a reconnect's backoff. Nothing while another instrument is chosen.
     */
    private fun wakePianoLink() {
        if (graph.pianoLink.kind.value != InstrumentKind.StevenPiano || graph.stevenLink.state.value is LinkState.Connected) return
        if (BlePermissions.missing(app).isNotEmpty()) return
        graph.pianoLink.connect(graph.settings.value.lastDeviceAddress)
    }

    /** The rail as the safety check (M61) and anything else that wants it sees it. */
    val railPower: RailPower get() = rail

    /** Steven Piano's own link connected: the rail's CHECK can be asked. */
    private val stevenConnected: StateFlow<Boolean> by lazy {
        graph.stevenLink.state.map { it is LinkState.Connected }.distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, false)
    }

    /** Steven Piano's link as everything that plays sees it: the lock's gate, then the rail's (notes wait while it comes up). */
    internal val railPlays: PianoLink by lazy { RailGate(graph.stevenPlays, rail.notesHeld) }

    /** The Pair screen's work (Piano › Power plug). */
    val pairFlow: PairFlow by lazy { PairFlow(plug, plugStore, appScope, log = LinkLog::warn) }

    /** The stop sequence (CC64 = 0, CC123) straight to Steven Piano, past both gates, and written before it returns. */
    private suspend fun railStopSequence() {
        graph.stevenLink.send(MidiBatch().apply { add(CONTROL_CHANGE, SUSTAIN, 0); add(CONTROL_CHANGE, ALL_NOTES_OFF, 0) }, dropPending = false)
        withContext(Dispatchers.IO) { graph.stevenLink.flush(RAIL_FLUSH_MS) }
    }

    /** The rail failed or was cut: what plays on Steven Piano stops (the piece, Test every key, the Keys tab's keys). */
    private fun stopForRail() {
        if (!graph.player.locked) graph.player.stop()
        graph.remote.keyTest.stop()
        graph.player.silenceLive()
    }

    /** Guest play's link: the tablet lets go of the piano, connects again, and ends guest play there. */
    private val railGuest = object : GuestLink {
        override fun letGo() {
            appScope.launch {
                graph.player.pauseAndFlush(DISCONNECT_FLUSH_MS)
                if (graph.pianoLink.kind.value == InstrumentKind.StevenPiano) graph.pianoLink.disconnect()
                LinkLog.warn("Guest play: the tablet lets go of the piano")
            }
        }

        override suspend fun reconnect(): Boolean {
            if (graph.pianoLink.kind.value != InstrumentKind.StevenPiano) return false
            if (graph.stevenLink.state.value !is LinkState.Connected && BlePermissions.missing(app).isEmpty()) graph.pianoLink.connect(graph.settings.value.lastDeviceAddress)
            return withTimeoutOrNull(GUEST_RECONNECT_MS) { graph.pianoSettings.lock.first { it.manageable || it.phase == LockPhase.None && graph.pianoSettings.state.value is PianoState.Ready } } != null
        }

        override fun endOnPiano() = graph.pianoSettings.guestPlay(0)
    }

    /**
     * Guest play from this tablet (Firmware and status' switch): `lock guest 60`; with the plug switching the rail, once the
     * piano says guest play is on, the rail is kept for it (60 minutes at most) and the tablet lets go of the piano.
     */
    fun startGuestPlay() {
        graph.pianoSettings.guestPlay(LockCopy.GUEST_MINUTES)
        if (!plugSwitches.value) return
        appScope.launch {
            withTimeoutOrNull(GUEST_START_MS) { graph.pianoSettings.lock.first { it.guestSeconds > 0 } } ?: return@launch
            rail.startGuest(LockCopy.GUEST_MINUTES)
        }
    }

    /** End guest play: with the rail kept for it, the full sequence (reconnect, `lock guest 0`, the stop sequence, CUT). */
    fun endGuestPlay() {
        if (rail.guestUntil.value == null) {
            graph.pianoSettings.guestPlay(0)
            return
        }
        appScope.launch { rail.endGuest() }
    }

    /** The Keys tab on screen ([on]) with Steven Piano the instrument: it wants the rail, until 10 minutes without a key. */
    fun railKeysShown(on: Boolean) = rail.keysOnScreen(on && graph.pianoLink.kind.value == InstrumentKind.StevenPiano)

    /** A key on the Keys tab: the idle cut starts over, and a rail cut for idling wakes ("Waking the piano…"). */
    fun railKeyPressed() = rail.keyPressed()

    /**
     * v1.38 — M75: Piano › Power plug's Turn the piano on ([on]) or off: the rail wanted until 30 minutes pass without a
     * note, or no longer (the usual stop, then CUT).
     */
    fun setPianoOn(on: Boolean) = rail.manual(on)

    /**
     * v1.38 — M75: Steven Piano is the instrument and its power plug switches the rail, so a piano not connected is off and
     * wakes with the rail: what plays it (Play, Test every key, the safety check) wants the rail rather than being refused.
     */
    fun pianoWakes(): Boolean = graph.pianoLink.kind.value == InstrumentKind.StevenPiano && rail.available.value

    /** The plug's switch, from Piano › Power plug: off, what plays stops and the rail is cut first. */
    fun setPlugOn(on: Boolean) {
        appScope.launch {
            if (!on) rail.release()
            plugStore.setOn(on)
        }
    }

    /** Forget the plug: the rail cut, the pairing gone, and its power use with it. */
    fun forgetPlug() {
        appScope.launch {
            rail.release()
            plugStore.forget()
            powerUse.clear()
            plug.drop()
            pairFlow.restart()
            LinkLog.warn("Power plug forgotten")
        }
    }

    /** What the rail follows: the player (a piece playing; held while the rail comes up), Test every key, a firmware update, Live's keys. */
    internal fun startRail() {
        // v1.36 — M72: a Shelly plug's pairing from before 1.36 cleared, once (it already reads as none).
        appScope.launch { if (plugStore.migrate()) LinkLog.warn("Power plug: the Shelly plug's pairing was cleared; this version pairs a SwitchBot plug") }
        rail.start()
        followPlugAdverts()
        followOnHours()   // v1.39 — M76
        followCooling()   // v1.40 — M78
        followPieceStarts()
        RailHold(
            appScope,
            player = combine(graph.player.state, graph.pianoLink.kind) { s, kind ->
                PlayerNow(playing = kind == InstrumentKind.StevenPiano && s.status == PlaybackStatus.Playing, stopped = s.status == PlaybackStatus.Stopped, pieceId = s.piece?.pieceId)
            },
            held = rail.notesHeld,
            positionMicros = graph.player::positionMicrosNow,
            pause = graph.player::pause,
            resume = graph.player::resume,
            want = { rail.wanted(RailKeeper.PLAY, it) },
        ).start()
        appScope.launch { graph.remote.keyTest.state.map { it.running }.distinctUntilChanged().collect { rail.wanted(RailKeeper.KEY_TEST, it) } }
        appScope.launch { graph.firmwareUpdater.state.map(::updateWantsRail).distinctUntilChanged().collect(rail::updateRunning) }
        // A keyboard's keys through Live are keys on the Keys tab: each starts the idle cut over, or wakes the rail.
        val main = Handler(android.os.Looper.getMainLooper())
        graph.keyboard.listen(
            object : KeyboardListener {
                override fun onKeys(events: KeyEvents, arrivalNanos: Long, stampNanos: Long) {
                    if (events.downs() > 0 && graph.liveThru.state.value.open) main.post(rail::keyPressed)
                }
            },
        )
        // Guest play this tablet started survives a restart of the app: its end, on the wall clock, is kept. Then (v1.36 —
        // M72) the CUT at app start unless something wants the rail: the SwitchBot comes on by itself after a power cut.
        appScope.launch {
            val settings = plugSettings.first { it != null }
            settings?.guestUntil?.let { until ->
                val left = until - System.currentTimeMillis()
                if (left > 0) rail.restoreGuest(SystemClock.elapsedRealtime() + left) else plugStore.setGuestUntil(null)
            }
            if (settings?.switches == true) withTimeoutOrNull(RAIL_AWAIT_MS) { rail.available.first { it } }
            rail.appStarted()
        }
        appScope.launch {
            rail.guestUntil.drop(1).collect { until ->
                plugStore.setGuestUntil(until?.let { System.currentTimeMillis() + (it - SystemClock.elapsedRealtime()) })
            }
        }
    }

    /**
     * The advert watch (v1.36 — M72): while a plug is paired, its adverts for the life of the process, low-power while the
     * rail is off and fast while it is up. Each one to the keeper (an "on" with nothing wanting the rail is CUT) and to Power
     * use; a CUT taken keeps Power use's meter. A new pairing, or none, starts Power use again from what the store kept.
     */
    private fun followPlugAdverts() {
        val pairing = plugSettings.map { it?.pairing }.distinctUntilChanged()
        val fast = rail.phase.map { it != RailPhase.Off }.distinctUntilChanged()
        appScope.launch {
            combine(pairing, fast) { p, f -> p to f }.collectLatest { (p, f) ->
                if (p == null) {
                    plugAdverts.clear()
                    piecePower.clear()
                    return@collectLatest
                }
                plugAdverts.run(plugTransport, p.address, p.mac, f) { advert ->
                    appScope.launch(Dispatchers.Main) {
                        rail.advert(advert.on)
                        powerUse.reading(advert.watts)
                        piecePower.reading(advert.watts, playing = graph.player.state.value.status == PlaybackStatus.Playing)   // v1.39 — M76
                    }
                }
            }
        }
        appScope.launch {
            plugSettings.first { it != null }
            pairing.collect { p -> powerUse.load(p?.address, p?.let { plugStore.energy(it.address) }) }
        }
        appScope.launch { rail.armedAt.drop(1).collect { if (it == null) powerUse.save() } }
    }

    // ---- v1.39 — M76: the On hours, the idle link, the power on Now playing ------------------------------------------------

    /** Now playing's power line: the watts now and this piece's peak, from the plug's adverts. */
    val piecePower: PiecePower by lazy { PiecePower(SystemClock::elapsedRealtime) }

    /** Bumped at each On hours boundary (and setting change), so the words that follow them are worked out again. */
    private val hoursTick = MutableStateFlow(0L)

    /**
     * The On hours (Piano › Power plug): within them, with Steven Piano the instrument, the rail's standing want
     * ([RailKeeper.HOURS]); outside them none. Looked at every [HOURS_LOOK_MS] on the wall clock (no alarm: the clock in
     * the app's own process, as the quiet times), and at once when the hours or the instrument change; told to the rail
     * only when it changes, so a fault that ended the want isn't undone until the next boundary.
     */
    private fun followOnHours() {
        appScope.launch {
            combine(plugSettings.map { it?.hours }, graph.pianoLink.kind) { hours, kind -> hours to kind }.distinctUntilChanged().collectLatest { (hours, kind) ->
                var told: Boolean? = null
                while (true) {
                    val within = hours != null && kind == InstrumentKind.StevenPiano && hours.wanted(ZonedDateTime.now())
                    if (within != told) {
                        if (told != null || within) LinkLog.warn("On hours: " + if (within) "within them, the piano stays on" else "outside them, the piano rests")
                        told = within
                        rail.wanted(RailKeeper.HOURS, within)
                        hoursTick.value++
                    }
                    delay(HOURS_LOOK_MS)
                }
            }
        }
    }

    /** The peak on Now playing starts again as each piece starts: a new piece, or the same from its top. */
    private fun followPieceStarts() {
        appScope.launch {
            var last: Pair<PlaybackStatus, Long?>? = null
            graph.player.state.map { it.status to it.piece?.pieceId }.distinctUntilChanged().collect { now ->
                val before = last
                last = now
                val starts = now.first == PlaybackStatus.Playing &&
                    (before == null || before.second != now.second || graph.player.positionMicrosNow() <= 0L)
                if (starts) piecePower.pieceStarts()
            }
        }
    }

    /** Steven Piano is the instrument and its power plug switches the rail: the plug wakes the piano. */
    internal fun plugWakesPiano(): Boolean = graph.pianoLink.kind.value == InstrumentKind.StevenPiano && plugSwitches.value

    /** The rail's grace between pieces: 2 minutes outside the On hours (a person at the tablet woke it), else none. */
    private fun railGraceMs(): Long = if (plugSettings.value?.hours?.outside(ZonedDateTime.now()) == true) RailKeeper.GRACE_MS else 0L

    /**
     * The idle link: the piano is off with its plug (the rail off), so a drop is expected (the CUT took its controller) and
     * its link doesn't look for it until an ARM wakes it ([wakePianoLink]). No fault, nothing said.
     */
    internal fun pianoResting(): Boolean = plugWakesPiano() && rail.phase.value == RailPhase.Off

    /**
     * The piano's words while it rests with its plug (the Piano tab's line, the System fact, the panel): "Piano off until
     * 7:00 · press Play here to wake it" outside the On hours, else "Piano off · press Play here to wake it"; null while it
     * is on, waking, or connected, or without the plug.
     */
    val pianoAsleep: StateFlow<String?> by lazy {
        combine(plugSettings, rail.phase, stevenConnected, graph.pianoLink.kind, hoursTick) { plug, phase, connected, kind, _ ->
            if (plug?.switches != true || kind != InstrumentKind.StevenPiano || phase != RailPhase.Off || connected) {
                null
            } else {
                PowerCopy.asleep(plug.hours, plug.hours.outside(ZonedDateTime.now()))
            }
        }.combine(coolWords) { asleep, cool -> cool ?: asleep }   // v1.40 — M78: the cool-down's words first
            .stateIn(appScope, SharingStarted.Eagerly, null)
    }

    // ---- v1.40 — M78: the cool-down after a fault CUT ---------------------------------------------------------------------

    private val _coolWords = MutableStateFlow<String?>(null)

    /**
     * The cool-down's words with Steven Piano on its plug: "Cooling down after a fault · 24 min left" (looked at every
     * [COOL_TICK_MS]), then "Run the safety check to play again" until the check the fault called for passes; null
     * otherwise. Play's refusal, Piano › Power plug, and (through [pianoAsleep]) the Piano tab, System and the panel.
     */
    val coolWords: StateFlow<String?> get() = _coolWords

    private fun followCooling() {
        appScope.launch {
            combine(rail.coolUntil, graph.safety.state, plugSwitches, graph.pianoLink.kind) { until, state, plugged, kind ->
                Triple(until, state.fault && (state.pending || state.lock != null), plugged && kind == InstrumentKind.StevenPiano)
            }.distinctUntilChanged().collectLatest { (until, due, shown) ->
                while (true) {
                    val left = until?.let { it - System.currentTimeMillis() }?.takeIf { it > 0 && shown }
                    _coolWords.value = left?.let(SafetyCopy::cooling) ?: SafetyCopy.CHECK_TO_PLAY.takeIf { due && shown }
                    if (left == null) break
                    delay(minOf(left, COOL_TICK_MS))
                }
            }
        }
    }

    /** The cool-down's ms left with the plug switching the rail; null while none runs. */
    fun coolLeft(): Long? = rail.coolLeft()?.takeIf { rail.available.value }

    /** Piano › Power plug's Skip the cool-down (behind the kiosk PIN, confirmed): logged; the safety check still runs. */
    fun skipCooling() = rail.skipCooling()

    /**
     * Why a start from afar (the web panel, the relay: Play, a channel, Test every key, Turn the piano on) is refused now:
     * outside the On hours with the piano resting, "The piano is off until 7:00". Only a person at the tablet wakes it
     * then; once it is awake (a person there), the panel plays as usual. Null when it may go.
     */
    fun offHoursRefusal(): String? {
        if (!plugWakesPiano()) return null
        val hours = plugSettings.value?.hours ?: return null
        return PowerCopy.offUntil(hours).takeIf { hours.refusesRemote(ZonedDateTime.now(), resting = rail.phase.value == RailPhase.Off) }
    }

    /** The panel's Turn the piano on / off (its System page): on is refused outside the On hours ([offHoursRefusal]). */
    fun setPianoOnFromAfar(on: Boolean): String? {
        if (on) coolLeft()?.let { return SafetyCopy.cooling(it) }   // v1.40 — M78
        if (on) offHoursRefusal()?.let { return it }
        rail.manual(on)
        return null
    }

    /** Piano › Power plug › On hours changed. */
    fun setOnHours(hours: OnHours) {
        appScope.launch { plugStore.setHours(hours) }
    }

    /**
     * The firmware updater with the piano off (its controller on the plug): the rail wanted for a while
     * ([UPDATE_WAKE_MS]: the download and the check; the transfer's own want takes over from BEGIN), and the 24 V up with
     * the piano connected awaited ([RAIL_AWAIT_MS]). True when connected.
     */
    internal suspend fun wakeForUpdate(): Boolean {
        if (stevenConnected.value) return true
        if (withContext(Dispatchers.Main) { coolLeft() } != null) return false   // v1.40 — M78: nothing switches the plug on
        if (!withContext(Dispatchers.Main) { pianoWakes() }) return false
        withContext(Dispatchers.Main) { rail.wantFor(RailKeeper.UPDATE_WAKE, UPDATE_WAKE_MS) }
        return graph.safetyWiring.railUpWithin(RAIL_AWAIT_MS) && stevenConnected.value
    }

    /** A firmware update wants the rail from BEGIN (its transfer under way) until its outcome is known. */
    private fun updateWantsRail(state: FirmwareState): Boolean = when (state) {
        is FirmwareState.Sending -> state.bytes > 0
        is FirmwareState.PianoVerifying, is FirmwareState.Restarting -> true
        is FirmwareState.Done -> state.confirming
        else -> false
    }

    // ==== end of M60's block ==================================================================================================

    // ==== v1.26 — M62: the plug and the safety check together (DESIGN.md › v1.26 — M62) ================================

    /**
     * Steven Piano's notes held for its 24 V (v1.26 — M62): Steven Piano is the instrument, and its power plug's rail holds
     * every note but what releases while it comes up ([RailKeeper.notesHeld], which [RailGate] applies), so nothing reaches
     * the piano. Red means notes reaching the piano: the "Sent to piano" dots and the rail's are red only while a piece plays
     * and this is false, and the connected dot while the rail wakes. Always false without the plug (the dots as before).
     */
    val railHoldsNotes: StateFlow<Boolean> by lazy {
        combine(rail.notesHeld, graph.pianoLink.kind) { held, kind -> held && kind == InstrumentKind.StevenPiano }
            .stateIn(appScope, SharingStarted.Eagerly, false)
    }

    // ==== end of M62's block ==========================================================================================

    // ==== v1.30 — M67: the power watch (SMART_PLUG.md › rule 11; BUILD_SPEC.md › v1.30 — M67) ==========================

    /** The keys the player's router holds, over time: what the rail's power watch weighs the 24 V's draw against. */
    val heldKeys: HeldKeys by lazy { HeldKeys(SystemClock::elapsedRealtime) }

    /**
     * What the rail's power watch reads beyond the plug: [heldKeys] (each key at most the piano's longest hold), the piano's
     * `!active` and `!i2cfails`, its Full power (on unless it says otherwise), and the safety check it makes due.
     */
    private val railWatch: PowerHost by lazy {
        object : PowerHost {
            override fun held(from: Long, to: Long): Double = heldKeys.held(from, to, pianoLongestHold())

            override fun pedalDown(from: Long, to: Long): Boolean = heldKeys.pedalDown(from, to)

            override fun struckSince(at: Long): Boolean = heldKeys.struckSince(at)

            override suspend fun ask(fact: String): String? = graph.pianoSettings.ask(fact, RAIL_CHECK_MS)

            override val fullPower: Boolean get() = pianoValue(PIANO_FULL_POWER) != "0"

            override fun checkDue() = graph.safety.require()

            // v1.31 — M68: the plug's usual idle, kept with its pairing in the plug's store.
            override fun usualIdle(): List<Double> = plugSettings.value?.idles?.map { it.watts }.orEmpty()

            override fun keepIdle(watts: Double) {
                appScope.launch { plugStore.keepIdle(IdleReading(System.currentTimeMillis(), watts)) }
            }

            override fun aboveUsual() {
                appScope.launch { plugStore.aboveUsual() }
            }
        }
    }

    /** A safety check heard every key (v1.31 — M68): after a trip above the plug's usual idle, it is learned again. */
    internal fun railHeardAll() {
        appScope.launch { if (plugStore.heardAll()) LinkLog.warn("Power watch: a safety check heard every key; the plug's usual idle is learned again") }
    }

    /** The piano's longest hold, ms (`hold`: its hold watchdog lets every coil go then); the firmware's ceiling unread. */
    private fun pianoLongestHold(): Long =
        pianoValue(PIANO_HOLD)?.toLongOrNull()?.coerceIn(PIANO_HOLD_LEAST_MS, HeldKeys.HOLD_CEILING_MS) ?: HeldKeys.HOLD_CEILING_MS

    /** One of the piano's settings as it last reported it; null before its dump. */
    private fun pianoValue(name: String): String? = (graph.pianoSettings.state.value as? PianoState.Ready)?.values?.get(name)

    // ==== end of M67's block ==========================================================================================

    private companion object {
        /** The piano's longest hold, ms, and the least it takes (v1.30 — M67: the power watch). */
        const val PIANO_HOLD = "hold"
        const val PIANO_HOLD_LEAST_MS = 50L

        // v1.26 — M60: the rail.
        const val BOARDS_FACT = "boards"
        const val RAIL_CHECK_MS = 2_000L
        const val RAIL_FLUSH_MS = 300L

        /** v1.39 — M76: the On hours looked at this often; the firmware updater's wake wants the rail this long. */
        const val HOURS_LOOK_MS = 30_000L

        /** v1.40 — M78: the cool-down's minutes left are looked at again this often. */
        const val COOL_TICK_MS = 15_000L
        const val UPDATE_WAKE_MS = 5 * 60_000L
        const val GUEST_RECONNECT_MS = 15_000L
        const val GUEST_START_MS = 5_000L
        const val CONTROL_CHANGE = 0xB0
        const val SUSTAIN = 64
        const val ALL_NOTES_OFF = 123
    }
}
