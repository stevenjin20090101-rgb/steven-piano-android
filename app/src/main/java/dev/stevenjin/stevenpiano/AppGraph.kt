// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import kotlinx.coroutines.withTimeoutOrNull
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.room.withTransaction
import dev.stevenjin.stevenpiano.admin.DeviceOwnerRelease
import dev.stevenjin.stevenpiano.admin.KioskController
import dev.stevenjin.stevenpiano.admin.KioskMode
import dev.stevenjin.stevenpiano.admin.OwnerRelease
import dev.stevenjin.stevenpiano.audio.AudioOut
import dev.stevenjin.stevenpiano.audio.PianoVoice
import dev.stevenjin.stevenpiano.audio.TabletSound
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.GattPianoLink
import dev.stevenjin.stevenpiano.ble.HandlerLinkExecutor
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.LockGate
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.ble.ScanThrottle
import dev.stevenjin.stevenpiano.channels.Channel
import dev.stevenjin.stevenpiano.channels.ChannelPlayer
import dev.stevenjin.stevenpiano.channels.ChannelPools
import dev.stevenjin.stevenpiano.channels.Channels
import dev.stevenjin.stevenpiano.channels.PianoLoudness
import dev.stevenjin.stevenpiano.channels.PianoVolume
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.art.ArtworkRepository
import dev.stevenjin.stevenpiano.data.builtin.BuiltInPlaylists
import dev.stevenjin.stevenpiano.data.db.PianoDatabase
import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.Importer
import dev.stevenjin.stevenpiano.diag.CrashReports
import dev.stevenjin.stevenpiano.diag.Diagnostics
import dev.stevenjin.stevenpiano.diag.DiagnosticsExporter
import dev.stevenjin.stevenpiano.diag.DiagnosticsText
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.firmware.FirmwareUpdater
import dev.stevenjin.stevenpiano.instruments.AndroidMidiBluetooth
import dev.stevenjin.stevenpiano.instruments.AndroidMidiPorts
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.instruments.InstrumentSwitch
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.MidiChoice
import dev.stevenjin.stevenpiano.instruments.MidiNames
import dev.stevenjin.stevenpiano.instruments.MidiPortLink
import dev.stevenjin.stevenpiano.instruments.LivePlayer
import dev.stevenjin.stevenpiano.instruments.LiveThru
import dev.stevenjin.stevenpiano.instruments.LiveTimingHold
import dev.stevenjin.stevenpiano.instruments.MidiDevices
import dev.stevenjin.stevenpiano.instruments.MidiKeyboard
import dev.stevenjin.stevenpiano.midi.KeyEvents
import dev.stevenjin.stevenpiano.library.LibraryPack
import dev.stevenjin.stevenpiano.net.NetworkMonitor
import dev.stevenjin.stevenpiano.piano.LedColourKeeper
import dev.stevenjin.stevenpiano.piano.LedRestKeeper
import dev.stevenjin.stevenpiano.piano.LockKeys
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoSettingsRepository
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.piano.StoredLockKeys
import dev.stevenjin.stevenpiano.player.PianoFacts
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.StartGate
import dev.stevenjin.stevenpiano.record.Recorder
import dev.stevenjin.stevenpiano.record.RecordingSession
import dev.stevenjin.stevenpiano.safety.SafetyRunner
import dev.stevenjin.stevenpiano.quiet.QuietGate
import dev.stevenjin.stevenpiano.quiet.QuietStore
import dev.stevenjin.stevenpiano.settings.Appearance
import dev.stevenjin.stevenpiano.settings.InstrumentChoice
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.settings.settingsDataStore
import dev.stevenjin.stevenpiano.studio.AppStudioLibrary
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.ModelInstaller
import dev.stevenjin.stevenpiano.studio.ModelStore
import dev.stevenjin.stevenpiano.studio.Generations
import dev.stevenjin.stevenpiano.studio.Studio
import dev.stevenjin.stevenpiano.studio.StudioReview
import dev.stevenjin.stevenpiano.update.HttpUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateChecker
import dev.stevenjin.stevenpiano.update.UpdateDownloader
import dev.stevenjin.stevenpiano.update.UpdateInstaller
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.Updater
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import dev.stevenjin.stevenpiano.web.WebPanel
import dev.stevenjin.stevenpiano.web.relay.EnrolResult
import dev.stevenjin.stevenpiano.power.HeldKeys
import dev.stevenjin.stevenpiano.power.OnHours
import dev.stevenjin.stevenpiano.power.PairFlow
import dev.stevenjin.stevenpiano.power.PiecePower
import dev.stevenjin.stevenpiano.power.PlugAdverts
import dev.stevenjin.stevenpiano.power.PlugClient
import dev.stevenjin.stevenpiano.power.PowerUse
import dev.stevenjin.stevenpiano.power.PlugSettings
import dev.stevenjin.stevenpiano.power.PlugStore
import dev.stevenjin.stevenpiano.power.RailKeeper
import dev.stevenjin.stevenpiano.power.RailPower
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * The app's objects, one per process, wired by hand. The [player] and the [pianoLink] are
 * process singletons: screens and services read them here rather than binding to a service.
 */
