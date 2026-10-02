// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
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
import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.ble.ScanThrottle
import dev.stevenjin.stevenpiano.channels.Channel
import dev.stevenjin.stevenpiano.channels.ChannelPlayer
import dev.stevenjin.stevenpiano.channels.ChannelPools
import dev.stevenjin.stevenpiano.channels.Channels
import dev.stevenjin.stevenpiano.channels.PianoLoudness
import dev.stevenjin.stevenpiano.channels.PianoVolume
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.PieceFiles
import dev.stevenjin.stevenpiano.data.art.ArtFiles
import dev.stevenjin.stevenpiano.data.art.ArtworkRepository
import dev.stevenjin.stevenpiano.data.builtin.BuiltInCatalogue
import dev.stevenjin.stevenpiano.data.builtin.BuiltInPlaylists
import dev.stevenjin.stevenpiano.data.db.PianoDatabase
import dev.stevenjin.stevenpiano.data.db.TextRepair
import dev.stevenjin.stevenpiano.data.db.UploadRepair
import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.data.imports.Importer
import dev.stevenjin.stevenpiano.diag.CrashReports
import dev.stevenjin.stevenpiano.diag.Diagnostics
import dev.stevenjin.stevenpiano.diag.DiagnosticsExporter
import dev.stevenjin.stevenpiano.diag.DiagnosticsText
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.firmware.FakeFirmwareServer
import dev.stevenjin.stevenpiano.firmware.FakeOta
import dev.stevenjin.stevenpiano.firmware.FirmwareKeys
import dev.stevenjin.stevenpiano.firmware.FirmwarePlayer
import dev.stevenjin.stevenpiano.firmware.FirmwareUpdater
import dev.stevenjin.stevenpiano.firmware.batteryState
import dev.stevenjin.stevenpiano.instruments.AndroidMidiBluetooth
import dev.stevenjin.stevenpiano.instruments.AndroidMidiPorts
import dev.stevenjin.stevenpiano.instruments.CombinedMidiPorts
import dev.stevenjin.stevenpiano.instruments.EmulatedMidiPorts
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
import dev.stevenjin.stevenpiano.library.LibraryOverride
import dev.stevenjin.stevenpiano.midi.KeyEvents
import dev.stevenjin.stevenpiano.library.LibraryPack
import dev.stevenjin.stevenpiano.library.OfferedPacks
import dev.stevenjin.stevenpiano.net.NetworkMonitor
import dev.stevenjin.stevenpiano.net.WikipediaClient
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoSettingsRepository
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.record.Recorder
import dev.stevenjin.stevenpiano.record.RecordingPieces
import dev.stevenjin.stevenpiano.record.RecordingSession
import dev.stevenjin.stevenpiano.record.RecordingStore
import dev.stevenjin.stevenpiano.schedule.Schedules
import dev.stevenjin.stevenpiano.service.ArtworkService
import dev.stevenjin.stevenpiano.service.LibraryService
import dev.stevenjin.stevenpiano.service.StudioService
import dev.stevenjin.stevenpiano.service.WebService
import dev.stevenjin.stevenpiano.settings.Appearance
import dev.stevenjin.stevenpiano.settings.InstrumentChoice
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.settings.settingsDataStore
import dev.stevenjin.stevenpiano.studio.AppStudioLibrary
import dev.stevenjin.stevenpiano.studio.AudioDecoder
import dev.stevenjin.stevenpiano.studio.AudioSource
import dev.stevenjin.stevenpiano.studio.LibrarySeeds
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.ModelInstaller
import dev.stevenjin.stevenpiano.studio.ModelStore
import dev.stevenjin.stevenpiano.studio.ReviewPlayer
import dev.stevenjin.stevenpiano.studio.StoredReview
import dev.stevenjin.stevenpiano.studio.Studio
import dev.stevenjin.stevenpiano.studio.StudioAvailability
import dev.stevenjin.stevenpiano.studio.StudioPieces
import dev.stevenjin.stevenpiano.studio.StudioReview
import dev.stevenjin.stevenpiano.update.HttpUpdateServer
import dev.stevenjin.stevenpiano.update.ModelsOverride
import dev.stevenjin.stevenpiano.update.UpdateChecker
import dev.stevenjin.stevenpiano.update.UpdateDownloader
import dev.stevenjin.stevenpiano.update.UpdateInstaller
import dev.stevenjin.stevenpiano.update.UpdateOverride
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.Updater
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import dev.stevenjin.stevenpiano.web.WebPanel
import dev.stevenjin.stevenpiano.web.relay.CloudAddress
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import dev.stevenjin.stevenpiano.web.relay.EnrolResult
import dev.stevenjin.stevenpiano.web.relay.Enrolment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
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

    /** Opening it runs the one-off repair of text imported before 1.2 capped it ([TextRepair]), before any query. */
    private val database: PianoDatabase by lazy {
        PianoDatabase.open(app) { db ->
            TextRepair.runOnce(
                db,
                due = { runBlocking { !settingsRepository.textRepairDone() } },
                done = { runBlocking { settingsRepository.markTextRepairDone() } },
                log = { Log.w(TAG, it) },
            )
        }
    }
    private val pieceFiles = PieceFiles(app.filesDir)
    val library: LibraryRepository by lazy { LibraryRepository(database, pieceFiles) }

    /** The built-in playlists (Popular, Recognisable, Epic on piano), read from the app's assets the first time, off the main thread. */
    val builtIns: BuiltInPlaylists by lazy { BuiltInCatalogue.load(app) }

    /** Sets the built-in playlists' pieces from the library as it is now. A failure is logged and changes nothing: the next refresh tries again. */
    suspend fun refreshBuiltIns() = withContext(Dispatchers.IO) {
        try {
            builtIns.refresh(library)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The built-in playlists couldn't be refreshed", e)
        }
    }

    /**
     * The one-time repair of uploads imported before 1.10.1 ([UploadRepair], v1.10.1 — M28, D4), off the main
     * thread, unless it has run already (`uploadRepairDone`); its lines go to the link's trail, naming the
     * playlists in debug builds only. True when it named some artist. A failure is logged and leaves it due, so
     * the next start tries again (what it did by then stays: those pieces are in their playlist).
     */
    private suspend fun repairUploadsOnce(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (settingsRepository.uploadRepairDone()) return@withContext false
            val done = UploadRepair.run(library, log = LinkLog::warn, named = { name -> if (BuildConfig.DEBUG) " $name" else "" })
            settingsRepository.markUploadRepairDone()
            done.any { it.filled > 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The repair of older uploads failed; it runs again at the next start", e)
            false
        }
    }

    private val importState = MutableStateFlow(ImportProgress.Idle)
    val importProgress: StateFlow<ImportProgress> = importState.asStateFlow()
    val importer: Importer by lazy { Importer(library, pieceFiles, importState) }

    /** Whether the device is online; artwork is only ever fetched when it is. */
    val network: NetworkMonitor by lazy { NetworkMonitor(app) }

    /** Portraits, notes, playlist photos and roll cards (the only network use: two Wikimedia hosts). */
    val artwork: ArtworkRepository by lazy {
        ArtworkRepository(app, database.artwork(), library, ArtFiles(app.filesDir), WikipediaClient(), network, appScope)
    }

    /**
     * Android's limit of 5 Bluetooth scans in 30 s is the app's: the piano's link and the MIDI device picker
     * share this one (v1.11 — M29), on `SystemClock.elapsedRealtime`.
     */
    val scanThrottle = ScanThrottle()

    private val steven = lazy {
        if (LoggingPianoLink.isWanted()) {
            LoggingPianoLink()
        } else {
            GattPianoLink.create(
                app,
                onConnected = { address, name -> appScope.launch { settingsRepository.rememberDevice(address, name) } },
                shouldReconnect = { settings.value.autoConnect },
                isForeign = { address -> midiDevices.isForeign(address) },
                throttle = scanThrottle,
            )
        }
    }

    /** The MIDI devices' own thread (v1.11 — M29): Android's MIDI callbacks, the keyboard's and the instrument's state and timers. */
    private val midiThread: HandlerThread by lazy { HandlerThread("steven-piano-midi").apply { start() } }

    /**
     * The emulator's MIDI keyboard and MIDI piano (debug builds on an emulator only, as [LoggingPianoLink]; v1.11 — M29):
     * the activity feeds the keyboard the bytes adb gives it.
     */
    val emulatedMidi: EmulatedMidiPorts? by lazy { if (EmulatedMidiPorts.isWanted()) EmulatedMidiPorts(Handler(midiThread.looper)) else null }

    /**
     * Every MIDI device but Steven Piano (v1.11 — M29): Android's MIDI service (another app's ports only in debug builds,
     * for the emulator's test device), the picker's Bluetooth search on the shared scan budget, and the addresses the
     * piano's link must never take.
     */
    val midiDevices: MidiDevices by lazy {
        val handler = Handler(midiThread.looper)
        val android = AndroidMidiPorts(app, handler, allowVirtual = BuildConfig.DEBUG)
        val emulated = emulatedMidi
        MidiDevices(
            ports = if (emulated != null) CombinedMidiPorts(android, emulated) else android,
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
            stevenLink,
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
     * what it sends the piano goes to the tablet's piano sound too (v1.8 — M25).
     */
    val player: Player by lazy { Player(pianoLink, library, appScope, trail = LinkLog::warn, tablet = tabletSound.sink) }

    /**
     * The tablet's piano sound (v1.8 — M25): the Upright Piano KW SoundFont (downloaded on demand from the
     * release `models` through Studio's verified path into `filesDir/models/`, as the models are), the voice
     * that plays it at the device's own output rate, and when it sounds (Piano › Playback › TABLET SOUND).
     */
    val tabletSound: TabletSound by lazy {
        val source = ModelsOverride.source() ?: UpdateSource.models
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

    /**
     * Steven's library from GitHub (v1.10 — M27): the pack's manifest and zip from this repository's release
     * `library` (on the emulator in debug builds, the server [LibraryOverride] names), its pieces through the
     * importer, the version loaded kept in the settings. The Library's buttons and the console's
     * `library.load` call [LibraryPack.load]; [LibraryService] holds the foreground while it runs.
     */
    val libraryPack: LibraryPack by lazy {
        val source = LibraryOverride.source() ?: UpdateSource.library
        val server = HttpUpdateServer(source, log = debugLog(LIBRARY_TAG), fileAccept = HttpUpdateServer.BINARY_ACCEPT)
        LibraryPack(
            source = source,
            server = server,
            downloader = VerifiedDownloader(server),
            offered = OfferedPacks(File(app.filesDir, LibraryPack.FILES_DIR)),
            cacheDir = File(app.cacheDir, LibraryPack.CACHE_DIR),
            loadedVersion = settingsRepository.settings.map { it.libraryPackVersion }.distinctUntilChanged(),
            recordLoaded = settingsRepository::setLibraryPackVersion,
            import = ::importLibraryPack,
            online = network.online,
            scope = appScope,
            start = { everything -> LibraryService.start(app, everything) },
            log = { Log.i(LIBRARY_TAG, it) },
        )
    }

    /** The pack's pieces through the importer; then, as after the app's own imports, the built-in playlists and artwork. */
    private suspend fun importLibraryPack(source: ImportSource.LocalZip): ImportProgress {
        val result = importer.import(app, source)
        if (result.piecesChanged) {
            refreshBuiltIns()
            if (settingsRepository.settings.first().fetchArtworkAutomatically) ArtworkService.start(app, force = false)
        }
        return result
    }

    /** The piano's own settings over its console, read on every connection. */
    val pianoSettings: PianoSettingsRepository by lazy { PianoSettingsRepository(stevenLink, appScope) }

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
        )
    }

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

    /** Where updates come from: the app's GitHub repository; on the emulator in debug builds, the test server [UpdateOverride] names. */
    private val updateSource: UpdateSource by lazy { UpdateOverride.source() ?: UpdateSource.production }
    private val updateServer by lazy { HttpUpdateServer(updateSource, log = debugLog(UPDATES_TAG)) }

    /** Whether a newer release exists, and the updater's state, which the Piano tab shows. */
    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, updateSource, updateServer, network.online, log = debugLog(UPDATES_TAG) ?: {})
    }

    /** Downloads land in `cacheDir/updates`, the one folder the installer is shown. */
    val updateDownloader: UpdateDownloader by lazy { UpdateDownloader(File(app.cacheDir, UPDATES_DIR), updateServer) }

    /** Before a silent install replaces the running app, the piano is silenced: live keys let go, playback pauses. */
    val updateInstaller: UpdateInstaller by lazy {
        UpdateInstaller(app) {
            withContext(Dispatchers.Main) {
                player.silenceLive()
                player.pauseAndFlush(INSTALL_FLUSH_MS)
            }
        }
    }

    /** The Update button's work: download, check, hand to Android. */
    val updater: Updater by lazy { Updater(updateChecker, updateDownloader, updateInstaller) }

    /**
     * Automatic update checks, run by the activity while it is started (after its first frame): at
     * once, then daily, while the switch is on and the device online. The switch is read from
     * DataStore itself, so a check never runs on the default before the saved value is known. The
     * piano's firmware is looked for on the same switch (v1.6 — M21), while a piano that can be
     * updated is connected, and a newer library pack (v1.10 — M27), once a day.
     */
    suspend fun runUpdateSchedule() = coroutineScope {
        val switch = settingsRepository.settings.map { it.checkForUpdates }.distinctUntilChanged()
        launch { firmwareUpdater.runSchedule(switch) }
        launch { libraryPack.runSchedule(switch) }   // v1.10 — M27: a newer library pack, on the same switch
        updateChecker.runSchedule(switch)
    }

    /**
     * Debug builds on an emulator only: the fake firmware update `debug.stevenpiano.fakeota` names
     * (its release, its image, RFC 8032's test key); null everywhere else.
     */
    private val fakeOta: FakeOta? by lazy { FakeOta.fromProperty() }

    /**
     * The piano's firmware updates over the Bluetooth link (v1.6 — M21): its signed releases from
     * the firmware repository, checked against the author's key; on the emulator with a fake
     * scenario, the scenario's release and the test key.
     */
    val firmwareUpdater: FirmwareUpdater by lazy {
        val fake = fakeOta
        FirmwareUpdater(
            link = stevenLink,
            pianoState = pianoSettings.state,
            readFact = pianoSettings::readFact,
            player = object : FirmwarePlayer {
                override fun lock(reason: String) = player.lock(reason)

                override fun unlock() = player.unlock()

                override suspend fun stopForUpdate(timeoutMs: Long): Boolean = player.stopQuietly(timeoutMs)
            },
            server = if (fake != null) FakeFirmwareServer(FakeOta::fromProperty) else HttpUpdateServer(UpdateSource.firmware, log = debugLog(FIRMWARE_TAG)),
            publicKey = if (fake != null) FirmwareKeys.rfc8032Test else FirmwareKeys.author,
            appVersionCode = BuildConfig.VERSION_CODE,
            platformEd25519 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,   // asked first where it has Ed25519; Android 14 has none
            online = network.online,
            power = { batteryState(app) },
            scope = appScope,
            now = SystemClock::elapsedRealtime,
            log = LinkLog::warn,
        )
    }

    /** The web panel (Piano › Remote control): its sessions, login guard, guests' requests, and where its service listens. */
    val web: WebPanel by lazy { WebPanel(app, this) }

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

    /**
     * Studio (Piano › Studio, v1.7 — M23): the models (downloaded on demand from the release tagged
     * `models`, pinned by hash; on the emulator in debug builds, the server [ModelsOverride] names), the
     * jobs (one at a time on [studioThread], a partial wake lock around each, the foreground service
     * following them), and what a transcription leaves: a piece that waits for Keep or Discard.
     */
    val studio: Studio by lazy {
        val source = ModelsOverride.source() ?: UpdateSource.models
        val server = HttpUpdateServer(source, log = debugLog(STUDIO_TAG), fileAccept = HttpUpdateServer.BINARY_ACCEPT)
        val models = ModelStore(File(app.filesDir, MODELS_DIR))
        val library = studioLibrary
        Studio(
            scope = appScope,
            availability = StudioAvailability.of(app, appScope),
            models = models,
            installer = ModelInstaller(source, server, models, VerifiedDownloader(server)),
            reader = AudioDecoder(app.contentResolver),
            pieces = StudioPieces(library),
            review = studioReview,
            worker = studioThread,
            online = network::isOnline,
            onBusy = { StudioService.start(app) },
            awake = ::studioAwake,
            release = ::releaseRecording,
            peakKb = ::peakResidentKb,
            log = { Log.i(STUDIO_TAG, it) },
            trail = LinkLog.shared::add,
            seeds = LibrarySeeds(this.library),
        )
    }

    /**
     * Where Studio's pieces and the tablet's recordings go into the library (v1.7 — M23; hoisted for recordings in
     * v1.11 — M29): through the importer, described on their sheet, discarded as the Library's Delete does.
     */
    val studioLibrary: AppStudioLibrary by lazy { AppStudioLibrary(this) }

    /** Keep or Discard after a first listen, for Studio's pieces and the tablet's recordings (hoisted in v1.11 — M29). */
    val studioReview: StudioReview by lazy {
        StudioReview(
            StoredReview(app),
            object : ReviewPlayer {
                override val state = player.state

                override fun positionMicrosNow(): Long = player.positionMicrosNow()
            },
            studioLibrary,
            appScope,
        )
    }

    /**
     * The recorder (v1.11 — M29): what the Keys screen and the MIDI keyboard play, captured before the router; it hears
     * the keyboard whether Live is on or not.
     */
    val recorder: Recorder by lazy { Recorder().also { keyboard.listen(it) } }

    /** A take from the Record control to the sheet; saved into Recordings, waiting for Keep or Discard. */
    val recording: RecordingSession by lazy {
        val store = object : RecordingStore {
            override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long? = studioLibrary.add(fileName, bytes, title, composer)

            override suspend fun describe(pieceId: Long, description: String) = studioLibrary.describe(pieceId, description)

            override suspend fun addToRecordings(pieceId: Long) = library.addRecording(pieceId)

            override suspend fun recordings(): List<Long> = library.recordings()

            override suspend fun markUndecided(pieceId: Long) = studioReview.made(pieceId)

            override suspend fun undecided(): Set<Long> = studioReview.undecided.value

            override suspend fun discard(pieceId: Long) = studioReview.discard(pieceId)
        }
        RecordingSession(
            recorder,
            RecordingPieces(store, File(app.filesDir, RECORDINGS_DIR), kiosk = { settings.value.kioskEnabled }, log = LinkLog::warn),
            appScope,
            log = LinkLog::warn,
        )
    }

    /** Studio's jobs' thread: one, at background priority, so the player's scheduler (urgent audio) keeps its timing. */
    private val studioThread by lazy {
        Executors.newSingleThreadExecutor { work ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                work.run()
            }, "steven-piano-studio").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    private val studioWakeLock by lazy {
        app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StevenPiano:studio").apply { setReferenceCounted(false) }
    }

    /** Studio's jobs keep the device awake while one runs (30 minutes at most a job: the longest recording takes about 7). */
    private fun studioAwake(on: Boolean) {
        if (on) studioWakeLock.acquire(STUDIO_WAKE_MS) else if (studioWakeLock.isHeld) studioWakeLock.release()
    }

    /** A transcription is over: a web upload's file goes, a picked document's read grant is given back. */
    private fun releaseRecording(source: AudioSource) {
        when (source) {
            is AudioSource.Local -> source.file.delete()
            is AudioSource.Document -> runCatching {
                app.contentResolver.releasePersistableUriPermission(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** The process's peak resident set (VmHWM), in kB; -1 when it can't be read. For Studio's log line. */
    private fun peakResidentKb(): Long = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()
    }.getOrNull() ?: -1L

    /** Timed play (Piano › Schedule): the schedules, the one exact alarm that keeps the next of them, and what runs them. */
    val schedules: Schedules by lazy { Schedules(app, this, database.schedules()) }

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

    /** Restart after a silent update: the piano is silenced first, then the new code starts. */
    fun restartForUpdate(from: Context) = updateInstaller.restart(from) {
        player.silenceLive()
        player.stopAndFlush(INSTALL_FLUSH_MS)
    }

    /**
     * From [App.onCreate]: settings flow into the player; the queue's shuffle and repeat start as
     * they were left and are remembered whenever they change; the piano is reached if the person
     * allows it; files a crashed import left behind are swept away.
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
        // First, once, uploads imported before 1.10.1 become playlists, their artists filled (v1.10.1 — M28, D4). Then the
        // built-in playlists follow the library: now (the first query opens the database), and two seconds after a run of
        // renames ends (imports refresh them from ImportService). Then artwork for the artists the repair named.
        appScope.launch {
            val named = repairUploadsOnce()
            refreshBuiltIns()
            if (named && settingsRepository.settings.first().fetchArtworkAutomatically) artwork.requestComposers(force = false)
        }
        appScope.launch { library.namesChanged.debounce(BUILT_INS_SETTLE_MS).collect { refreshBuiltIns() } }
        pianoSettings.start()
        appScope.launch {
            val saved = settingsRepository.settings.first()
            player.setShuffle(saved.shuffle)
            player.setRepeat(saved.repeat)
            player.state.map { it.queue.shuffle to it.queue.repeat }.distinctUntilChanged().collect { (shuffle, repeat) ->
                val stored = settingsRepository.settings.first()
                if (stored.shuffle != shuffle) settingsRepository.setShuffle(shuffle)
                if (stored.repeat != repeat) settingsRepository.setRepeat(repeat)
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
                applied = s
            }
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
        web.start()
        firmwareUpdater.start()
        schedules.start()
        studio.start()
        appScope.launch {
            val s = settingsRepository.settings.first()
            // The keyboard first (v1.11 — M29): its address is foreign to the piano's link before that link scans.
            midiDevices.start()
            keyboard.restore(KeyboardState.Chosen.saved(s.keyboardId, s.keyboardName))
            restoreInstrument(s)
            followLive()
            launch { recording.recoverPending() }   // takes a crash left behind (v1.11 — M29), beside the rest
            // Permission is only ever asked for on the Piano tab; without it, launch stays quiet. A MIDI piano (v1.11 — M29)
            // connects with the same switch; by cable it needs no permission.
            if (s.autoConnect) {
                if (pianoLink.kind.value == InstrumentKind.MidiPiano) {
                    pianoLink.connect(null)
                } else if (BlePermissions.missing(app).isEmpty()) {
                    pianoLink.connect(s.lastDeviceAddress)
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
     * [count] files another app sent could not be read (no access was given with them): the
     * Library's import bar says so, unless an import is running, whose progress it keeps showing.
     */
    fun reportUnreadableShare(count: Int) {
        if (!importState.value.finished) {
            Log.w(TAG, "$count shared files couldn't be read (an import is running)")
            return
        }
        importState.value = ImportProgress(done = count, total = count, failed = count, unreadable = true)
    }

    /**
     * A file sent from the web panel could not even be opened (a zip that is not one, or holds past
     * its entry cap): the Library's import bar and the panel's tally count it as one failed file,
     * unless an import is running, whose progress they keep showing.
     */
    fun reportFailedImport() {
        if (!importState.value.finished) return
        importState.value = ImportProgress(done = 1, total = 1, failed = 1)
    }

    /**
     * The app came to the foreground, where a foreground service may start: if the person lets
     * artwork arrive by itself, the device is online, and some composer was never looked up (a
     * library from 1.1, an import made offline) or failed a day ago or more, the fetch starts.
     */
    fun fetchArtworkIfDue() {
        appScope.launch {
            if (!settingsRepository.settings.first().fetchArtworkAutomatically || !network.isOnline()) return@launch
            if (artwork.composersDue()) ArtworkService.start(app, force = false)
        }
    }

    /**
     * Web control on or off (Piano › Remote control): the switch is saved first, so the service,
     * which follows it, sees it on when it starts; off, the service stops and every session ends.
     */
    fun setWebEnabled(on: Boolean) {
        appScope.launch {
            settingsRepository.setWebEnabled(on)
            if (on) {
                if (!WebService.start(app)) web.report(web.status.value.copy(problem = "Android refused to start the web service."))
            } else {
                WebService.stop(app)
                web.turnedOff()
            }
        }
    }

    /**
     * The app came to the foreground (where a foreground service may start): with Web control on (or
     * remote access over the internet, v1.10 — M26) and a PIN set, the web service starts, after a
     * restart of the tablet or of the app.
     */
    fun startWebIfOn(context: Context) {
        val appContext = context.applicationContext
        appScope.launch {
            val s = settingsRepository.settings.first()
            val cloudDown = s.cloudEnabled && s.cloudEnrolled && web.cloud.value == CloudStatus.Off
            if ((s.webEnabled || s.cloudEnabled) && s.webPinSet && (!web.status.value.running || cloudDown)) WebService.start(appContext)
        }
    }

    /**
     * Remote access over the internet on or off (Piano › Remote control › CLOUD, v1.10 — M26): saved
     * first, so the web service, which follows it, sees it; on, the service starts (it connects once
     * a PIN is set and the tablet is enrolled); off, it stops unless Web control keeps it.
     */
    fun setCloudEnabled(on: Boolean) {
        appScope.launch {
            settingsRepository.setCloudEnabled(on)
            val s = settingsRepository.settings.first()
            if (on) {
                if (s.webPinSet && !WebService.start(app)) web.report(web.status.value.copy(problem = "Android refused to start the web service."))
            } else if (!s.webEnabled) {
                WebService.stop(app)
            }
        }
    }

    /**
     * Enrols this tablet with the relay at [host] using a [code] from the console (v1.10 — M26): the
     * typed address is remembered; on success the piano's id and its sealed secret are kept together
     * and the relay client starts again for them. The answer says how it went, in plain words.
     */
    suspend fun enrol(host: String, code: String): EnrolResult {
        CloudAddress.host(host)?.let { settingsRepository.setCloudHost(it) }
        val result = withContext(Dispatchers.IO) { Enrolment(web.cloudOverride).enrol(host, code) }
        if (result !is EnrolResult.Enrolled) return result
        val sealed = withContext(Dispatchers.IO) { web.cloudSecrets.seal(result.secret) }
            ?: return EnrolResult.Refused("The tablet couldn't keep its key. Try again.")
        settingsRepository.setCloudEnrolment(result.host, result.pianoId, sealed)
        web.enrolled()
        LinkLog.warn("Cloud: enrolled with ${result.host}")
        return result
    }

    /** Forget this cloud (v1.10 — M26): remote access turns off, the enrolment and its key go; the typed address stays. */
    fun forgetCloud() {
        appScope.launch {
            settingsRepository.forgetCloud()
            withContext(Dispatchers.IO) { web.cloudSecrets.forget() }
            web.reportCloud(CloudStatus.Off)
            if (!settingsRepository.settings.first().webEnabled) WebService.stop(app)
            LinkLog.warn("Cloud: this tablet's enrolment was forgotten")
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

    private companion object {
        const val TAG = "AppGraph"
        const val UPDATES_TAG = "Updates"
        const val FIRMWARE_TAG = "Firmware"
        const val STUDIO_TAG = "Studio"
        const val SOUND_TAG = "TabletSound"
        const val LIBRARY_TAG = "Library"
        const val MODELS_DIR = "models"
        const val RECORDINGS_DIR = "recordings"
        const val STUDIO_WAKE_MS = 30 * 60_000L
        const val UPDATES_DIR = "updates"
        const val DIAGNOSTICS_DIR = "diagnostics"
        const val DISCONNECT_FLUSH_MS = 300L
        const val INSTALL_FLUSH_MS = 300L

        /** The piano's own volume setting, which a channel holds while it plays. */
        const val PIANO_VOLUME = "volume"

        /** The piano's Full power, which the firmware turns off when the volume goes below 100. */
        const val PIANO_FULL_POWER = "fullpower"

        /** Renames come in runs (a dialog's fields, a tidy-up): the built-in playlists wait for the run to end. */
        const val BUILT_INS_SETTLE_MS = 2_000L

        /** Debug builds log each request and check under [tag]; release builds log no address. */
        fun debugLog(tag: String): ((String) -> Unit)? = if (BuildConfig.DEBUG) { line -> Log.d(tag, line) } else null
    }
}
