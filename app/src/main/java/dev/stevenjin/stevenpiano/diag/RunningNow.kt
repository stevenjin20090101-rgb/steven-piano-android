// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import dev.stevenjin.stevenpiano.audio.SoundDownload
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
import dev.stevenjin.stevenpiano.audio.TabletSoundState
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.data.art.ArtworkProgress
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.library.PackState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.ui.ArtworkCopy
import dev.stevenjin.stevenpiano.ui.FirmwareCopy
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.ImportCopy
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.LibraryCopy
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.ui.TabletSoundCopy
import dev.stevenjin.stevenpiano.ui.UpdateCopy
import dev.stevenjin.stevenpiano.update.UpdateState
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * What the app is doing now, as the System page lists it (v1.18 — M46): always the same twelve rows, in the same order
 * ([KEYS]), each built from what the app already knows ([Inputs]) and said as the app says it elsewhere (its copy
 * objects). Pure, so it is tested on the JVM.
 */
object RunningNow {
    const val RUNNING = "running"
    const val WAITING = "waiting"
    const val IDLE = "idle"
    const val OFF = "off"
    const val PROBLEM = "problem"

    /** The rows' keys, in the order they are listed. */
    val KEYS = listOf("player", "link", "web", "relay", "covers", "import", "studio", "pack", "update", "firmware", "schedule", "sound")

    /**
     * One row: its [key] and [title]; its [state] ([RUNNING], [WAITING], [IDLE], [OFF] or [PROBLEM]); [detail], one plain
     * sentence fragment; [progress] 0–1 when there is a measure of it, else null.
     */
    data class Activity(val key: String, val title: String, val state: String, val detail: String, val progress: Double? = null)

    /** The player: its status, the piece's [title] and [composer], the [channel]'s name when one plays, where it is. */
    data class Player(
        val status: PlaybackStatus = PlaybackStatus.Stopped,
        val loading: Boolean = false,
        val title: String? = null,
        val composer: String? = null,
        val channel: String? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val problem: String? = null,
    )

    /** The instrument's link ([kind], [state], the [instrument]'s name), the keyboard, and Live and a take when on. */
    data class Link(
        val kind: InstrumentKind = InstrumentKind.StevenPiano,
        val state: LinkState = LinkState.Disconnected,
        val instrument: String = InstrumentCopy.STEVEN_PIANO,
        val keyboard: KeyboardState = KeyboardState(),
        val live: Boolean = false,
        val recording: Boolean = false,
    )

    /** The web panel: [on] (listening, or over the internet), the devices signed in, the panels open, whether guests may ask. */
    data class Web(val on: Boolean = false, val sessions: Int = 0, val sockets: Int = 0, val guests: Boolean = false)

    /** The internet link ([status]) and the requests it [answered] and [refused] since it connected (null without a client). */
    data class Relay(val status: CloudStatus = CloudStatus.Off, val answered: Int? = null, val refused: Int? = null)

    /** The artwork worker's run, and when Apple's hour-long stop ends ([blockedUntil], epoch ms; null when none is on). */
    data class Covers(val progress: ArtworkProgress = ArtworkProgress.Idle, val blockedUntil: Long? = null)

    /** Studio's [jobs] (oldest first), and why it can't run here ([unavailable]; null when it can). */
    data class Studio(val jobs: List<StudioJob> = emptyList(), val unavailable: String? = null)

    /**
     * Everything the rows are built from; [schedule] is the next start's line ("Next: Wednesday 12:30, Calm"), [now] the
     * wall clock (epoch ms) and [zone] its zone, for the times shown.
     */
    data class Inputs(
        val player: Player = Player(),
        val link: Link = Link(),
        val web: Web = Web(),
        val relay: Relay = Relay(),
        val covers: Covers = Covers(),
        val import: ImportProgress = ImportProgress.Idle,
        val studio: Studio = Studio(),
        val pack: PackState = PackState.Idle,
        val update: UpdateState = UpdateState.Idle,
        val firmware: FirmwareState = FirmwareState.Idle,
        val schedule: String? = null,
        val sound: TabletSoundState = TabletSoundState(),
        val now: Long = 0,
        val zone: ZoneId = ZoneId.systemDefault(),
        val locale: Locale = Locale.getDefault(),
    )

