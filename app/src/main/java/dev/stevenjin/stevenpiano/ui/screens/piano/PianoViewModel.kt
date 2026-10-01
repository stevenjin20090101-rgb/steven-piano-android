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
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
import dev.stevenjin.stevenpiano.audio.TabletSoundState
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.firmware.FirmwarePiano
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.MidiChoice
import dev.stevenjin.stevenpiano.instruments.MidiDeviceRef
import dev.stevenjin.stevenpiano.instruments.MidiPicker
import dev.stevenjin.stevenpiano.instruments.MidiPurpose
import dev.stevenjin.stevenpiano.instruments.MidiScan
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.schedule.NextSchedule
import dev.stevenjin.stevenpiano.settings.Appearance
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.StandbyCanvas
import dev.stevenjin.stevenpiano.settings.StandbyShows
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.settings.WideLayout
import dev.stevenjin.stevenpiano.studio.AudioSource
import dev.stevenjin.stevenpiano.studio.ModelEntry
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.studio.StudioSupport
import dev.stevenjin.stevenpiano.service.FirmwareService
import dev.stevenjin.stevenpiano.service.UpdateService
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.FirmwareActions
import dev.stevenjin.stevenpiano.update.UpdateState
import dev.stevenjin.stevenpiano.web.PosterPrint
import dev.stevenjin.stevenpiano.web.WebStatus
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import dev.stevenjin.stevenpiano.web.relay.EnrolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    /** Studio (v1.7 — M23): whether it runs here, its models, its jobs, and the pieces waiting for Keep or Discard. */
    val studioSupport: StateFlow<StudioSupport> = graph.studio.availability.support
    val studioModels: StateFlow<Set<String>> = graph.studio.models.installed
    val studioJobs: StateFlow<List<StudioJob>> = graph.studio.jobs.jobs
    val studioUndecided: StateFlow<Set<Long>> = graph.studio.review.undecided
    val studioDiscarded: StateFlow<Set<Long>> = graph.studio.review.discardedNow

    /** Asks once per process whether Studio runs here (the hub and the page need to know). */
    fun checkStudio() = graph.studio.availability.check()

    fun downloadModel(model: ModelEntry) {
        graph.studio.download(model)
    }

    fun removeModel(model: ModelEntry) {
        graph.studio.remove(model)
    }

    fun cancelStudioJob(id: Long) = graph.studio.cancel(id)

    /** A recording the person picked: its read grant kept for the job (given back after), and queued. */
    fun transcribe(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        graph.studio.transcribe(AudioSource.Document(uri))
    }

    /** The tablet's piano sound (v1.8 — M25): its mode and volume as set, the piano's link, the SoundFont and its download. */
    val tabletSound: StateFlow<TabletSoundState> = graph.tabletSound.state

    fun setTabletSound(mode: TabletSoundMode) = edit { setTabletSound(mode) }

    fun setTabletVolume(pct: Int) = edit { setTabletVolume(pct) }

    fun downloadTabletSound() = graph.tabletSound.download()

    fun cancelTabletSound() = graph.tabletSound.cancelDownload()

    fun removeTabletSound() = graph.tabletSound.remove()

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

    /** The page open beside the hub on wide screens (Feel at first), and on phones the one last opened. */
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

    fun setNoteDisplay(display: NoteDisplay) = edit { setNoteDisplay(display) }

    fun setWideLayout(layout: WideLayout) = edit { setWideLayout(layout) }

    fun setDefaultTempo(pct: Int) = edit { setDefaultTempo(pct) }

    fun setPreRoll(ms: Int) = edit { setPreRoll(ms) }

    fun setTranspose(semitones: Int) = edit { setTranspose(semitones) }

    fun setVelocity(pct: Int) = edit { setVelocity(pct) }

    fun setFold(on: Boolean) = edit { setFoldOutOfRange(on) }

    fun setSkipDrums(on: Boolean) = edit { setSkipDrumChannel(on) }

    fun setArtworkMonochrome(on: Boolean) = edit { setArtworkMonochrome(on) }

    fun setFetchArtworkAutomatically(on: Boolean) = edit { setFetchArtworkAutomatically(on) }

    fun setFingering(on: Boolean) = edit { setFingering(on) }

    fun setChordNames(on: Boolean) = edit { setChordNames(on) }

    fun setHandColours(on: Boolean) = edit { setHandColours(on) }

    fun setCheckForUpdates(on: Boolean) = edit { setCheckForUpdates(on) }

    fun setAppearance(appearance: Appearance) = edit { setAppearance(appearance) }

    fun setDisplayModeAfterMinute(on: Boolean) = edit { setDisplayModeAfterMinute(on) }

    fun setStandbyCanvas(canvas: StandbyCanvas) = edit { setStandbyCanvas(canvas) }

    fun setStandbyShows(shows: StandbyShows) = edit { setStandbyShows(shows) }

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
        const val STOP_TIMEOUT_MS = 5_000L
        const val SELECTED = "selectedPage"
        const val OPENED = "pageOpened"
    }
}
