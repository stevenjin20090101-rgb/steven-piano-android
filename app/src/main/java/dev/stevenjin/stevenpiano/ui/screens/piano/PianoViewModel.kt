// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
import dev.stevenjin.stevenpiano.audio.TabletSoundState
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.diag.PianoDiag
import dev.stevenjin.stevenpiano.diag.RunningNow
import dev.stevenjin.stevenpiano.firmware.FirmwarePiano
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.MidiChoice
import dev.stevenjin.stevenpiano.instruments.MidiDeviceRef
import dev.stevenjin.stevenpiano.instruments.MidiPicker
import dev.stevenjin.stevenpiano.instruments.MidiPurpose
import dev.stevenjin.stevenpiano.instruments.MidiScan
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoFold
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.DynamicRange
import dev.stevenjin.stevenpiano.player.ExpressionLevel
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.schedule.NextSchedule
import dev.stevenjin.stevenpiano.settings.Appearance
import dev.stevenjin.stevenpiano.settings.StandbyCanvas
import dev.stevenjin.stevenpiano.settings.StandbyShows
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.service.FirmwareService
import dev.stevenjin.stevenpiano.service.UpdateService
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.FirmwareActions
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.SystemDay
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.SystemNow
import dev.stevenjin.stevenpiano.update.UpdateState
import dev.stevenjin.stevenpiano.web.PosterPrint
import dev.stevenjin.stevenpiano.web.WebStatus
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import dev.stevenjin.stevenpiano.web.relay.EnrolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Piano tab, its hub and its pages together (one instance for the tab's whole graph): the
 * connection, the piano's own settings, the app's updates and preferences, and which page is open.
 * The piano's settings go through the app-wide [AppGraph.pianoSettings], which reads them on every
 * connection; [leave] saves them on the piano when the tab goes (not when a page closes).
 * Preferences are written in the app's scope so leaving the tab never drops one; the player picks
 * them up from the settings flow. Update, Check now and Restart act through [AppGraph.updater].
 * The hub's row values come from [GroupSummaries], worked out from these same flows.
 */
class PianoViewModel(private val graph: AppGraph, private val saved: SavedStateHandle) : ViewModel(), PianoSettingsActions, FirmwareActions {
    val link: StateFlow<LinkState> = graph.pianoLink.state
    val settings: StateFlow<PianoSettings> = graph.settings
    val playing: StateFlow<Boolean> = graph.player.state
        .map { it.status == PlaybackStatus.Playing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** What the piano reports of its own settings. */
    val piano: StateFlow<PianoState> = graph.pianoSettings.state
    val statusText: StateFlow<String?> = graph.pianoSettings.statusText
    val statusReading: StateFlow<Boolean> = graph.pianoSettings.statusReading

    /** The updater: a release on offer, its download, its install, or what the last check found. */
    val update: StateFlow<UpdateState> = graph.updater.state

    /** Where the web panel listens (Remote control's address and QR, and the hub's row). */
    val web: StateFlow<WebStatus> = graph.web.status

    /** The piano's firmware update (v1.6 — M21): the Firmware page's FIRMWARE section, and the hub's row. */
    val firmware: StateFlow<FirmwareState> = graph.firmwareUpdater.state

    /** The piano as the firmware updater sees it: its version, and whether it can be updated. */
    val firmwarePiano: StateFlow<FirmwarePiano> = graph.firmwareUpdater.piano

    override fun checkFirmware() = graph.firmwareUpdater.checkNow()

    override fun checkFirmwareOnOpen() = graph.firmwareUpdater.checkOnOpen()

    /** Update or Retry: the transfer starts in the app's scope, and the foreground service follows it. */
    override fun updateFirmware(context: Context) {
        graph.firmwareUpdater.update()
        if (graph.firmwareUpdater.state.value.busy) FirmwareService.start(context.applicationContext)
    }

    override fun cancelFirmware() = graph.firmwareUpdater.cancel()

    /** The next schedule's start (the hub's Schedule row). */
    val nextSchedule: StateFlow<NextSchedule?> = graph.schedules.next

    /** The tablet's piano sound (v1.8 — M25): its mode and volume as set, the piano's link, the SoundFont and its download. */
    val tabletSound: StateFlow<TabletSoundState> = graph.tabletSound.state

    fun setTabletSound(mode: TabletSoundMode) = edit { setTabletSound(mode) }

    fun setTabletVolume(pct: Int) = edit { setTabletVolume(pct) }

    fun downloadTabletSound() = graph.tabletSound.download()

    fun cancelTabletSound() = graph.tabletSound.cancelDownload()

    fun removeTabletSound() = graph.tabletSound.remove()

    /** The instrument (Piano › Instrument, v1.11 — M29): Steven Piano or a MIDI piano. */
    val instrumentKind: StateFlow<InstrumentKind> = graph.pianoLink.kind

    /** Steven Piano plays from now on (the MIDI piano silenced and let go first). */
    fun chooseStevenPiano() = graph.chooseInstrument(InstrumentKind.StevenPiano)

    /** [choice] plays from now on (the instrument before silenced and let go first). */
    fun chooseMidiPiano(choice: MidiChoice) = graph.chooseInstrument(InstrumentKind.MidiPiano, choice)

    /** All keys off: the instrument's stop sequence at once (and Steven Piano's own off). */
    fun allKeysOff() = graph.allKeysOff()

    /** The MIDI keyboard (Piano › Keyboard, v1.11 — M29). */
    val keyboard: StateFlow<KeyboardState> = graph.keyboard.state

    /** What Android's MIDI service lists, and the picker's Bluetooth search. */
    val midiDevices: StateFlow<List<MidiDeviceRef>> = graph.midiDevices.devices
    val midiScan: StateFlow<MidiScan> = graph.midiDevices.scan

    /** The picker's rows for [purpose] (never Steven Piano, nor the remembered piano's address). */
    fun pickerRows(purpose: MidiPurpose, listed: List<MidiDeviceRef>, scan: MidiScan): List<MidiChoice> =
        MidiPicker.rows(purpose, listed, scan.found, graph.settings.value.lastDeviceAddress)

    /** The picker opened, or Look again: Bluetooth MIDI devices are looked for (12 s, on the shared scan budget). */
    fun startMidiScan() = graph.midiDevices.startScan()

    /** The picker closed. */
    fun stopMidiScan() = graph.midiDevices.stopScan()

    /** The person chose [choice] as the keyboard: remembered and connected (the one before lets go first). */
    fun chooseKeyboard(choice: MidiChoice) = graph.keyboard.choose(choice)

    /** Forget the keyboard: what it holds lets go, and none is remembered. */
    fun forgetKeyboard() = graph.keyboard.forget()

    fun connect() = graph.pianoLink.connect(graph.settings.value.lastDeviceAddress)

    /** The person chose the other "Steven Piano" a scan found: it becomes the piano this phone connects to. */
    fun connectTo(address: String) = graph.pianoLink.connect(address)

    /** Stops looking for the piano. Nothing is sounding yet, so there is nothing to silence. */
    fun cancel() = graph.pianoLink.disconnect()

    /** Pauses first, so the piano is silenced, then drops the link. */
    fun disconnect() = graph.disconnectPiano()

    override fun setPianoValue(name: String, value: String) = graph.pianoSettings.set(name, value)

    override fun applyPreset(command: String) = graph.pianoSettings.preset(command)

    override fun runPianoAction(action: PianoAction, key: Int) = graph.pianoSettings.action(action, key)

    override fun dismissPianoError() = graph.pianoSettings.dismissError()

    /** The tab left the foreground: changes still waiting go now, and the piano saves them. */
    fun leave() = graph.pianoSettings.leave()

    private val _selectedPage = MutableStateFlow(SettingsPage.of(saved.get<String>(SELECTED)) ?: SettingsPage.Feel)

    /** The page open beside the hub on wide screens (Sound and touch at first), and on phones the one last opened. */
    val selectedPage: StateFlow<SettingsPage> = _selectedPage.asStateFlow()

    /**
     * Whether the person has a page open on a wide screen (they picked it, or it was open on the
     * phone before it turned), so that a phone turned upright shows that page rather than the hub.
     */
    private var opened: Boolean
        get() = saved.get<Boolean>(OPENED) == true
        set(value) {
            saved[OPENED] = value
        }

    /** Each page's scroll, shared by the page pushed on a phone and the page beside the hub, so turning the phone keeps it. */
    private val scrolls = mutableStateMapOf(*SettingsPage.entries.map { it to ScrollState(0) }.toTypedArray())

    fun scrollOf(page: SettingsPage): ScrollState = scrolls.getValue(page)

    /** Phones: a row of the hub opens [page], from its top. */
    fun open(page: SettingsPage) {
        scrolls[page] = ScrollState(0)
        choose(page, opened = false)
    }

    /** Wide screens: a row of the hub shows [page] beside it; another page starts from its top. */
    fun pick(page: SettingsPage) {
        if (page != _selectedPage.value) scrolls[page] = ScrollState(0)
        choose(page, opened = true)
    }

    /** The window widened with [page] open on the phone: it stays open, beside the hub, where it was scrolled to. */
    fun keepOpen(page: SettingsPage) = choose(page, opened = true)

    // ---- Search and the disclosures (v1.13 — M31b) --------------------------------------------------

    /** What the hub's search field holds (Compose state: the field reads it as it is typed). */
    var query by mutableStateOf("")
        private set

    fun search(text: String) {
        query = text.take(MAX_QUERY)
    }

    private var folds by mutableStateOf(emptySet<PianoFold>())

    override val openFolds: Set<PianoFold> get() = folds

    override fun toggleFold(fold: PianoFold) {
        folds = if (fold in folds) folds - fold else folds + fold
    }

    private val _jump = MutableStateFlow<Jump?>(null)
    private var jumps = 0L

    /** A search result's way into its page: its fold opened, then scrolled to and lit by the page ([JumpEffect]). */
    val jump: StateFlow<Jump?> = _jump.asStateFlow()

    /** A result was chosen: [target]'s fold opens now, and its page scrolls to it once it shows. */
    fun jumpTo(target: SettingsTarget.Row) {
        target.fold?.let { folds = folds + it }
        _jump.value = Jump(target.page, target.anchor, target.fold, ++jumps)
    }

    /** The page has done [jump] (or given up on it). */
    fun jumpDone(jump: Jump) {
        _jump.compareAndSet(jump, null)
    }

    /** The window narrowed: the page to put back over the hub, if the person had one open; asked once. */
    fun takeOpened(): SettingsPage? {
        if (!opened) return null
        opened = false
        return _selectedPage.value
    }

    private fun choose(page: SettingsPage, opened: Boolean) {
        _selectedPage.value = page
        saved[SELECTED] = page.key
        this.opened = opened
    }

    fun setAutoConnect(on: Boolean) = edit { setAutoConnect(on) }

    fun setDefaultTempo(pct: Int) = edit { setDefaultTempo(pct) }

    fun setPreRoll(ms: Int) = edit { setPreRoll(ms) }

    fun setTranspose(semitones: Int) = edit { setTranspose(semitones) }

    fun setVelocity(pct: Int) = edit { setVelocity(pct) }

    fun setFold(on: Boolean) = edit { setFoldOutOfRange(on) }

    fun setSkipDrums(on: Boolean) = edit { setSkipDrumChannel(on) }

    fun setDynamicRange(range: DynamicRange) = edit { setDynamicRange(range) }

    fun setVelocityFloor(velocity: Int) = edit { setVelocityFloor(velocity) }

    fun setExpression(level: ExpressionLevel) = edit { setExpression(level) }

    fun setRestrike(ms: Int) = edit { setRestrike(ms) }

    fun setArtworkMonochrome(on: Boolean) = edit { setArtworkMonochrome(on) }

    fun setFetchArtworkAutomatically(on: Boolean) = edit { setFetchArtworkAutomatically(on) }

    fun setAlbumCovers(on: Boolean) = edit { setAlbumCovers(on) }

    fun setCheckForUpdates(on: Boolean) = edit { setCheckForUpdates(on) }

    fun setAppearance(appearance: Appearance) = edit { setAppearance(appearance) }

    fun setDisplayModeAfterMinute(on: Boolean) = edit { setDisplayModeAfterMinute(on) }

    fun setStandbyCanvas(canvas: StandbyCanvas) = edit { setStandbyCanvas(canvas) }

    fun setStandbyShows(shows: StandbyShows) = edit { setStandbyShows(shows) }

    fun setAlbumBackdrop(on: Boolean) = edit { setAlbumBackdrop(on) }

    fun setWebEnabled(on: Boolean) = graph.setWebEnabled(on)

    fun setWebGuests(on: Boolean) = edit { setWebGuests(on) }

    fun setWebApproveFirst(on: Boolean) = edit { setWebApproveFirst(on) }

    fun setWebOnWifi(on: Boolean) = edit { setWebOnWifi(on) }

    /** A new panel PIN: hashed and kept in the app's scope, and every session of the panel ends. */
    fun setWebPin(pin: String) {
        graph.appScope.launch { graph.web.setPin(pin) }
    }

    /** Where the connection to Steven Piano Cloud stands (Remote control's CLOUD section, v1.10 — M26). */
    val cloud: StateFlow<CloudStatus> = graph.web.cloud

    fun setCloudEnabled(on: Boolean) = graph.setCloudEnabled(on)

    /** The panel's public link through the relay, while remote access is on and the tablet enrolled. */
    fun cloudLink(settings: PianoSettings, status: CloudStatus): String? = graph.web.cloudLink(settings, status)

    /** Enrol with a code from the console: in the app's scope (leaving the page doesn't stop it); [onDone] hears how it went. */
    fun enrol(host: String, code: String, onDone: (EnrolResult) -> Unit) {
        graph.appScope.launch { onDone(graph.enrol(host, code)) }
    }

    /** Forget this cloud: remote access off, the enrolment and its key gone. */
    fun forgetCloud() = graph.forgetCloud()

    /** Print the request poster: Android's print dialog, over [activity], for the guests' [url]. */
    fun printPoster(activity: Activity, url: String) {
        PosterPrint.print(activity, url)
    }

    // ---- System (v1.18 — M50) ------------------------------------------------------------------------

    private val _system = MutableStateFlow<SystemNow?>(null)

    /** The System page's figures and the hub's System row: the tablet's last reading and what runs; null before the first. */
    val system: StateFlow<SystemNow?> = _system.asStateFlow()

    private val _systemDay = MutableStateFlow<SystemDay?>(null)

    /** The System page's day, the app's minute samples ([AppGraph.systemHistory]) as last read; null before the first read. */
    val systemDay: StateFlow<SystemDay?> = _systemDay.asStateFlow()

    private var systemRead: Job? = null

    /**
     * The tablet and what runs ([AppGraph.runningInputs], as the web panel's System page gathers them), read off the main
     * thread: the page every 5 s, the hub's row once a minute, each only while it shows; one read at a time.
     */
    fun readSystem() {
        if (systemRead?.isActive == true) return
        systemRead = viewModelScope.launch {
            try {
                _system.value = withContext(Dispatchers.IO) {
                    val at = System.currentTimeMillis()
                    val reading = graph.systemProbe.read()
                    val inputs = graph.runningInputs(at)
                    SystemNow(reading, RunningNow.of(inputs), at, loading = inputs.player.loading)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The System page couldn't read the tablet", e)
            }
        }
    }

    /** The day's samples as they stand (the page reads them as it opens, then once a minute). */
    fun readSystemDay() {
        _systemDay.value = SystemDay(graph.systemHistory.snapshot(), System.currentTimeMillis())
    }

    /**
     * The piano's live facts asked again (`firmware/docs/BLE_DIAG.md`): only of Steven Piano, connected and answering, and at
     * most once in 10 s whichever System page asks ([AppGraph.factsFloor]); the answers come in through [piano].
     */
    fun refreshPianoFacts() {
        if (PianoDiag.of(instrumentKind.value, link.value, piano.value) == null) return
        if (graph.factsFloor.take()) graph.pianoSettings.refreshFacts()
    }

    /** Find missing covers: the pieces whose album cover wasn't found are looked up again, in the app's scope. */
    fun findMissingCovers() {
        graph.appScope.launch { graph.artwork.lookAgainForCovers() }
    }

    /** Reconnect the piano: the player paused and the piano silenced, then the link made again; false during a firmware update. */
    fun reconnectPiano(): Boolean = graph.reconnectPiano()

    /** Check now: asks the server whatever the switch says, in the app's scope so leaving the tab does not stop it. */
    fun checkNow() {
        graph.appScope.launch { graph.updateChecker.checkNow() }
    }

    /**
     * Update: a release on offer is downloaded (the service shows the progress, then Android's
     * installer opens, or the device owner installs it); a download already verified goes straight
     * to the installer.
     */
    fun update(context: Context) {
        val app = context.applicationContext
        when (val state = graph.updater.state.value) {
            is UpdateState.ReadyToInstall -> graph.appScope.launch { graph.updater.install(state.manifest, state.file, app) }
            is UpdateState.Available, is UpdateState.Failed -> if (state.manifest != null) UpdateService.start(app)
            else -> Unit
        }
    }

    /** After a silent update: the piano silenced, the app starts again on the new version. */
    fun restart(context: Context) = graph.restartForUpdate(context)

    /** Whether Android lets this app open its installer; always so for the device owner. */
    fun canInstall(): Boolean = graph.updateInstaller.canRequestInstalls()

    fun installPermissionSettings(): Intent = graph.updateInstaller.installPermissionSettings()

    private fun edit(change: suspend SettingsRepository.() -> Unit) {
        graph.appScope.launch { graph.settingsRepository.change() }
    }

    private companion object {
        const val TAG = "PianoTab"
        const val MAX_QUERY = 60
        const val STOP_TIMEOUT_MS = 5_000L
        const val SELECTED = "selectedPage"
        const val OPENED = "pageOpened"
    }
}