    fun of(inputs: Inputs): List<Activity> = listOf(
        player(inputs.player),
        link(inputs.link),
        web(inputs.web, inputs.locale),
        relay(inputs.relay, inputs.now),
        covers(inputs.covers, inputs.now, inputs.zone),
        import(inputs.import),
        studio(inputs.studio, inputs.locale),
        pack(inputs.pack, inputs.locale),
        update(inputs.update, inputs.locale),
        firmware(inputs.firmware),
        schedule(inputs.schedule),
        sound(inputs.sound, inputs.locale),
    )

    /** The internet link's state on the wire: `connected`, `reconnecting`, `off`, or `stopped` (revoked, removed, or the key gone). */
    fun relayWord(status: CloudStatus): String = when (status) {
        is CloudStatus.Connected -> "connected"
        CloudStatus.Connecting, is CloudStatus.Waiting -> "reconnecting"
        CloudStatus.Off -> "off"
        CloudStatus.NotEnrolled, CloudStatus.Revoked, CloudStatus.Disabled -> "stopped"
    }

    private fun player(p: Player): Activity {
        val piece = p.title?.let { listOf(it) + listOfNotNull(p.composer?.takeIf(String::isNotBlank)) }.orEmpty()
        val channel = p.channel?.let { "$it channel" }
        val progress = if (p.title != null && p.durationMs > 0) fraction(p.positionMs.coerceIn(0, p.durationMs).toDouble() / p.durationMs) else null
        return when {
            p.problem != null -> Activity("player", "Player", PROBLEM, fragment(p.problem), progress)
            p.loading -> Activity("player", "Player", RUNNING, line(listOf("Loading…") + piece + listOfNotNull(channel)), null)
            p.status == PlaybackStatus.Playing -> Activity("player", "Player", RUNNING, line(listOf("Playing") + piece + listOfNotNull(channel)), progress)
            p.status == PlaybackStatus.Paused -> Activity("player", "Player", WAITING, line(listOf("Paused") + piece + listOfNotNull(channel)), progress)
            piece.isNotEmpty() -> Activity("player", "Player", IDLE, line(listOf("Stopped") + piece), progress)
            else -> Activity("player", "Player", IDLE, "Stopped · nothing loaded")
        }
    }

    private fun link(l: Link): Activity {
        val state = l.state
        val words = when (state) {
            is LinkState.Connected -> "Connected to ${state.name}" + if (state.mtu > 0) " · MTU ${state.mtu}" else ""
            is LinkState.Error -> fragment(state.message)
            else -> InstrumentCopy.linkWords(l.kind, state)
        }
        val instrument = l.instrument.takeIf { l.kind == InstrumentKind.MidiPiano && state !is LinkState.Connected }
        val keyboard = l.keyboard.chosen?.let { "Keyboard: ${it.name} (${it.transport.label}), ${InstrumentCopy.stateWords(l.keyboard.phase).lowercase(Locale.ROOT)}" }
        val detail = line(listOfNotNull(instrument, words, keyboard, "Live".takeIf { l.live }, "Recording".takeIf { l.recording }))
        val shown = when (state) {
            is LinkState.Connected -> RUNNING
            LinkState.Scanning, LinkState.Connecting, is LinkState.Reconnecting -> WAITING
            LinkState.Disconnected -> OFF
            is LinkState.Error -> PROBLEM
        }
        return Activity("link", "Piano link", shown, detail)
    }

    private fun web(w: Web, locale: Locale): Activity {
        if (!w.on) return Activity("web", "Web panel", OFF, "Off")
        val detail = line(
            listOf(
                "${Format.count(w.sessions, "device", "devices", locale)} signed in",
                "${Format.count(w.sockets, "panel", "panels", locale)} open",
                if (w.guests) "guests can request" else "guests can't request",
            ),
        )
        return Activity("web", "Web panel", RUNNING, detail)
    }

