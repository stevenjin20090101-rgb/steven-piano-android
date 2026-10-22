// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.audio.AndroidMicrophone
import dev.stevenjin.stevenpiano.audio.KeyTest
import dev.stevenjin.stevenpiano.audio.Listening
import dev.stevenjin.stevenpiano.audio.MicrophoneStandIn
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.diag.AndroidSystemProbe
import dev.stevenjin.stevenpiano.diag.HeldKey
import dev.stevenjin.stevenpiano.diag.PianoDiag
import dev.stevenjin.stevenpiano.diag.QuietSince
import dev.stevenjin.stevenpiano.diag.RunningNow
import dev.stevenjin.stevenpiano.diag.SystemHistory
import dev.stevenjin.stevenpiano.diag.SystemProbe
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.power.RailFact
import dev.stevenjin.stevenpiano.power.RailKeeper
import dev.stevenjin.stevenpiano.power.RailPhase
import dev.stevenjin.stevenpiano.quiet.QuietCopy
import dev.stevenjin.stevenpiano.record.RecordingState
import dev.stevenjin.stevenpiano.studio.Studio
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.PowerCopy
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.update.UpdateState
import dev.stevenjin.stevenpiano.web.RefreshFloor
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the web panel and the relay ask of the app from afar, apart from [AppGraph]'s wiring (v1.27 — M63; the graph makes
 * it once, [AppGraph.remote]): Install the update ([updateFromAfar]), the power plug's fact ([railFact]), the system
 * reading (the probe, the day's samples, the facts' floor, the running rows' inputs) and Listen and Test every key. The
 * tablet's own System page reads the same. [graph] is the app's one graph, [app] its application.
 */
class RemoteActions internal constructor(private val graph: AppGraph, private val app: Application) {
    /** The tablet's and the app's readings for the web panel's System page (v1.18 — M46). */
    val systemProbe: SystemProbe by lazy { AndroidSystemProbe(app) }

    /** The System page's day: a sample a minute while the process lives ([startSystemSamples]), in memory only. */
    val systemHistory = SystemHistory()

    /**
     * The piano's live facts at most once in 10 s, whichever System page asks: the web panel's (`/api/system/refresh`) or the
     * tablet's own (v1.18 — M50); `firmware/docs/BLE_DIAG.md` promises the piano one round of reads in 10 s at most.
     */
    val factsFloor = RefreshFloor(clock = SystemClock::elapsedRealtime)

    /**
     * The app's update installed from a distance (v1.23, the panel's Install the update): checked now, then downloaded,
     * verified and handed to Android as the Update button does; once installed it starts again on the new version by
     * itself, the piano silenced first. False (nothing done) while a firmware update runs.
     */
    fun updateFromAfar(): Boolean {
        if (graph.firmwareUpdater.state.value.busy) return false
        graph.appScope.launch {
            graph.updateChecker.checkNow()
            when (val state = graph.updater.state.value) {
                is UpdateState.ReadyToInstall -> {
                    restartOnceInstalled()
                    graph.updater.install(state.manifest, state.file, app)
                }
                is UpdateState.Available, is UpdateState.Failed -> if (state.manifest != null) {
                    restartOnceInstalled()
                    dev.stevenjin.stevenpiano.service.UpdateService.start(app)
                }
                else -> Unit
            }
        }
        return true
    }

    /** After an update started from a distance: when Android reports it installed, the app starts again on it. */
    private fun restartOnceInstalled() {
        graph.appScope.launch {
            val ended = withTimeoutOrNull(REMOTE_UPDATE_WAIT_MS) {
                graph.updater.state.first { it is UpdateState.Installed || (it is UpdateState.Failed) }
            }
            if (ended is UpdateState.Installed && ended.restartNeeded) graph.restartForUpdate(app)
        }
    }

    /**
     * The plug and its rail in one fact for the web panel (the integrator adds it to `/api/system` and the state JSON after
     * the merge: BUILD_SPEC.md › v1.26 — M60): paired, the switch, where the rail stands, the plug's reading, the boards, the
     * tablet's words, a stuck relay, guest play's end.
     */
    val railFact: StateFlow<RailFact> by lazy {
        combine(
            combine(graph.plugSettings, graph.rail.phase, graph.rail.message) { s, phase, message -> Triple(s, phase, message) },
            combine(graph.plug.status, graph.rail.armedAt, graph.rail.boards) { status, armedAt, boards -> Triple(status, armedAt, boards) },
            combine(graph.rail.welded, graph.rail.guestUntil) { welded, guest -> welded to guest },
        ) { (s, phase, message), (status, armedAt, boards), (welded, guest) ->
            val wall = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            RailFact(
                paired = s?.pairing != null,
                on = s?.on == true,
                rail = RailFact.word(phase),
                waking = phase == RailPhase.Waking,
                output = status?.output,
                watts = status?.watts,
                timerEndsAt = armedAt?.let { it + RailKeeper.TOGGLE_AFTER_S * 1_000L + wall },
                answered = boards?.answered,
                total = boards?.total,
                message = message?.let(PowerCopy::message),
                attention = message?.attention == true,
                welded = welded,
                guestEndsAt = guest?.let { it + wall },
            )
        }.stateIn(graph.appScope, SharingStarted.Eagerly, RailFact())
    }

    /**
     * What the System pages' running rows ([RunningNow]) are built from now, the wall clock [now]: the app's own state, read
     * where each part keeps it (v1.18 — M46's gathering for the web panel's `/api/system`, lifted into the graph in M50 so the tablet's
     * own System page gathers it the same way). The quiet times' line (v1.20 — M54) is the Quiet times page's. Any thread.
     */
    suspend fun runningInputs(now: Long = System.currentTimeMillis()): RunningNow.Inputs {
        val prefs = graph.settings.value
        val panel = graph.web
        val cloud = panel.cloud.value
        val relay = panel.relay
        val hub = panel.hub
        val playerState = graph.player.state.value
        val piece = playerState.piece
        val kind = graph.pianoLink.kind.value
        val quietNow = graph.quiet.now(now)
        val quietLine = QuietCopy.status(quietNow, QuietCopy.at(now, java.time.ZoneId.systemDefault()))
        return RunningNow.Inputs(
            player = RunningNow.Player(
                status = playerState.status,
                loading = playerState.loading,
                title = piece?.title,
                composer = piece?.composer,
                channel = playerState.channel?.let { key -> graph.channelPools.summary(key)?.name ?: graph.channels.firstOrNull { it.key == key }?.name ?: key },
                positionMs = if (piece == null) 0 else graph.player.positionMicrosAt(System.nanoTime()) / MICROS_PER_MS,
                durationMs = (piece?.durationMicros ?: 0) / MICROS_PER_MS,
                problem = playerState.problem,
            ),
            link = RunningNow.Link(
                kind = kind,
                state = graph.pianoLink.state.value,
                instrument = InstrumentCopy.instrumentValue(kind, prefs.midiOutName),
                keyboard = graph.keyboard.state.value,
                live = graph.liveThru.state.value.open,
                recording = graph.recording.state.value is RecordingState.Recording,
            ),
            web = RunningNow.Web(
                on = panel.status.value.running || cloud != CloudStatus.Off,
                sessions = panel.sessions.count(),
                sockets = hub?.let { it.count + it.memberCount } ?: 0,
                guests = prefs.webGuests,
            ),
            relay = RunningNow.Relay(cloud, relay?.answered, relay?.refused),
            covers = RunningNow.Covers(graph.artwork.progress.value, graph.artwork.coversBlockedForMs()?.let { now + it }),
            import = graph.importProgress.value,
            studio = RunningNow.Studio(graph.studio.jobs.jobs.value, StudioCopy.unsupported(graph.studio.availability.support.value)),
            pack = graph.libraryPack.state.value,
            update = graph.updater.state.value,
            firmware = graph.firmwareUpdater.state.value,
            quiet = RunningNow.Quiet(quietNow.now, quietNow.overridden, quietLine),
            sound = graph.tabletSound.state.value,
            now = now,
        )
    }

    /**
     * The System page's day (v1.18 — M46): a sample now and every minute after, for as long as the process lives, off
     * the main thread; the piano's temperature only when its facts were read in the last two minutes ([SystemHistory]).
     */
    internal fun startSystemSamples() {
        graph.appScope.launch(Dispatchers.Default) {
            while (true) {
                try {
                    val piano = graph.pianoSettings.state.value as? PianoState.Ready
                    val at = System.currentTimeMillis()
                    systemHistory.add(SystemHistory.sampleOf(at, systemProbe.read(), piano?.facts?.get(TEMP_FACT), graph.pianoSettings.factsAt.value))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "A system sample couldn't be taken")
                }
                delay(SystemHistory.EVERY_MS)
            }
        }
    }

    // ---- Listening and the key test (v1.23 — M55) -------------------------------------------------------------------

    /** Whether Android allows the microphone (asked for by the System page's two switches, Listening from the panel and the safety check's). */
    fun microphoneAllowed(): Boolean = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * The tablet's microphone for the web panel (DESIGN.md › v1.23 — M55): only with Listening from the panel on and Android's
     * permission, only while the app is in the foreground ([MainActivity] says so), each frame to the hearing sessions'
     * sockets alone, through the web service's hub (the listeners' and the relay's), nothing kept. The tablet hears for
     * itself (its own Test every key, the safety check: v1.26 — M61) with Android's permission alone. On the emulator in
     * debug builds the microphone is a stand-in that hears the keys the key test strikes ([MicrophoneStandIn]).
     */
    val listening: Listening by lazy {
        Listening(
            graph.appScope,
            MicrophoneStandIn.of(AndroidMicrophone(app)) { keyTest.state },
            allowedNow = { graph.settings.value.listenFromPanel && microphoneAllowed() },
            sessionValid = { graph.web.sessions.isValid(it) },
            send = { token, text -> graph.web.hub?.sendToSession(token, text) ?: 0 },
            localAllowedNow = ::microphoneAllowed,
        )
    }

    /** Test every key (v1.23 — M55): every strike through the player's live path, the microphone hearing which keys sounded. */
    val keyTest: KeyTest by lazy { KeyTest(graph.appScope, keyTestPiano, listening, before = graph.keyTestRail) }

    /** The piano as the key test strikes it: the player's live keys and stop sequence, the instrument's link, the gates. */
    private val keyTestPiano = object : KeyTest.Piano {
        override val connected: Boolean get() = graph.pianoLink.state.value is LinkState.Connected

        override val playing: Boolean get() = graph.player.state.value.let { it.status == PlaybackStatus.Playing || it.loading }

        override fun quiet(): String? {
            val now = graph.quiet.now()
            val until = now.until?.takeIf { now.holds } ?: return null
            val zone = java.time.ZoneId.systemDefault()
            return QuietCopy.refusal(QuietCopy.until(QuietCopy.at(until, zone), QuietCopy.at(System.currentTimeMillis(), zone)))
        }

        override val updating: Boolean get() = graph.player.locked || graph.firmwareUpdater.state.value.busy

        override fun noteOn(key: Int, velocity: Int) = graph.player.liveNoteOn(key, velocity)

        override fun noteOff(key: Int) = graph.player.liveNoteOff(key)

        override fun stopSequence() = graph.player.allKeysOff()

        override fun muteTablet(on: Boolean) = graph.tabletSound.mute(on)
    }

    /** Since when nothing has played (a piece, the key test, a keyboard through Live): what a key held on is weighed against. */
    val quietSince = QuietSince(System.currentTimeMillis())

    /**
     * Whether the piano holds a key on now ([HeldKey]): Steven Piano connected and answering says it holds a key, in facts
     * that came in 5 s or more after anything last played.
     */
    fun keyHeld(): Boolean =
        HeldKey.held(PianoDiag.of(graph.pianoLink.kind.value, graph.pianoLink.state.value, graph.pianoSettings.state.value)?.active, graph.pianoSettings.factsAt.value, quietSince.since)

    /** The banner's Stop: the key test ends with the player's stop sequence, and nothing hears any more. */
    fun stopListening() {
        keyTest.stop()
        listening.stopAll()
    }

    /**
     * Listening from the panel turned off: what the panels hear ends (a key test a panel started, with the stop sequence);
     * the tablet's own key test and the safety check hear on (v1.26 — M61). From [AppGraph.settings] itself, so what [listening]
     * reads of it is already the new value.
     */
    internal fun followListening() {
        graph.appScope.launch {
            graph.settings.map { it.listenFromPanel }.distinctUntilChanged().collect { listening.refresh() }
        }
    }

    /** What plays, as [quietSince] follows it. */
    internal fun followPlaying() {
        graph.appScope.launch {
            combine(
                graph.player.state.map { it.status == PlaybackStatus.Playing || it.loading },
                keyTest.state.map { it.running },
                graph.liveThru.state.map { it.open },
            ) { piece, test, live -> piece || test || live }
                .distinctUntilChanged()
                .collect { quietSince.see(it, System.currentTimeMillis()) }
        }
    }

    private companion object {
        const val TAG = "AppGraph"   // the graph's own, as these lines were its until v1.27 (M63)
        const val MICROS_PER_MS = 1_000L

        /** How long an update started from a distance may take to download and install before the wait for it ends. */
        const val REMOTE_UPDATE_WAIT_MS = 20 * 60_000L

        /** The piano's controller temperature, °C, a fact of later firmware's `dump` (v1.18 — M46; BLE_DIAG.md). */
        const val TEMP_FACT = "temp"
    }
}