class AppGraph(private val app: Application) {
    /** Lives as long as the process, on the main thread: the player's state has one owner. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(app.settingsDataStore)

    /** The latest settings, for callers that cannot suspend. */
    val settings: StateFlow<PianoSettings> =
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, PianoSettings())

    /** The appearance the person chose; null until the settings have been read, so the first frame is never the wrong one. */
    val appearance: StateFlow<Appearance?> =
        settingsRepository.settings.map { it.appearance }.stateIn(appScope, SharingStarted.Eagerly, null)

    internal val database: PianoDatabase by lazy { PianoDatabase.open(app) }

    // The areas in their files. Each is made here, ahead of everything below, and holds nothing but what is lazy or asked for.
    // What the rest of the app and the areas reach stays named on the graph, so `graph.x` reads the same wherever x lives.
    internal val libraryWiring = LibraryWiring(this, app)
    internal val studioWiring = StudioWiring(this, app)
    internal val updateWiring = UpdateWiring(this, app)
    internal val webWiring = WebWiring(this, app)
    internal val powerWiring = PowerWiring(this, app)
    internal val safetyWiring = SafetyWiring(this, app)

    val library: LibraryRepository get() = libraryWiring.library
    val builtIns: BuiltInPlaylists get() = libraryWiring.builtIns
    suspend fun refreshBuiltIns() = libraryWiring.refreshBuiltIns()
    suspend fun refreshGenrePlaylists(): Unit = libraryWiring.refreshGenrePlaylists()
    suspend fun refreshStudioShelf() = libraryWiring.refreshStudioShelf()
    val importProgress: StateFlow<ImportProgress> get() = libraryWiring.importProgress
    val importer: Importer get() = libraryWiring.importer
    val artwork: ArtworkRepository get() = libraryWiring.artwork
    val libraryPack: LibraryPack get() = libraryWiring.libraryPack
    fun reportUnreadableShare(count: Int) = libraryWiring.reportUnreadableShare(count)
    fun reportFailedImport() = libraryWiring.reportFailedImport()
    fun fetchArtworkIfDue() = libraryWiring.fetchArtworkIfDue()

    val studioHistory: Generations get() = studioWiring.studioHistory
    val studio: Studio get() = studioWiring.studio
    val studioLibrary: AppStudioLibrary get() = studioWiring.studioLibrary
    val studioReview: StudioReview get() = studioWiring.studioReview
    val recorder: Recorder get() = studioWiring.recorder
    val recording: RecordingSession get() = studioWiring.recording

    val updateChecker: UpdateChecker get() = updateWiring.updateChecker
    val updateDownloader: UpdateDownloader get() = updateWiring.updateDownloader
    val updateInstaller: UpdateInstaller get() = updateWiring.updateInstaller
    val updater: Updater get() = updateWiring.updater
    suspend fun runUpdateSchedule() = updateWiring.runUpdateSchedule()
    val firmwareUpdater: FirmwareUpdater get() = updateWiring.firmwareUpdater
    fun restartForUpdate(from: Context) = updateWiring.restartForUpdate(from)

    val web: WebPanel get() = webWiring.web
    fun setWebEnabled(on: Boolean) = webWiring.setWebEnabled(on)
    fun startWebIfOn(context: Context) = webWiring.startWebIfOn(context)
    fun setCloudEnabled(on: Boolean) = webWiring.setCloudEnabled(on)
    suspend fun enrol(host: String, code: String): EnrolResult = webWiring.enrol(host, code)
    fun forgetCloud() = webWiring.forgetCloud()

    fun powerWatch(): Boolean? = powerWiring.powerWatch()
    fun startPowerWatchIfPaired() = powerWiring.startPowerWatchIfPaired()
    suspend fun askBatteryOnce(activity: Activity) = powerWiring.askBatteryOnce(activity)
    val plugStore: PlugStore get() = powerWiring.plugStore
    val plugSettings: StateFlow<PlugSettings?> get() = powerWiring.plugSettings
    val plug: PlugClient get() = powerWiring.plug
    val plugAdverts: PlugAdverts get() = powerWiring.plugAdverts
    val powerUse: PowerUse get() = powerWiring.powerUse
    val rail: RailKeeper get() = powerWiring.rail
    val railPower: RailPower get() = powerWiring.railPower
    val pairFlow: PairFlow get() = powerWiring.pairFlow
    fun startGuestPlay() = powerWiring.startGuestPlay()
    fun endGuestPlay() = powerWiring.endGuestPlay()
    fun railKeysShown(on: Boolean) = powerWiring.railKeysShown(on)
    fun railKeyPressed() = powerWiring.railKeyPressed()
    fun setPianoOn(on: Boolean) = powerWiring.setPianoOn(on)
    fun pianoWakes(): Boolean = powerWiring.pianoWakes()
    fun setPlugOn(on: Boolean) = powerWiring.setPlugOn(on)
    fun forgetPlug() = powerWiring.forgetPlug()
    val piecePower: PiecePower get() = powerWiring.piecePower
    val pianoAsleep: StateFlow<String?> get() = powerWiring.pianoAsleep
    val coolWords: StateFlow<String?> get() = powerWiring.coolWords
    fun coolLeft(): Long? = powerWiring.coolLeft()
    fun skipCooling() = powerWiring.skipCooling()
    fun offHoursRefusal(): String? = powerWiring.offHoursRefusal()
    fun setPianoOnFromAfar(on: Boolean): String? = powerWiring.setPianoOnFromAfar(on)
    fun setOnHours(hours: OnHours) = powerWiring.setOnHours(hours)
    val railHoldsNotes: StateFlow<Boolean> get() = powerWiring.railHoldsNotes
    val heldKeys: HeldKeys get() = powerWiring.heldKeys
    private val railPlays: PianoLink get() = powerWiring.railPlays
    private fun pianoResting(): Boolean = powerWiring.pianoResting()
    private fun plugWakesPiano(): Boolean = powerWiring.plugWakesPiano()
    private fun followPowerWatch() = powerWiring.followPowerWatch()
    private fun startRail() = powerWiring.startRail()

    val safety: SafetyRunner get() = safetyWiring.safety
    internal val keyTestRail: suspend () -> Boolean get() = safetyWiring.keyTestRail
    private val safetyGate: StartGate get() = safetyWiring.safetyGate
    private fun startSafety() = safetyWiring.startSafety()

    /** Whether the device is online; artwork is only ever fetched when it is. */
    val network: NetworkMonitor by lazy { NetworkMonitor(app) }

    /**
     * Android's limit of 5 Bluetooth scans in 30 s is the app's: the piano's link and the MIDI device picker
     * share this one (v1.11 — M29), on `SystemClock.elapsedRealtime`.
     */
    val scanThrottle = ScanThrottle()

    /** The piano's link: Bluetooth LE MIDI to the piano; on the emulator in debug builds, its stand-in ([Emulator]). */
    private val steven = lazy {
        Emulator.pianoLink() ?: GattPianoLink.create(
            app,
            onConnected = { address, name -> appScope.launch { settingsRepository.rememberDevice(address, name) } },
            shouldReconnect = { settings.value.autoConnect },
            isForeign = { address -> midiDevices.isForeign(address) },
            throttle = scanThrottle,
            resting = ::pianoResting,   // v1.39 — M76: off with its plug, no reconnect loop until an ARM
        )
    }

    /** The MIDI devices' own thread (v1.11 — M29): Android's MIDI callbacks, the keyboard's and the instrument's state and timers. */
    private val midiThread: HandlerThread by lazy { HandlerThread("steven-piano-midi").apply { start() } }

    /**
     * Every MIDI device but Steven Piano (v1.11 — M29): Android's MIDI service (another app's ports only in debug builds,
     * for the emulator's test device; on the emulator, its keyboard and MIDI piano beside them, [Emulator]), the picker's
     * Bluetooth search on the shared scan budget, and the addresses the piano's link must never take.
     */
    val midiDevices: MidiDevices by lazy {
        val handler = Handler(midiThread.looper)
        val android = AndroidMidiPorts(app, handler, allowVirtual = BuildConfig.DEBUG)
        MidiDevices(
            ports = Emulator.midiPorts(android, handler),
            bluetooth = midiBluetooth,
            executor = HandlerLinkExecutor(midiThread.looper),
            throttle = scanThrottle,
            log = LinkLog::warn,
            nowMs = SystemClock::elapsedRealtime,
            pianoAddress = { settings.value.lastDeviceAddress },
        )
    }

    private val midiBluetooth: AndroidMidiBluetooth by lazy { AndroidMidiBluetooth(app) }

    /**
     * The gate between the MIDI keyboard and the instrument (v1.11 — M29): Live on the Keys tab, with the flood
     * breaker, which switches Live off and remembers so.
     */
    val liveThru: LiveThru by lazy {
        LiveThru(
            player = object : LivePlayer {
                override fun external(events: KeyEvents, arrivalNanos: Long) = player.external(events, arrivalNanos)

                override fun silenceExternal() = player.silenceExternal()
            },
            log = LinkLog::warn,
            onTrip = { appScope.launch { settingsRepository.setLiveToPiano(false) } },
        ).also { keyboard.listen(it) }
    }

    /** The MIDI keyboard the person chose (Piano › Keyboard, v1.11 — M29): what it holds lights the Keys tab. */
    val keyboard: MidiKeyboard by lazy {
        MidiKeyboard(
            devices = midiDevices,
            bluetooth = midiBluetooth,
            executor = HandlerLinkExecutor(midiThread.looper),
            log = LinkLog::warn,
            remember = { chosen -> appScope.launch { settingsRepository.setKeyboard(chosen?.key, chosen?.name) } },
        )
    }

    /**
     * Steven Piano's own Bluetooth link; on an emulator in debug builds, a stand-in that logs what it would send. The
     * piano's settings and its firmware updater read this one: under a MIDI piano they see it disconnected.
     */
    val stevenLink: PianoLink by steven

    /** The keys this tablet keeps for the pianos it claimed (v1.25 — M58), sealed by the Keystore in their own store. */
    private val lockKeys: LockKeys by lazy { StoredLockKeys(app) }

    /**
     * Steven Piano's link as everything that plays sees it (v1.25 — M58): notes wait for the piano's Bluetooth lock, which
     * the piano's settings claim or prove on every connection ([LockGate]); the stop and the crash silence never wait.
     */
    internal val stevenPlays: PianoLink by lazy { LockGate(stevenLink, pianoSettings.trust, appScope) }

    /**
     * A MIDI piano as the app's output (v1.11 — M29), through Android's MIDI service, sent from its own thread at audio
     * priority.
     */
    val midiLink: MidiPortLink by lazy {
        MidiPortLink(
            devices = midiDevices,
            bluetooth = midiBluetooth,
            executor = HandlerLinkExecutor(midiThread.looper),
            log = LinkLog::warn,
            startThread = { body ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    body.run()
                }, "steven-piano-midi-out").apply {
                    isDaemon = true
                    start()
                }
            },
        )
    }

    private val link = lazy {
        val saved = settings.value
        InstrumentSwitch(
            railPlays,   // v1.26 — M60: the lock's gate, then the 24 V rail's (see the M60 block)
            midiLink,
            appScope,
            initial = if (saved.instrumentKind == InstrumentChoice.MIDI_PIANO && saved.midiOutId != null) InstrumentKind.MidiPiano else InstrumentKind.StevenPiano,
        )
    }

    /**
     * The instrument that plays (v1.11 — M29): Steven Piano's link or a MIDI piano's, as chosen ([InstrumentSwitch]).
     * Everything that plays or shows the connection reads this one.
     */
    val pianoLink: InstrumentSwitch by link

    /** The link if something has made it already, else null: the crash handler's view, which must never make one. */
    fun pianoLinkIfMade(): PianoLink? = if (link.isInitialized()) link.value else if (steven.isInitialized()) steven.value else null

    /**
     * The player; each run's timing goes to the link's trail (how late its events went out: v1.7 — M23), and
     * what it sends the piano goes to the tablet's piano sound too (v1.8 — M25). While a quiet time holds (v1.20 — M54,
     * [quiet]) it starts nothing; the safety check has its say over every start (v1.26 — M61, [safetyGate]); what its router
     * holds goes to the power watch (v1.30 — M67, [heldKeys]).
     */
    val player: Player by lazy {
        Player(
            pianoLink, library, appScope, trail = LinkLog::warn, tablet = tabletSound.sink, quiet = { quiet.holds() }, gate = safetyGate,
            held = heldKeys::record,
            railHolds = { railHoldsNotes.value },   // v1.38 — M75: a drop with the 24 V held (the piano off) is the hold's
        )
    }

    /**
     * The tablet's piano sound (v1.8 — M25): the Upright Piano KW SoundFont (downloaded on demand from the
     * release `models` through Studio's verified path into `filesDir/models/`, as the models are), the voice
     * that plays it at the device's own output rate, and when it sounds (Piano › Tablet sound).
     */
    val tabletSound: TabletSound by lazy {
        val source = Emulator.modelsSource() ?: UpdateSource.models
        val server = HttpUpdateServer(source, log = debugLog(SOUND_TAG), fileAccept = HttpUpdateServer.BINARY_ACCEPT)
        val store = ModelStore(File(app.filesDir, MODELS_DIR), listOf(ModelCatalogue.pianoSound))
        TabletSound(
            scope = appScope,
            store = store,
            installer = ModelInstaller(source, server, store, VerifiedDownloader(server)),
            online = network::isOnline,
            voice = PianoVoice(AudioOut.nativeRate(app.getSystemService(AudioManager::class.java))),
            log = { Log.i(SOUND_TAG, it) },
            poke = { soundOut.poke() },
        )
    }

    /** The tablet's speaker for the piano sound: its own thread, opened only while something sounds or a piece plays. */
    private val soundOut: AudioOut by lazy {
        AudioOut(app, tabletSound.voice, keepOpen = { tabletSound.keepOpen }, onFocusLost = ::tabletSoundFocusLost, log = { Log.i(SOUND_TAG, it) })
    }

    /** Another app took the sound (a call, a video): the tablet's piano has gone quiet; the piece pauses, and Play resumes it. */
    private fun tabletSoundFocusLost() {
        tabletSound.focusLost()
        if (player.state.value.status == PlaybackStatus.Playing) player.pause()
    }

    /** The piano's own settings over its console, read on every connection; nothing asked of it while its firmware updates (v1.18 — M46). */
    val pianoSettings: PianoSettingsRepository by lazy {
        PianoSettingsRepository(
            stevenLink,
            appScope,
            updating = { firmwareUpdater.state.value.busy },
            fullPowerChosenOff = { fullPowerChoice.getBoolean(FULL_POWER_CHOSEN_OFF, false) },
            chooseFullPower = { on -> fullPowerChoice.edit { putBoolean(FULL_POWER_CHOSEN_OFF, !on) } },
            lockKeys = lockKeys,   // v1.25 — M58: the piano's Bluetooth lock
            elapsed = SystemClock::elapsedRealtime,
        )
    }

    /**
     * M80: the strip's colour as Piano › Lights and screen chose it (the album art's, the custom colour, the palettes), on a
     * piano that says `!ledcolorproto=1`: the tint at each piece and connection. It follows the piano, the choice and the
     * loaded piece, and runs nothing on a clock.
     */
    val ledColours: LedColourKeeper by lazy {
        LedColourKeeper(
            pianoSettings,
            source = settingsRepository.settings.map { it.ledColourSource },
            piece = player.state.map { it.piece?.pieceId },
            coverOf = artwork::ledCover,
            follow = { source -> appScope.launch { settingsRepository.setLedColourSource(source) } },
            scope = appScope,
        )
    }

    /**
     * M81: the strip between pieces as Piano › Lights and screen chose it, on a piano that says `!ledrestproto=1`: `ledrest 1`
     * while nothing plays (Solid colour, in Reactive), `ledrest 0` as soon as something does. What plays: a piece from its
     * start (its pause before the first note too) until it is over, paused included ([Player.pieceSounding]); the Keys tab
     * on screen, which is also the only time a MIDI keyboard plays the piano (Live opens only then); Test every key, which
     * is also the safety check's sweep. Guest play holds it at 0: the piano's lock says guest play is on, or the tablet let go
     * of the piano for it ([RailKeeper.guestUntil]). It follows those and runs nothing on a clock.
     */
    val ledRest: LedRestKeeper by lazy {
        LedRestKeeper(
            piano = pianoSettings.state,
            choice = settingsRepository.settings.map { it.ledRest },
            playing = combine(
                player.pieceSounding,
                liveThru.state.map { it.onScreen },
                remote.keyTest.state.map { it.running },
            ) { piece, keys, test -> piece || keys || test },
            guest = combine(rail.guestUntil, pianoSettings.lock) { until, lock -> until != null || lock.guestSeconds > 0 },
            send = pianoSettings::rest,
            scope = appScope,
        )
    }

    /** The channels, in their order on screen (Calm, Epic, Baroque…), read from the app's assets the first time, off the main thread. */
    val channels: List<Channel> by lazy { Channels.load(app, builtIns.lists) }

    /** Every channel's pool and card, kept up to date with the library. */
    val channelPools: ChannelPools by lazy { ChannelPools({ channels }, library.all(), appScope) }

    /** Endless play from a channel's pool, at the channel's volume. */
    val channelPlayer: ChannelPlayer by lazy {
        ChannelPlayer(
            player,
            pools = { key -> channelPools.summary(key)?.ids },
            volumeOf = { key -> settings.value.channelVolume(key) },
            piano = pianoVolume,
            scope = appScope,
            gate = safetyGate,   // v1.26 — M61
        )
    }

    /** A person's Full power choice (v1.22), kept apart from the settings: on until switched off. */
    private val fullPowerChoice by lazy { app.getSharedPreferences(FULL_POWER_CHOICE, android.content.Context.MODE_PRIVATE) }

    /**
     * The piano's volume for a channel: held while it plays and put back after, with Full power
     * (which the firmware turns off below 100 %) as it was; never saved on the piano.
     */
    private val pianoVolume = object : PianoVolume {
        override fun current(): PianoLoudness? {
            val values = (pianoSettings.state.value as? PianoState.Ready)?.values ?: return null
            val volume = values[PIANO_VOLUME]?.toFloatOrNull()?.roundToInt() ?: return null
            return PianoLoudness(volume, fullPower = values[PIANO_FULL_POWER] == "1")
        }

        override fun hold(pct: Int) = pianoSettings.holdTemporarily(PIANO_VOLUME, pct)

        override fun release(previous: PianoLoudness) {
            pianoSettings.releaseTemporary(PIANO_VOLUME, previous.volume)
            if (previous.fullPower) pianoSettings.releaseTemporary(PIANO_FULL_POWER, 1)
        }
    }

    /** Kiosk mode (Piano › Kiosk): the screen locked to the app as device owner, the app as the home screen, its PIN. */
    val kiosk: KioskMode by lazy {
        KioskMode(
            KioskController.of(app),
            settingsRepository,
            kioskEnabled = settingsRepository.settings.map { it.kioskEnabled },
            scope = appScope,
            ownerRelease = object : OwnerRelease {
                override fun asked() = DeviceOwnerRelease.asked(app)

                override fun release() = DeviceOwnerRelease.release(app)
            },
        )
    }

    /**
     * The adb way back (`debug.stevenpiano.releaseowner`), asked again whenever the activity starts or
     * is sent an intent: Android will not force-stop a device owner's app, so `am start` is how adb
     * reaches a running one. Off the main thread (getprop).
     */
    fun releaseOwnerIfAsked() {
        appScope.launch(Dispatchers.IO) {
            try {
                kiosk.releaseIfAsked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The device owner couldn't be given back", e)
            }
        }
    }

    /** The schedules table (v1.20 — M54: the quiet times' blocks alone; timed plays were removed), each save of the quiet times one transaction. */
    val quietStore: QuietStore by lazy { QuietStore(this, database.schedules()) { block -> database.withTransaction { block() } } }

    /**
     * Quiet times' gate (Piano › Quiet times, v1.20 — M54): whether a block is on now and whether Play anyway lifted it;
     * the player starts nothing while one holds, and stops as a block begins ([start]).
     */
    val quiet: QuietGate by lazy { QuietGate(quietStore.quietRows) }

    /** The app's own crash reports, which [App]'s crash handler writes (on the device only). */
    val crashReports: CrashReports by lazy { Diagnostics.crashReports(app) }

    /**
     * Share diagnostics' zip: about the app and device, the preferences, the link's last lines and
     * the crash reports; nothing from the library.
     */
    val diagnostics: DiagnosticsExporter by lazy {
        DiagnosticsExporter(
            File(app.cacheDir, DIAGNOSTICS_DIR),
            crashReports,
            LinkLog.shared,
            about = {
                DiagnosticsText.about(
                    Diagnostics.facts(),
                    deviceOwner = updateInstaller.isDeviceOwner(),
                    update = updateChecker.state.value,
                    checkForUpdates = settings.value.checkForUpdates,
                    lastCheckedAt = updateChecker.lastCheckedAt?.let { DiagnosticsText.stamp(it) },
                    link = pianoLinkIfMade()?.state?.value,
                    exportedAt = DiagnosticsText.stamp(System.currentTimeMillis()),
                    instrument = DiagnosticsText.instrumentLine(settings.value),
                    keyboard = DiagnosticsText.keyboardLine(keyboard.state.value, liveThru.state.value),
                )
            },
            settings = { DiagnosticsText.settings(settings.value) },
        )
    }

    /** What the panel and the relay ask for from afar, the system reading, Listen and Test every key (v1.27 — M63). */
    val remote = RemoteActions(this, app)

    /** When the newest crash report was written, read once at start (null: none). */
    private val latestCrash = MutableStateFlow<Long?>(null)

    /** The Library's "The app crashed last time" banner: a report newer than the last one answered. */
    val crashNotice: StateFlow<Boolean> = combine(latestCrash, settingsRepository.crashNoticeSeenAt) { latest, seen ->
        latest != null && latest > seen
    }.stateIn(appScope, SharingStarted.Eagerly, false)

    /** The banner was answered (shared or dismissed): it stays away until the next crash. */
    fun answerCrashNotice() {
        val latest = latestCrash.value ?: return
        appScope.launch { settingsRepository.markCrashNoticeSeen(latest) }
    }

    /**
     * From [App.onCreate]: settings flow into the player; the queue's repeat starts as it was left
     * and is remembered whenever it changes (shuffle's old key is removed once: v1.26 — M61); the
     * piano is reached if the person allows it; files a crashed import left behind are swept away.
     */
    @OptIn(FlowPreview::class)
    fun start() {
        val startedAt = System.currentTimeMillis()
        // First, so a tablet in kiosk mode locks as soon as it can: the adb way back, then the device owner.
        appScope.launch(Dispatchers.IO) {
            try {
                kiosk.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Kiosk mode couldn't start", e)
            }
        }
        appScope.launch(Dispatchers.IO) {
            runCatching { ImportLimits.sweepStale(app.cacheDir, app.filesDir, before = startedAt) }
            runCatching { updateDownloader.sweep(before = startedAt) }
            runCatching { LibraryPack.sweep(File(app.cacheDir, LibraryPack.CACHE_DIR), before = startedAt) }
            latestCrash.value = runCatching { crashReports.latestAt() }.getOrNull()
        }
        // The built-in playlists follow the library: now (the first query opens the database), and two seconds after a run
        // of renames ends (imports refresh them from ImportService).
        appScope.launch { refreshBuiltIns() }
        appScope.launch { library.namesChanged.debounce(BUILT_INS_SETTLE_MS).collect { refreshBuiltIns() } }
        appScope.launch { library.genresChanged.debounce(GENRES_SETTLE_MS).collect { refreshGenrePlaylists() } }   // v1.37 — M73
        pianoSettings.start()
        ledColours.start()   // M80
        appScope.launch {
            try {
                settingsRepository.forgetShuffle()   // v1.26 — M61: shuffle is gone; its stored value goes once
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Shuffle's old setting couldn't be removed")
            }
            val saved = settingsRepository.settings.first()
            player.setRepeat(saved.repeat)
            player.state.map { it.queue.repeat }.distinctUntilChanged().collect { repeat ->
                if (settingsRepository.settings.first().repeat != repeat) settingsRepository.setRepeat(repeat)
            }
        }
        appScope.launch {
            // Each value reaches the player when it changes, not whenever another setting does: a
            // channel's velocity (ChannelPlayer) stands until the person changes Velocity itself.
            var applied: PianoSettings? = null
            settingsRepository.settings.collect { s ->
                val was = applied
                if (was?.defaultTempoPct != s.defaultTempoPct) player.setDefaultTempo(s.defaultTempoPct)
                if (was?.preRollMs != s.preRollMs) player.setPreRoll(s.preRollMs)
                if (was?.transpose != s.transpose) player.setTranspose(s.transpose)
                if (was?.velocityPct != s.velocityPct) player.setVelocity(s.velocityPct)
                if (was?.foldOutOfRange != s.foldOutOfRange) player.setFold(s.foldOutOfRange)
                if (was?.skipDrumChannel != s.skipDrumChannel) player.setSkipDrums(s.skipDrumChannel)
                if (was?.performance != s.performance) player.setPerformance(s.performance)   // v1.16 — M44: from the next piece
                applied = s
            }
        }
        // The piano's repeat period (v1.16 — M44): the re-strike time on Auto, with which the player shapes the next piece.
        appScope.launch {
            pianoSettings.state.map { PianoFacts.read((it as? PianoState.Ready)?.facts?.get(REPEAT_FACT)) }
                .distinctUntilChanged()
                .collect(player::setPianoFacts)
        }
        // The tablet's piano sound (v1.8 — M25) follows its mode, its volume and the piano's link, and holds
        // its output open while a piece plays.
        soundOut.start()
        tabletSound.start()
        appScope.launch {
            combine(settingsRepository.settings, pianoLink.state) { s, link -> Triple(s.tabletSound, s.tabletVolume, link is LinkState.Connected) }
                .distinctUntilChanged()
                .collect { (mode, volume, connected) -> tabletSound.follow(mode, volume, connected) }
        }
        appScope.launch { player.state.map { it.status == PlaybackStatus.Playing }.distinctUntilChanged().collect(tabletSound::playing) }
        channelPools.summaries   // the channels' pools are worked out from the start, for the Library's first look
        channelPlayer.start()
        remote.startSystemSamples()
        web.start()
        firmwareUpdater.start()
        // At a quiet time's start anything playing stops, with the stop sequence; on a start inside one too (v1.20 — M54).
        // Not while the piano's firmware is updated: the updater stopped the player first, and the link is its.
        quiet.start(appScope) { if (!player.locked) player.stop() }
        // A clock or time zone changed by hand moves the blocks' edges: the gate weighs the quiet again at once (v1.24 — M57).
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = quiet.clockChanged()
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        studio.start()
        remote.followListening()   // v1.23 — M55
        startSafety()   // v1.26 — M61
        followPowerWatch()   // v1.39 — M77
        appScope.launch {
            val s = settingsRepository.settings.first()
            // The keyboard first (v1.11 — M29): its address is foreign to the piano's link before that link scans.
            midiDevices.start()
            keyboard.restore(KeyboardState.Chosen.saved(s.keyboardId, s.keyboardName))
            restoreInstrument(s)
            followLive()
            remote.followPlaying()   // v1.23 — M55: since when nothing plays, for a key held on
            startRail()   // v1.26 — M60: the 24 V rail on its smart plug (see the M60 block)
            ledRest.start()   // M81: after what it follows (the player, Live, the key test, the rail), before the piano connects
            launch { recording.recoverPending() }   // takes a crash left behind (v1.11 — M29), beside the rest
            // Permission is only ever asked for on the Piano tab; without it, launch stays quiet. A MIDI piano (v1.11 — M29)
            // connects with the same switch; by cable it needs no permission.
            if (s.autoConnect) {
                if (pianoLink.kind.value == InstrumentKind.MidiPiano) {
                    pianoLink.connect(null)
                } else if (BlePermissions.missing(app).isEmpty()) {
                    // v1.39 — M76: with the plug the piano is off until the rail wakes it (the On hours' want, or a Play).
                    withTimeoutOrNull(PLUG_READ_MS) { plugSettings.first { it != null } }
                    if (!plugWakesPiano()) pianoLink.connect(s.lastDeviceAddress)
                }
            }
        }
    }

    /**
     * The instrument remembered (v1.11 — M29): a MIDI piano chosen before is chosen again (it connects with the rest, as
     * Steven Piano does), and the player takes its rules.
     */
    private fun restoreInstrument(s: PianoSettings) {
        val choice = midiChoiceOf(s) ?: return
        if (midiDevices.isPiano(choice.address, choice.name)) return   // never Steven Piano as a MIDI piano: Steven Piano plays
        midiLink.choose(choice)
        if (s.instrumentKind != InstrumentChoice.MIDI_PIANO) return
        pianoLink.select(InstrumentKind.MidiPiano)
        player.setProfile(InstrumentKind.MidiPiano.profile)
    }

    /** The MIDI piano the settings remember, or null. */
    private fun midiChoiceOf(s: PianoSettings): MidiChoice? {
        val id = s.midiOutId ?: return null
        val transport = MidiNames.transportOf(id) ?: return null
        return MidiChoice(id, MidiNames.clean(s.midiOutName).ifEmpty { "MIDI piano" }, transport, MidiNames.addressOf(id))
    }

    /**
     * Another instrument plays from now on (v1.11 — M29; Piano › Instrument, which asks for the PIN in kiosk mode): the
     * one playing is paused and silenced first (its stop sequence written), and let go; then the new one is chosen, the
     * player takes its rules, the choice is remembered, and the new one connects. Nothing while the player is locked for a
     * firmware update. [choice]: the MIDI piano, for [InstrumentKind.MidiPiano].
     */
    fun chooseInstrument(kind: InstrumentKind, choice: MidiChoice? = null) {
        if (player.locked) return
        if (kind == InstrumentKind.MidiPiano && choice == null && midiLink.choice == null) return
        appScope.launch {
            player.pauseAndFlush(DISCONNECT_FLUSH_MS)
            pianoLink.disconnect()
            if (choice != null) midiLink.choose(choice)
            pianoLink.select(kind)
            player.setProfile(kind.profile)
            val chosen = choice ?: midiLink.choice
            settingsRepository.setInstrument(
                if (kind == InstrumentKind.MidiPiano) InstrumentChoice.MIDI_PIANO else InstrumentChoice.STEVEN_PIANO,
                chosen?.key,
                chosen?.name,
            )
            LinkLog.warn("Instrument: " + if (kind == InstrumentKind.MidiPiano) "a MIDI piano" else "Steven Piano")
            if (kind == InstrumentKind.StevenPiano) {
                if (BlePermissions.missing(app).isEmpty()) pianoLink.connect(settings.value.lastDeviceAddress)
            } else {
                pianoLink.connect(null)
            }
        }
    }

    /**
     * Live (v1.11 — M29) follows its switch as remembered, the keyboard's connection, and whether anything can play
     * the keys (the piano connected, or the tablet's own sound); while it plays Steven Piano, the piano's timing
     * scatter is held at 0 ([LiveTimingHold]).
     */
    private fun followLive() {
        val live = liveThru
        appScope.launch { settingsRepository.settings.map { it.liveToPiano }.distinctUntilChanged().collect(live::setWanted) }
        appScope.launch { keyboard.state.map { it.connected }.distinctUntilChanged().collect(live::setKeyboard) }
        // A keyboard that is the instrument too never plays through: every key would sound twice, or loop.
        appScope.launch {
            combine(pianoLink.kind, settingsRepository.settings) { kind, s -> kind == InstrumentKind.MidiPiano && s.midiOutId != null && s.midiOutId == s.keyboardId }
                .distinctUntilChanged()
                .collect(live::setLooped)
        }
        appScope.launch {
            combine(pianoLink.state, tabletSound.state) { link, sound -> link is LinkState.Connected || sound.active }
                .distinctUntilChanged()
                .collect(live::setTarget)
        }
        val scatter = LiveTimingHold(pianoSettings::holdTemporarily, pianoSettings::releaseTemporary)
        appScope.launch {
            combine(live.state.map { it.open }.distinctUntilChanged(), pianoSettings.state) { open, piano -> open to piano }
                .collect { (open, piano) -> scatter.update(open, piano) }
        }
    }

    /**
     * All keys off (Piano › Instrument, v1.11 — M29): the instrument's stop sequence through the player, at once; on
     * Steven Piano also its own `off` over its console, where it has one (as Firmware and status's All keys off).
     */
    fun allKeysOff() {
        player.allKeysOff()
        if (pianoLink.kind.value == InstrumentKind.StevenPiano) pianoSettings.action(PianoAction.AllKeysOff)
    }

    /** Disconnect from the Piano tab: the player pauses first, so the piano is silenced, then the link drops. */
    fun disconnectPiano() {
        appScope.launch {
            player.pauseAndFlush(DISCONNECT_FLUSH_MS)
            pianoLink.disconnect()
        }
    }

    /**
     * The web panel's System page's Reconnect (v1.18 — M46): as [disconnectPiano] (the player paused and the piano
     * silenced first), then the instrument connects again as [chooseInstrument] connects it. Nothing while the player is
     * locked for a firmware update; false then.
     */
    fun reconnectPiano(): Boolean {
        if (player.locked || firmwareUpdater.state.value.busy) return false
        appScope.launch {
            player.pauseAndFlush(DISCONNECT_FLUSH_MS)
            pianoLink.disconnect()
            LinkLog.warn("Reconnecting, asked from the web panel")
            if (pianoLink.kind.value == InstrumentKind.StevenPiano) {
                if (BlePermissions.missing(app).isEmpty()) pianoLink.connect(settings.value.lastDeviceAddress)
            } else {
                pianoLink.connect(null)
            }
        }
        return true
    }

    internal companion object {
        const val TAG = "AppGraph"
        const val SOUND_TAG = "TabletSound"
        const val MODELS_DIR = "models"
        const val DIAGNOSTICS_DIR = "diagnostics"
        const val DISCONNECT_FLUSH_MS = 300L

        /** The piano's own volume setting, which a channel holds while it plays. */
        const val PIANO_VOLUME = "volume"

        /** Whether a person switched Full power off (v1.22): its own small preference file, read when the piano connects. */
        const val FULL_POWER_CHOICE = "full_power"

        const val FULL_POWER_CHOSEN_OFF = "chosenOff"

        /** The piano's Full power, which the firmware turns off when the volume goes below 100. */
        const val PIANO_FULL_POWER = "fullpower"

        /** The piano's steady repeat period, a fact of its `dump` (v1.16 — M44). */
        const val REPEAT_FACT = "repeatms"

        /**
         * How long a sweep waits for the 24 V rail to come up before it gives up (v1.26). v1.38 — M75: 45 s, as a piano
         * woken from off by its power plug may take the rail's 30 s for its link, then its CHECK.
         */
        const val RAIL_AWAIT_MS = 45_000L

        const val PLUG_READ_MS = 2_000L

        /** Renames come in runs (a dialog's fields, a tidy-up): the built-in playlists wait for the run to end. */
        const val BUILT_INS_SETTLE_MS = 2_000L

        /** The genre playlists are refreshed this long after the last of a run of genres arriving (one a search, 3.5 s apart). */
        const val GENRES_SETTLE_MS = 5_000L

        /** Debug builds log each request and check under [tag]; release builds log no address. */
        fun debugLog(tag: String): ((String) -> Unit)? = if (BuildConfig.DEBUG) { line -> Log.d(tag, line) } else null
    }
}
