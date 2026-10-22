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
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
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
import dev.stevenjin.stevenpiano.audio.KeyTestState
import dev.stevenjin.stevenpiano.audio.ListenAnswer
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
import dev.stevenjin.stevenpiano.data.PieceFiles
import dev.stevenjin.stevenpiano.data.art.ArtFiles
import dev.stevenjin.stevenpiano.data.art.ArtworkRepository
import dev.stevenjin.stevenpiano.data.builtin.BuiltInCatalogue
import dev.stevenjin.stevenpiano.data.builtin.StudioPlaylist
import dev.stevenjin.stevenpiano.data.builtin.BuiltInPlaylists
import dev.stevenjin.stevenpiano.data.db.PianoDatabase
import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.data.imports.Importer
import dev.stevenjin.stevenpiano.diag.CrashReports
import dev.stevenjin.stevenpiano.diag.Diagnostics
import dev.stevenjin.stevenpiano.diag.DiagnosticsExporter
import dev.stevenjin.stevenpiano.diag.DiagnosticsText
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.firmware.FirmwareKeys
import dev.stevenjin.stevenpiano.firmware.FirmwarePlayer
import dev.stevenjin.stevenpiano.firmware.FirmwareUpdater
import dev.stevenjin.stevenpiano.firmware.batteryState
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
import dev.stevenjin.stevenpiano.library.OfferedPacks
import dev.stevenjin.stevenpiano.net.NetworkMonitor
import dev.stevenjin.stevenpiano.net.AppleCatalog
import dev.stevenjin.stevenpiano.net.WikipediaClient
import dev.stevenjin.stevenpiano.piano.LockKeys
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoSettingsRepository
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.piano.StoredLockKeys
import dev.stevenjin.stevenpiano.player.PianoFacts
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Played
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.RepeatMode
import dev.stevenjin.stevenpiano.player.StartGate
import dev.stevenjin.stevenpiano.record.Recorder
import dev.stevenjin.stevenpiano.record.RecordingPieces
import dev.stevenjin.stevenpiano.record.RecordingSession
import dev.stevenjin.stevenpiano.record.RecordingStore
import dev.stevenjin.stevenpiano.safety.DataStoreSafetyStore
import dev.stevenjin.stevenpiano.safety.Deferral
import dev.stevenjin.stevenpiano.safety.SafetyCheck
import dev.stevenjin.stevenpiano.safety.SafetyHost
import dev.stevenjin.stevenpiano.safety.SafetyRunner
import dev.stevenjin.stevenpiano.safety.safetyDataStore
import dev.stevenjin.stevenpiano.quiet.QuietGate
import dev.stevenjin.stevenpiano.quiet.QuietStore
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
import dev.stevenjin.stevenpiano.studio.Generations
import dev.stevenjin.stevenpiano.studio.RoomGenerations
import dev.stevenjin.stevenpiano.studio.RoomReview
import dev.stevenjin.stevenpiano.studio.StoredCalibration
import dev.stevenjin.stevenpiano.studio.StoredWaiting
import dev.stevenjin.stevenpiano.studio.Studio
import dev.stevenjin.stevenpiano.studio.StudioAvailability
import dev.stevenjin.stevenpiano.studio.StudioPieces
import dev.stevenjin.stevenpiano.studio.StudioReview
import dev.stevenjin.stevenpiano.update.HttpUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateChecker
import dev.stevenjin.stevenpiano.update.UpdateDownloader
import dev.stevenjin.stevenpiano.update.UpdateInstaller
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.Updater
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import dev.stevenjin.stevenpiano.web.WebPanel
import dev.stevenjin.stevenpiano.web.relay.CloudAddress
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import dev.stevenjin.stevenpiano.web.relay.EnrolResult
import dev.stevenjin.stevenpiano.web.relay.Enrolment
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.instruments.KeyboardListener
import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.piano.LockPhase
import dev.stevenjin.stevenpiano.power.AndroidPlugTransport
import dev.stevenjin.stevenpiano.power.Boards
import dev.stevenjin.stevenpiano.power.GuestLink
import dev.stevenjin.stevenpiano.power.PairFlow
import dev.stevenjin.stevenpiano.power.PlayerNow
import dev.stevenjin.stevenpiano.power.PlugClient
import dev.stevenjin.stevenpiano.power.PlugSettings
import dev.stevenjin.stevenpiano.power.PlugStore
import dev.stevenjin.stevenpiano.power.RailGate
import dev.stevenjin.stevenpiano.power.RailHold
import dev.stevenjin.stevenpiano.power.RailKeeper
import dev.stevenjin.stevenpiano.power.RailPhase
import dev.stevenjin.stevenpiano.power.RailPower
import dev.stevenjin.stevenpiano.power.ShellyRail
import dev.stevenjin.stevenpiano.ui.LockCopy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
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

    private val database: PianoDatabase by lazy { PianoDatabase.open(app) }
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
        refreshStudioShelf()
    }

    /** "Made in Studio" (v1.12 — M30) from Studio's history: after each piece Studio saves, and with the built-in lists. */
    suspend fun refreshStudioShelf() = withContext(Dispatchers.IO) {
        try {
            StudioPlaylist.refresh(library, studioHistory.madeHere())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Made in Studio couldn't be refreshed", e)
        }
    }

    /** Studio's history (v1.12 — M30): the turns of its conversation, and the one record of its pieces' Keep or Discard. */
    val studioHistory: Generations by lazy { RoomGenerations(database.generations()) }

    private val importState = MutableStateFlow(ImportProgress.Idle)
    val importProgress: StateFlow<ImportProgress> = importState.asStateFlow()
    val importer: Importer by lazy { Importer(library, pieceFiles, importState) }

    /** Whether the device is online; artwork is only ever fetched when it is. */
    val network: NetworkMonitor by lazy { NetworkMonitor(app) }

    /**
     * Portraits, notes, album covers, playlist photos and roll cards (their network use: Wikipedia's two hosts, and with
     * Album covers on Apple's catalogue, `itunes.apple.com` and `*.mzstatic.com`; v1.15 — M40).
     */
    val artwork: ArtworkRepository by lazy {
        ArtworkRepository(
            app, database.artwork(), library, ArtFiles(app.filesDir), WikipediaClient(), AppleCatalog(),
            coversWanted = { settingsRepository.settings.first().let { it.fetchArtworkAutomatically && it.albumCovers } },
            coverRuleSince = { settingsRepository.coverRuleSince(System.currentTimeMillis()) },
            network = network,
            scope = appScope,
        )
    }

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
    private val stevenPlays: PianoLink by lazy { LockGate(stevenLink, pianoSettings.trust, appScope) }

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
     * [quiet]) it starts nothing; the safety check has its say over every start (v1.26 — M61, [safetyGate]).
     */
    val player: Player by lazy {
        Player(pianoLink, library, appScope, trail = LinkLog::warn, tablet = tabletSound.sink, quiet = { quiet.holds() }, gate = safetyGate)
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

    /**
     * Steven's library from GitHub (v1.10 — M27): the pack's manifest and zip from this repository's release
     * `library` (on the emulator in debug builds, a server on the Mac: [Emulator.librarySource]), its pieces through the
     * importer, the version loaded kept in the settings. The Library's buttons and the console's
     * `library.load` call [LibraryPack.load]; [LibraryService] holds the foreground while it runs.
     */
    val libraryPack: LibraryPack by lazy {
        val source = Emulator.librarySource() ?: UpdateSource.library
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

    /** Where updates come from: the app's GitHub repository; on the emulator in debug builds, a test server ([Emulator.updateSource]). */
    private val updateSource: UpdateSource by lazy { Emulator.updateSource() ?: UpdateSource.production }
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
     * The piano's firmware updates over the Bluetooth link (v1.6 — M21): its signed releases from
     * the firmware repository, checked against the author's key; on the emulator with a fake
     * scenario, the scenario's release and the test key ([Emulator.firmware]).
     */
    val firmwareUpdater: FirmwareUpdater by lazy {
        val fake = Emulator.firmware()
        FirmwareUpdater(
            link = stevenLink,
            pianoState = pianoSettings.state,
            readFact = pianoSettings::readFact,
            player = object : FirmwarePlayer {
                override fun lock(reason: String) = player.lock(reason)

                override fun unlock() = player.unlock()

                override suspend fun stopForUpdate(timeoutMs: Long): Boolean = player.stopQuietly(timeoutMs)
            },
            server = fake?.server ?: HttpUpdateServer(UpdateSource.firmware, log = debugLog(FIRMWARE_TAG)),
            publicKey = fake?.publicKey ?: FirmwareKeys.author,
            appVersionCode = BuildConfig.VERSION_CODE,
            platformEd25519 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,   // asked first where it has Ed25519; Android 14 has none
            online = network.online,
            power = { batteryState(app) },
            scope = appScope,
            now = SystemClock::elapsedRealtime,
            log = LinkLog::warn,
        )
    }

    /** The web panel (Piano › Web panel): its sessions, login guard, guests' requests, and where its service listens. */
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
     * Studio (its own tab since v1.12 — M30; a Piano page from v1.7 — M23): the models (downloaded on demand from the release tagged
     * `models`, pinned by hash; on the emulator in debug builds, a server on the Mac: [Emulator.modelsSource]), the
     * jobs (one at a time on [studioThread], a partial wake lock around each, the foreground service
     * following them), and what a transcription leaves: a piece that waits for Keep or Discard.
     */
    val studio: Studio by lazy {
        val source = Emulator.modelsSource() ?: UpdateSource.models
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
            generations = studioHistory,
            calibration = StoredCalibration(app),
            kiosk = { settings.value.kioskEnabled },
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
            RoomReview(studioHistory, StoredWaiting(app)),
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

    /** A take from the Record control to the sheet; saved into Recordings with a cover of its own, waiting for Keep or Discard. */
    val recording: RecordingSession by lazy {
        val store = object : RecordingStore {
            override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long? = studioLibrary.add(fileName, bytes, title, composer)

            override suspend fun describe(pieceId: Long, description: String) = studioLibrary.describe(pieceId, description)

            override suspend fun addToRecordings(pieceId: Long) = library.addRecording(pieceId)

            override suspend fun recordings(): List<Long> = library.recordings()

            override suspend fun markUndecided(pieceId: Long) = studioReview.made(pieceId)

            override suspend fun undecided(): Set<Long> = studioReview.undecided.value

            override suspend fun discard(pieceId: Long) = studioReview.discard(pieceId)

            override suspend fun setCover(pieceId: Long, png: ByteArray): Boolean = studioLibrary.setCover(pieceId, png)
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

    /** Restart after a silent update: the piano is silenced first, then the new code starts. */
    fun restartForUpdate(from: Context) = updateInstaller.restart(from) {
        player.silenceLive()
        player.stopAndFlush(INSTALL_FLUSH_MS)
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
        pianoSettings.start()
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
        appScope.launch {
            val s = settingsRepository.settings.first()
            // The keyboard first (v1.11 — M29): its address is foreign to the piano's link before that link scans.
            midiDevices.start()
            keyboard.restore(KeyboardState.Chosen.saved(s.keyboardId, s.keyboardName))
            restoreInstrument(s)
            followLive()
            remote.followPlaying()   // v1.23 — M55: since when nothing plays, for a key held on
            startRail()   // v1.26 — M60: the 24 V rail on its smart plug (see the M60 block)
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
     * artwork arrive by itself, the device is online, and some composer (or, with Album covers on,
     * some piece's cover; v1.15 — M40) was never looked up (a library from 1.1, an import made
     * offline) or failed a day ago or more, the fetch starts.
     */
    fun fetchArtworkIfDue() {
        appScope.launch {
            if (!settingsRepository.settings.first().fetchArtworkAutomatically || !network.isOnline()) return@launch
            if (artwork.due()) ArtworkService.start(app, force = false)
        }
    }

    /**
     * The web panel on or off (Piano › Web panel): the switch is saved first, so the service,
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
     * The web panel over the internet on or off (Piano › Web panel › OVER THE INTERNET, v1.10 — M26): saved
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

    /** The plug's JSON-RPC over Bluetooth (one request in flight); on the emulator in debug builds, the fake plug. */
    val plug: PlugClient by lazy {
        PlugClient(
            Emulator.plugTransport() ?: AndroidPlugTransport(app, scanThrottle),
            appScope,
            address = { plugSettings.value?.pairing?.address },
            log = LinkLog::warn,
        )
    }

    /** The CHECK: the piano's `get boards`, answered within 2 s (null: no answer, or no console). */
    private suspend fun railCheck(): Boards? = Boards.parse(pianoSettings.ask(BOARDS_FACT, RAIL_CHECK_MS))

    /** The tablet as the 24 V supply's dead-man's switch ([RailKeeper]); [railPower] is how other features see it. */
    val rail: RailKeeper by lazy {
        RailKeeper(
            scope = appScope,
            plug = ShellyRail(plug),
            check = ::railCheck,
            connected = stevenConnected,
            stopSequence = ::railStopSequence,
            stopPlaying = ::stopForRail,
            available = plugSwitches,
            now = SystemClock::elapsedRealtime,
            guest = railGuest,
            log = LinkLog::warn,
        )
    }

    /** The rail as the safety check (M61) and anything else that wants it sees it. */
    val railPower: RailPower get() = rail

    /** Steven Piano's own link connected: the rail's CHECK can be asked. */
    private val stevenConnected: StateFlow<Boolean> by lazy {
        stevenLink.state.map { it is LinkState.Connected }.distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, false)
    }

    /** Steven Piano's link as everything that plays sees it: the lock's gate, then the rail's (notes wait while it comes up). */
    private val railPlays: PianoLink by lazy { RailGate(stevenPlays, rail.notesHeld) }

    /** The Pair screen's work (Piano › Power plug). */
    val pairFlow: PairFlow by lazy { PairFlow(plug, plugStore, appScope, check = ::railCheck, now = SystemClock::elapsedRealtime, log = LinkLog::warn) }

    /** The stop sequence (CC64 = 0, CC123) straight to Steven Piano, past both gates, and written before it returns. */
    private suspend fun railStopSequence() {
        stevenLink.send(MidiBatch().apply { add(CONTROL_CHANGE, SUSTAIN, 0); add(CONTROL_CHANGE, ALL_NOTES_OFF, 0) }, dropPending = false)
        withContext(Dispatchers.IO) { stevenLink.flush(RAIL_FLUSH_MS) }
    }

    /** The rail failed or was cut: what plays on Steven Piano stops (the piece, Test every key, the Keys tab's keys). */
    private fun stopForRail() {
        if (!player.locked) player.stop()
        remote.keyTest.stop()
        player.silenceLive()
    }

    /** Guest play's link: the tablet lets go of the piano, connects again, and ends guest play there. */
    private val railGuest = object : GuestLink {
        override fun letGo() {
            appScope.launch {
                player.pauseAndFlush(DISCONNECT_FLUSH_MS)
                if (pianoLink.kind.value == InstrumentKind.StevenPiano) pianoLink.disconnect()
                LinkLog.warn("Guest play: the tablet lets go of the piano")
            }
        }

        override suspend fun reconnect(): Boolean {
            if (pianoLink.kind.value != InstrumentKind.StevenPiano) return false
            if (stevenLink.state.value !is LinkState.Connected && BlePermissions.missing(app).isEmpty()) pianoLink.connect(settings.value.lastDeviceAddress)
            return withTimeoutOrNull(GUEST_RECONNECT_MS) { pianoSettings.lock.first { it.manageable || it.phase == LockPhase.None && pianoSettings.state.value is PianoState.Ready } } != null
        }

        override fun endOnPiano() = pianoSettings.guestPlay(0)
    }

    /**
     * Guest play from this tablet (Firmware and status' switch): `lock guest 60`; with the plug switching the rail, once the
     * piano says guest play is on, the rail is kept for it (60 minutes at most) and the tablet lets go of the piano.
     */
    fun startGuestPlay() {
        pianoSettings.guestPlay(LockCopy.GUEST_MINUTES)
        if (!plugSwitches.value) return
        appScope.launch {
            withTimeoutOrNull(GUEST_START_MS) { pianoSettings.lock.first { it.guestSeconds > 0 } } ?: return@launch
            rail.startGuest(LockCopy.GUEST_MINUTES)
        }
    }

    /** End guest play: with the rail kept for it, the full sequence (reconnect, `lock guest 0`, the stop sequence, CUT). */
    fun endGuestPlay() {
        if (rail.guestUntil.value == null) {
            pianoSettings.guestPlay(0)
            return
        }
        appScope.launch { rail.endGuest() }
    }

    /** The Keys tab on screen ([on]) with Steven Piano the instrument: it wants the rail, until 10 minutes without a key. */
    fun railKeysShown(on: Boolean) = rail.keysOnScreen(on && pianoLink.kind.value == InstrumentKind.StevenPiano)

    /** A key on the Keys tab: the idle cut starts over, and a rail cut for idling wakes ("Waking the piano…"). */
    fun railKeyPressed() = rail.keyPressed()

    /** The plug's switch, from Piano › Power plug: off, what plays stops and the rail is cut first. */
    fun setPlugOn(on: Boolean) {
        appScope.launch {
            if (!on) rail.release()
            plugStore.setOn(on)
        }
    }

    /** Forget the plug: the rail cut, this tablet dropped from the plug's bonds (when known), the pairing gone. */
    fun forgetPlug() {
        appScope.launch {
            rail.release()
            val pairing = plugSettings.value?.pairing
            pairing?.bondEntry?.let { entry -> plug.call("BLE.DeletePairedDevice", org.json.JSONObject().put("addr", entry)) }
            plugStore.forget()
            plug.drop()
            pairFlow.restart()
            LinkLog.warn("Power plug forgotten")
        }
    }

    /** Admit a replacement tablet: the plug's pairing window for 3 minutes. True when the plug opened it. */
    suspend fun admitTablet(): Boolean =
        plug.call("BLE.StartPairing", org.json.JSONObject().put("timeout", PairFlow.PAIRING_WINDOW_S))?.ok == true

    /** What the rail follows: the player (a piece playing; held while the rail comes up), Test every key, a firmware update, Live's keys. */
    private fun startRail() {
        rail.start()
        RailHold(
            appScope,
            player = combine(player.state, pianoLink.kind) { s, kind ->
                PlayerNow(playing = kind == InstrumentKind.StevenPiano && s.status == PlaybackStatus.Playing, stopped = s.status == PlaybackStatus.Stopped, pieceId = s.piece?.pieceId)
            },
            held = rail.notesHeld,
            positionMicros = player::positionMicrosNow,
            pause = player::pause,
            resume = player::resume,
            want = { rail.wanted(RailKeeper.PLAY, it) },
        ).start()
        appScope.launch { remote.keyTest.state.map { it.running }.distinctUntilChanged().collect { rail.wanted(RailKeeper.KEY_TEST, it) } }
        appScope.launch { firmwareUpdater.state.map(::updateWantsRail).distinctUntilChanged().collect(rail::updateRunning) }
        appScope.launch { rail.phase.map { it != RailPhase.Off }.distinctUntilChanged().collect(plug::hold) }
        // A keyboard's keys through Live are keys on the Keys tab: each starts the idle cut over, or wakes the rail.
        val main = Handler(android.os.Looper.getMainLooper())
        keyboard.listen(
            object : KeyboardListener {
                override fun onKeys(events: KeyEvents, arrivalNanos: Long, stampNanos: Long) {
                    if (events.downs() > 0 && liveThru.state.value.open) main.post(rail::keyPressed)
                }
            },
        )
        // Guest play this tablet started survives a restart of the app: its end, on the wall clock, is kept.
        appScope.launch {
            val until = plugSettings.first { it != null }?.guestUntil ?: return@launch
            val left = until - System.currentTimeMillis()
            if (left > 0) rail.restoreGuest(SystemClock.elapsedRealtime() + left) else plugStore.setGuestUntil(null)
        }
        appScope.launch {
            rail.guestUntil.drop(1).collect { until ->
                plugStore.setGuestUntil(until?.let { System.currentTimeMillis() + (it - SystemClock.elapsedRealtime()) })
            }
        }
    }

    /** A firmware update wants the rail from BEGIN (its transfer under way) until its outcome is known. */
    private fun updateWantsRail(state: FirmwareState): Boolean = when (state) {
        is FirmwareState.Sending -> state.bytes > 0
        is FirmwareState.PianoVerifying, is FirmwareState.Restarting -> true
        is FirmwareState.Done -> state.confirming
        else -> false
    }

    // ==== end of M60's block ==================================================================================================

    // ==== v1.26 — M61: the safety check (one block; M60's RailPower is connected here by the integrator) ===============

    /**
     * The safety check (v1.26 — M61; DESIGN.md › v1.26 — M61): after every 6th piece the tablet plays on Steven Piano, the
     * release, the coils and the boards read, the 84 keys swept while the microphone listens, the restart and repower
     * where keys stay silent, and Play locked when they still do. Its record in its own small DataStore.
     *
     * For the integrator, after M60 merges: `repower = { rail.repower() }`, `cutNow = { rail.cutNow() }`,
     * `plugged = { rail.available.value }`, `wantRail = { on -> rail.wanted("safety", on) }` and `awaitRail` (the rail up,
     * its notes no longer held, within 10 s) with M60's `RailPower`; and [keyTestRail] for Test every key.
     */
    val safety: SafetyRunner by lazy {
        SafetyRunner(
            scope = appScope,
            store = DataStoreSafetyStore(app.safetyDataStore, log = LinkLog::warn),
            host = safetyHost,
            listens = { settings.value.safetyCheckListens && remote.microphoneAllowed() },
            repower = { railPower.repower() },
            cutNow = { railPower.cutNow() },
            plugged = { railPower.available.value },
            wantRail = { on ->
                railPower.wanted(SAFETY_RAIL, on)
                // v1.26 — M62: the check's end lets go of its repower's own hold too (60 s from the repower), so the 24 V
                // goes off a second after the check, as after a piece: nothing is sent once it ended.
                if (!on) railPower.wanted(RailKeeper.REPOWER, false)
            },
            awaitRail = { railUpWithin(RAIL_AWAIT_MS) },
            log = LinkLog::warn,
        )
    }

    /**
     * Test every key's wait before its first strike (v1.26 — M61), whoever started it (the System page, the panel, the
     * safety check): the 24 V up. The integrator connects M60's `RailPower` here (want the rail, then wait for it, 10 s at
     * most, false when it doesn't come up); until then the rail is taken as up.
     */
    internal val keyTestRail: suspend () -> Boolean = {
        if (!railPower.available.value) {
            true
        } else {
            railPower.wanted(RailKeeper.KEY_TEST, true)   // the key test's own running state keeps or drops it from here
            railUpWithin(RAIL_AWAIT_MS).also { up -> if (!up) railPower.wanted(RailKeeper.KEY_TEST, false) }
        }
    }

    /**
     * The 24 V up and its notes no longer held, within [ms] (v1.26: the safety check's and Test every key's wait before a
     * sweep); true at once without the plug feature, false when it doesn't come up in time. Wants nothing by itself.
     */
    private suspend fun railUpWithin(ms: Long): Boolean {
        if (!railPower.available.value) return true
        return withTimeoutOrNull(ms) {
            rail.phase.first { it == RailPhase.Up || it == RailPhase.Kept }
            rail.notesHeld.first { held -> !held }
            true
        } ?: false
    }

    /** The player's and the channels' start gate: the safety check's, asked as each start comes (the two never make each other). */
    private val safetyGate: StartGate by lazy {
        object : StartGate {
            override val open: Boolean get() = safety.open

            override fun admit(pieceId: Long?, start: () -> Unit): Boolean = safety.admit(pieceId, start)
        }
    }

    /** What the safety check works with: the player, Steven Piano's link and console, the key test, the gates. */
    private val safetyHost: SafetyHost by lazy { SafetyCheckHost() }

    private inner class SafetyCheckHost : SafetyHost {
        override val played: Flow<Played> get() = player.played

        override fun deferral(): Deferral? = when {
            player.locked || firmwareUpdater.state.value.busy -> Deferral.UPDATING
            quiet.holds() -> Deferral.QUIET
            !remote.listening.inFront.value -> Deferral.AWAY
            pianoSettings.lock.value.guestLeft(SystemClock.elapsedRealtime()) > 0 -> Deferral.GUEST_PLAY
            !pianoReady() -> Deferral.NO_PIANO
            else -> null
        }

        override fun pianoReady(): Boolean = pianoLink.kind.value == InstrumentKind.StevenPiano && pianoLink.state.value is LinkState.Connected

        override fun playerBusy(): Boolean = player.state.value.let { it.status == PlaybackStatus.Playing || it.loading }

        override fun pausePlayer() {
            if (player.state.value.status == PlaybackStatus.Playing) player.pause()
        }

        override fun release() {
            player.silenceLive()
            player.allKeysOff()
        }

        override fun stopEverything() {
            player.stop()
            player.silenceLive()
            pianoSettings.action(PianoAction.AllKeysOff)   // the piano's own `off`, which goes whatever its lock says
        }

        override suspend fun facts(): Map<String, String>? {
            val read = withTimeoutOrNull(SAFETY_FACTS_WAIT_MS) { pianoSettings.state.first { it !is PianoState.Unknown } }
            if (read is PianoState.Ready) return read.facts
            // v1.27 — M64: none (empty) only for a piano with no console at all, whose facts are left out as before; a console
            // that gave no dump on this connection says nothing of what the piano reports.
            val noConsole = read == PianoState.Unsupported && stevenLink.state.value is LinkState.Connected && stevenLink.console == null
            return if (noConsole) emptyMap() else null
        }

        override fun dumpAgain(): Boolean = pianoSettings.dumpAgain()

        override suspend fun ask(fact: String): String? {
            // v1.26 — M62: the power boards answer only with the 24 V up, which the check wanted at its start. After the
            // 6th piece it is still up; a check that begins with it off (due before the next Play, or run from the lock
            // card) reads them once it is up (10 s at most), not a second after wanting it, which would find them all
            // missing and power the piano off and on for nothing.
            if (fact == SafetyCheck.BOARDS) railUpWithin(RAIL_AWAIT_MS)
            return pianoSettings.ask(fact)
        }

        override suspend fun restart(): String? {
            stevenLink.expectRestart(SAFETY_RESTART_WINDOW_MS)
            return pianoSettings.restart().also { reply ->
                // v1.27 — M64: the drop after `restarting` is the restart asked for, not a dead piano: the 24 V's keeper lets
                // it pass, and brings the rail up again with the piano.
                if (SafetyCheck.restarting(reply)) rail.expectRestart(SAFETY_RESTART_WINDOW_MS)
            }
        }

        override fun startSweep(): String? = (remote.keyTest.start(null) as? ListenAnswer.Refused)?.message

        override val sweep: StateFlow<KeyTestState> get() = remote.keyTest.state

        override fun stopSweep() = remote.keyTest.stop()

        override fun nextPiece(): Long? {
            val queue = player.state.value.queue
            val at = queue.index
            return when {
                at !in queue.ids.indices -> null
                queue.repeat == RepeatMode.ONE -> queue.ids[at]
                at + 1 < queue.ids.size -> queue.ids[at + 1]
                queue.repeat == RepeatMode.ALL -> queue.ids.first()
                else -> null
            }
        }

        override suspend fun titleOf(pieceId: Long): String? = withContext(Dispatchers.IO) { library.summaries(listOf(pieceId))[pieceId]?.title }
    }

    /** From [start]: the safety check reads its record and follows the player. */
    private fun startSafety() = safety.start()

    // ==== end of the safety check's block ==============================================================================

    // ==== v1.26 — M62: the plug and the safety check together (DESIGN.md › v1.26 — M62) ================================

    /**
     * Steven Piano's notes held for its 24 V (v1.26 — M62): Steven Piano is the instrument, and its power plug's rail holds
     * every note but what releases while it comes up ([RailKeeper.notesHeld], which [RailGate] applies), so nothing reaches
     * the piano. Red means notes reaching the piano: the "Sent to piano" dots and the rail's are red only while a piece plays
     * and this is false, and the connected dot while the rail wakes. Always false without the plug (the dots as before).
     */
    val railHoldsNotes: StateFlow<Boolean> by lazy {
        combine(rail.notesHeld, pianoLink.kind) { held, kind -> held && kind == InstrumentKind.StevenPiano }
            .stateIn(appScope, SharingStarted.Eagerly, false)
    }

    // ==== end of M62's block ==========================================================================================

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

        /** Whether a person switched Full power off (v1.22): its own small preference file, read when the piano connects. */
        const val FULL_POWER_CHOICE = "full_power"

        const val FULL_POWER_CHOSEN_OFF = "chosenOff"

        /** The piano's Full power, which the firmware turns off when the volume goes below 100. */
        const val PIANO_FULL_POWER = "fullpower"

        /** The piano's steady repeat period, a fact of its `dump` (v1.16 — M44). */
        const val REPEAT_FACT = "repeatms"

        // v1.26 — M60: the rail.
        const val BOARDS_FACT = "boards"
        const val RAIL_CHECK_MS = 2_000L
        const val RAIL_FLUSH_MS = 300L

        /** The safety check's reason for wanting the 24 V rail ([RailPower.wanted], v1.26). */
        const val SAFETY_RAIL = "safety"

        /** How long a sweep waits for the 24 V rail to come up before it gives up (v1.26). */
        const val RAIL_AWAIT_MS = 10_000L
        const val GUEST_RECONNECT_MS = 15_000L
        const val GUEST_START_MS = 5_000L
        const val CONTROL_CHANGE = 0xB0
        const val SUSTAIN = 64
        const val ALL_NOTES_OFF = 123

        /** Renames come in runs (a dialog's fields, a tidy-up): the built-in playlists wait for the run to end. */
        const val BUILT_INS_SETTLE_MS = 2_000L

        /** The safety check (v1.26 — M61): the piano's dump at most this long, and the restart's window for the link. */
        const val SAFETY_FACTS_WAIT_MS = 5_000L
        const val SAFETY_RESTART_WINDOW_MS = 60_000L

        /** Debug builds log each request and check under [tag]; release builds log no address. */
        fun debugLog(tag: String): ((String) -> Unit)? = if (BuildConfig.DEBUG) { line -> Log.d(tag, line) } else null
    }
}
