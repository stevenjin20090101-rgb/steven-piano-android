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
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.stevenjin.stevenpiano.admin.DeviceOwnerRelease
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.GattPianoLink
import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import dev.stevenjin.stevenpiano.ble.PianoLink
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
import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
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
import dev.stevenjin.stevenpiano.net.NetworkMonitor
import dev.stevenjin.stevenpiano.net.WikipediaClient
import dev.stevenjin.stevenpiano.piano.PianoSettingsRepository
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.service.ArtworkService
import dev.stevenjin.stevenpiano.service.WebService
import dev.stevenjin.stevenpiano.settings.Appearance
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.settings.settingsDataStore
import dev.stevenjin.stevenpiano.update.HttpUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateChecker
import dev.stevenjin.stevenpiano.update.UpdateDownloader
import dev.stevenjin.stevenpiano.update.UpdateInstaller
import dev.stevenjin.stevenpiano.update.UpdateOverride
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.Updater
import dev.stevenjin.stevenpiano.web.WebPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
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

    private val importState = MutableStateFlow(ImportProgress.Idle)
    val importProgress: StateFlow<ImportProgress> = importState.asStateFlow()
    val importer: Importer by lazy { Importer(library, pieceFiles, importState) }

    /** Whether the device is online; artwork is only ever fetched when it is. */
    val network: NetworkMonitor by lazy { NetworkMonitor(app) }

    /** Portraits, notes, playlist photos and roll cards (the only network use: two Wikimedia hosts). */
    val artwork: ArtworkRepository by lazy {
        ArtworkRepository(app, database.artwork(), library, ArtFiles(app.filesDir), WikipediaClient(), network, appScope)
    }

    private val link = lazy {
        if (LoggingPianoLink.isWanted()) {
            LoggingPianoLink()
        } else {
            GattPianoLink.create(
                app,
                onConnected = { address, name -> appScope.launch { settingsRepository.rememberDevice(address, name) } },
                shouldReconnect = { settings.value.autoConnect },
            )
        }
    }

    /** The Bluetooth link; on an emulator in debug builds, a stand-in that logs what it would send. */
    val pianoLink: PianoLink by link

    /** The link if something has made it already, else null: the crash handler's view, which must never make one. */
    fun pianoLinkIfMade(): PianoLink? = if (link.isInitialized()) link.value else null

    val player: Player by lazy { Player(pianoLink, library, appScope) }

    /** The piano's own settings over its console, read on every connection. */
    val pianoSettings: PianoSettingsRepository by lazy { PianoSettingsRepository(pianoLink, appScope) }

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
     * piano's firmware is looked for on the same switch (v1.6.1 — M21), while a piano that can be
     * updated is connected.
     */
    suspend fun runUpdateSchedule() = coroutineScope {
        val switch = settingsRepository.settings.map { it.checkForUpdates }.distinctUntilChanged()
        launch { firmwareUpdater.runSchedule(switch) }
        updateChecker.runSchedule(switch)
    }

    /**
     * Debug builds on an emulator only: the fake firmware update `debug.stevenpiano.fakeota` names
     * (its release, its image, RFC 8032's test key); null everywhere else.
     */
    private val fakeOta: FakeOta? by lazy { FakeOta.fromProperty() }

    /**
     * The piano's firmware updates over the Bluetooth link (v1.6.1 — M21): its signed releases from
     * the firmware repository, checked against the author's key; on the emulator with a fake
     * scenario, the scenario's release and the test key.
     */
    val firmwareUpdater: FirmwareUpdater by lazy {
        val fake = fakeOta
        FirmwareUpdater(
            link = pianoLink,
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
        appScope.launch(Dispatchers.IO) {
            runCatching { ImportLimits.sweepStale(app.cacheDir, app.filesDir, before = startedAt) }
            runCatching { updateDownloader.sweep(before = startedAt) }
            latestCrash.value = runCatching { crashReports.latestAt() }.getOrNull()
            runCatching { DeviceOwnerRelease.releaseIfAsked(app) }
        }
        // The built-in playlists follow the library: now (the first query opens the database), and
        // two seconds after a run of renames ends (imports refresh them from ImportService).
        appScope.launch { refreshBuiltIns() }
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
        channelPools.summaries   // the channels' pools are worked out from the start, for the Library's first look
        channelPlayer.start()
        web.start()
        firmwareUpdater.start()
        appScope.launch {
            val s = settingsRepository.settings.first()
            // Permission is only ever asked for on the Piano tab; without it, launch stays quiet.
            if (s.autoConnect && BlePermissions.missing(app).isEmpty()) pianoLink.connect(s.lastDeviceAddress)
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
     * The app came to the foreground (where a foreground service may start): with Web control on and
     * a PIN set, the web service starts, after a restart of the tablet or of the app.
     */
    fun startWebIfOn(context: Context) {
        val appContext = context.applicationContext
        appScope.launch {
            val s = settingsRepository.settings.first()
            if (s.webEnabled && s.webPinSet && !web.status.value.running) WebService.start(appContext)
        }
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