    private fun relay(r: Relay, now: Long): Activity {
        val counts = if (r.answered != null && r.refused != null) listOf("${r.answered} answered", "${r.refused} refused") else emptyList()
        return when (val s = r.status) {
            is CloudStatus.Connected -> Activity("relay", "Internet relay", RUNNING, line(listOf("Connected to ${s.host}") + counts))
            CloudStatus.Connecting -> Activity("relay", "Internet relay", WAITING, "Connecting…")
            is CloudStatus.Waiting -> {
                val left = (s.retryInMs - (now - s.since)).coerceAtLeast(0)
                Activity("relay", "Internet relay", WAITING, "${s.reason} · trying again in ${(left + 999) / 1000} s")
            }
            CloudStatus.Off -> Activity("relay", "Internet relay", OFF, "Off")
            CloudStatus.NotEnrolled -> Activity("relay", "Internet relay", PROBLEM, "This tablet's key is gone; enrol it again")
            CloudStatus.Revoked -> Activity("relay", "Internet relay", PROBLEM, "Revoked in the console; enrol this tablet again")
            CloudStatus.Disabled -> Activity("relay", "Internet relay", PROBLEM, "Removed from the console; enrol this tablet again")
        }
    }

    private fun covers(c: Covers, now: Long, zone: ZoneId): Activity {
        val run = c.progress
        val until = c.blockedUntil?.takeIf { it > now }
        return when {
            !run.idle -> Activity(
                "covers", "Artwork", RUNNING,
                line(listOfNotNull(ArtworkCopy.running(run), run.current)),
                if (run.total > 0) fraction(run.done.toDouble() / run.total) else null,
            )
            until != null -> Activity("covers", "Artwork", WAITING, "Apple asked to wait · covers again at ${clock(until, zone)}")
            else -> Activity("covers", "Artwork", IDLE, "Nothing to fetch")
        }
    }

    private fun import(i: ImportProgress): Activity = when {
        !i.finished -> Activity("import", "Import", RUNNING, ImportCopy.running(i), if (i.total > 0) fraction(i.done.toDouble() / i.total) else null)
        i.total > 0 -> Activity("import", "Import", IDLE, fragment(ImportCopy.summary(i)))
        else -> Activity("import", "Import", IDLE, "Nothing importing")
    }

    private fun studio(s: Studio, locale: Locale): Activity {
        s.unavailable?.let { return Activity("studio", "Studio", OFF, fragment(it)) }
        val running = s.jobs.firstOrNull { it.state == JobState.Running }
        if (running != null) return Activity("studio", "Studio", RUNNING, StudioCopy.libraryLine(running, locale), running.progress?.toDouble()?.let(::fraction))
        val waiting = s.jobs.filter { it.state == JobState.Queued }
        if (waiting.isNotEmpty()) {
            val more = if (waiting.size > 1) " and ${waiting.size - 1} more" else ""
            return Activity("studio", "Studio", WAITING, "Waiting · ${StudioCopy.jobName(waiting.first())}$more")
        }
        return Activity("studio", "Studio", IDLE, "Nothing to do")
    }

    private fun pack(p: PackState, locale: Locale): Activity = when (p) {
        PackState.Checking, is PackState.Downloading ->
            Activity("pack", "Steven's library", RUNNING, LibraryCopy.bar(p, locale) ?: "Loading Steven's library…", LibraryCopy.progress(p)?.toDouble()?.let(::fraction))
        PackState.Importing -> Activity("pack", "Steven's library", RUNNING, "Adding the pieces…")
        is PackState.Failed -> Activity("pack", "Steven's library", PROBLEM, fragment(p.line))
        is PackState.Offered -> Activity("pack", "Steven's library", WAITING, "Version ${p.version} is on offer · ${Format.count(p.newPieces, "new piece", "new pieces", locale)}")
        is PackState.Done -> Activity("pack", "Steven's library", IDLE, "Loaded · ${Format.count(p.added, "piece", "pieces", locale)} added")
        PackState.Idle -> Activity("pack", "Steven's library", IDLE, "Nothing loading")
    }

    private fun update(u: UpdateState, locale: Locale): Activity = when (u) {
        UpdateState.Idle -> Activity("update", "App update", IDLE, "Not checked yet")
        UpdateState.Checking -> Activity("update", "App update", RUNNING, UpdateCopy.CHECKING)
        UpdateState.UpToDate -> Activity("update", "App update", IDLE, fragment(UpdateCopy.UP_TO_DATE))
        is UpdateState.Available -> Activity("update", "App update", WAITING, UpdateCopy.available(u.manifest.versionName))
        is UpdateState.Downloading -> Activity(
            "update", "App update", RUNNING,
            UpdateCopy.downloading(u.manifest.versionName, u.bytes, u.total, locale),
            if (u.total > 0) fraction(u.bytes.toDouble() / u.total) else null,
        )
        is UpdateState.ReadyToInstall -> Activity("update", "App update", WAITING, UpdateCopy.ready(u.manifest.versionName))
        is UpdateState.Installing -> Activity("update", "App update", RUNNING, UpdateCopy.installing(u.manifest.versionName))
        is UpdateState.Installed -> Activity("update", "App update", if (u.restartNeeded) WAITING else IDLE, UpdateCopy.installed(u.version, u.restartNeeded))
        is UpdateState.Failed -> Activity("update", "App update", PROBLEM, fragment(u.message))
    }

    private fun firmware(f: FirmwareState): Activity = when (f) {
        FirmwareState.Idle -> Activity("firmware", "Piano firmware", IDLE, "Not checked yet")
        FirmwareState.Checking -> Activity("firmware", "Piano firmware", RUNNING, FirmwareCopy.CHECKING)
        is FirmwareState.UpToDate -> Activity("firmware", "Piano firmware", IDLE, fragment(FirmwareCopy.UP_TO_DATE))
        is FirmwareState.Available -> Activity("firmware", "Piano firmware", WAITING, FirmwareCopy.available(f.manifest.version))
        is FirmwareState.UsbOnly -> Activity("firmware", "Piano firmware", WAITING, "${f.manifest.version}: ${FirmwareCopy.USB_ONLY.lowercase(Locale.ROOT)}")
        is FirmwareState.NeedsNewerApp -> Activity("firmware", "Piano firmware", WAITING, "${f.manifest.version}: ${FirmwareCopy.NEEDS_NEWER_APP.lowercase(Locale.ROOT)}")
        is FirmwareState.Downloading, is FirmwareState.Verifying, is FirmwareState.Sending, is FirmwareState.PianoVerifying, is FirmwareState.Restarting ->
            Activity("firmware", "Piano firmware", RUNNING, FirmwareCopy.progress(f) ?: FirmwareCopy.NOTIFICATION_TITLE, FirmwareCopy.fraction(f)?.toDouble()?.let(::fraction))
        is FirmwareState.Done -> Activity("firmware", "Piano firmware", if (f.confirming) WAITING else IDLE, FirmwareCopy.done(f.version, f.confirming))
        is FirmwareState.Failed -> Activity("firmware", "Piano firmware", PROBLEM, fragment(f.message))
    }

    private fun schedule(next: String?): Activity =
        if (next != null) Activity("schedule", "Schedule", WAITING, next) else Activity("schedule", "Schedule", IDLE, "Nothing scheduled")

    private fun sound(s: TabletSoundState, locale: Locale): Activity {
        val download = s.download
        return when {
            s.mode == TabletSoundMode.OFF -> Activity("sound", "Tablet sound", OFF, "Off")
            download is SoundDownload.Running && s.needsDownload ->
                Activity("sound", "Tablet sound", RUNNING, TabletSoundCopy.nowPlayingNote(s, locale = locale) ?: "Downloading the piano sound", TabletSoundCopy.progress(s)?.toDouble()?.let(::fraction))
            s.active -> Activity("sound", "Tablet sound", RUNNING, fragment(TabletSoundCopy.status(s)))
            else -> Activity("sound", "Tablet sound", IDLE, fragment(TabletSoundCopy.status(s)))
        }
    }

    /** Parts joined as the app joins them, " · ". */
    private fun line(parts: List<String>): String = parts.filter { it.isNotBlank() }.joinToString(" · ")

    /** A sentence as a fragment: without its full stop (an ellipsis stays). */
    private fun fragment(text: String): String = text.trim().let { if (it.endsWith(".") && !it.endsWith("..")) it.dropLast(1) else it }

    /** 0–1, three decimals. */
    private fun fraction(value: Double): Double? = value.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.let { Math.round(it * 1000) / 1000.0 }

    /** "14:32" in [zone]. */
    private fun clock(epochMs: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        return "%02d:%02d".format(Locale.ROOT, at.hour, at.minute)
    }
}
