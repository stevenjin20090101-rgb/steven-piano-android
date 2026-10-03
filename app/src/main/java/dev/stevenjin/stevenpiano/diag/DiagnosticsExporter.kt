// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.LiveState
import dev.stevenjin.stevenpiano.instruments.LiveTrip
import dev.stevenjin.stevenpiano.instruments.MidiNames
import dev.stevenjin.stevenpiano.settings.InstrumentChoice
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.update.UpdateState
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The file Share diagnostics sends: `cacheDir/diagnostics/steven-piano-diagnostics-<date>.zip`
 * (the one folder of the cache the app's FileProvider shares for it), holding exactly
 * - `about.txt`: the app's version and build, the device's model and Android, whether the app is
 *   the device owner, the updater's state, the piano link's state, the instrument and the keyboard
 *   (v1.11 — M29) ([about]);
 * - `settings.txt`: the app's preferences, the remembered piano's address among them ([settings]);
 * - `link.log`: the piano link's last lines ([linkLog]);
 * - the crash reports kept ([crashes]), each cut at [MAX_REPORT_CHARS].
 *
 * Nothing from the library (no titles, playlists or files), no photos, no Wikipedia text: the
 * exporter is given none of them. Each export replaces the one before.
 */
class DiagnosticsExporter(
    private val outDir: File,
    private val crashes: CrashReports,
    private val linkLog: LinkLog,
    private val about: () -> String,
    private val settings: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Writes the zip and returns it. Throws IOException when it can't be written (a full disk). */
    fun export(): File {
        if (!outDir.isDirectory) outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        val at = clock()
        val zip = File(outDir, "steven-piano-diagnostics-${FILE_STAMP.format(Instant.ofEpochMilli(at).atZone(zone))}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            fun entry(name: String, text: String) {
                out.putNextEntry(ZipEntry(name).apply { time = at })
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            entry(ABOUT, about())
            entry(SETTINGS, settings())
            entry(LINK_LOG, linkLog.text().ifEmpty { "No piano link lines since the app started.\n" })
            for (report in crashes.reports()) {
                val text = report.readText()
                entry(report.name, if (text.length <= MAX_REPORT_CHARS) text else text.substring(0, MAX_REPORT_CHARS))
            }
        }
        return zip
    }

    companion object {
        const val ABOUT = "about.txt"
        const val SETTINGS = "settings.txt"
        const val LINK_LOG = "link.log"
        const val MAX_REPORT_CHARS = 128 * 1024
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT)
    }
}

/** The text of `about.txt` and `settings.txt`, and a crash report's header: pure, so it is tested. */
object DiagnosticsText {
    /** What the app knows about itself and the device. */
    data class Facts(
        val versionName: String,
        val versionCode: Int,
        val buildType: String,
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdkInt: Int,
    )

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)

    /** A moment as `about.txt` writes it: local time with its offset. */
    fun stamp(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = STAMP.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** The lines that open a crash report and `about.txt`. */
    fun header(facts: Facts): String = buildString {
        append("App: Steven Piano ").append(facts.versionName).append(" (build ").append(facts.versionCode).append(", ").append(facts.buildType).append(")\n")
        append("Device: ").append(facts.manufacturer).append(' ').append(facts.model).append('\n')
        append("Android: ").append(facts.androidRelease).append(" (API ").append(facts.sdkInt).append(')')
    }

    /** `about.txt`. */
    fun about(
        facts: Facts,
        deviceOwner: Boolean,
        update: UpdateState,
        checkForUpdates: Boolean,
        lastCheckedAt: String?,
        link: LinkState?,
        exportedAt: String,
        instrument: String = "Steven Piano",
        keyboard: String = "none",
    ): String = buildString {
        append("Steven Piano diagnostics\n")
        append("Exported: ").append(exportedAt).append('\n')
        append(header(facts)).append('\n')
        append("Device owner: ").append(if (deviceOwner) "yes (updates install without a tap)" else "no").append('\n')
        append("Updates: ").append(updateLine(update)).append('\n')
        append("Automatic update checks: ").append(if (checkForUpdates) "on" else "off")
        append(", last check ").append(lastCheckedAt ?: "none yet in this session").append('\n')
        append("Piano link: ").append(linkLine(link)).append('\n')
        append("Instrument: ").append(instrument).append('\n')
        append("Keyboard: ").append(keyboard).append('\n')
    }

    /** `about.txt`'s Instrument line (v1.11 — M29): "Steven Piano", or "FP-30X (a MIDI piano, USB)". */
    fun instrumentLine(s: PianoSettings): String {
        if (s.instrumentKind != InstrumentChoice.MIDI_PIANO || s.midiOutId == null) return "Steven Piano"
        val transport = MidiNames.transportOf(s.midiOutId)?.label
        return MidiNames.clean(s.midiOutName).ifEmpty { "A MIDI piano" } + " (a MIDI piano" + (transport?.let { ", $it" } ?: "") + ")"
    }

    /**
     * `about.txt`'s Keyboard line (v1.11 — M29): "none", or the keyboard, its state and Live's: "FP-30X (Bluetooth),
     * connected, Live on", "…, Live off (too many notes at once)".
     */
    fun keyboardLine(keyboard: KeyboardState, live: LiveState): String {
        val chosen = keyboard.chosen ?: return "none"
        val state = when (keyboard.phase) {
            KeyboardState.Phase.Connected -> "connected"
            KeyboardState.Phase.Connecting -> "connecting"
            KeyboardState.Phase.NeedsPairing -> "asks to pair"
            KeyboardState.Phase.Unavailable -> "no MIDI on this tablet"
            KeyboardState.Phase.None, KeyboardState.Phase.NotConnected -> "not connected"
        }
        val gate = when {
            live.open -> "Live on"
            live.looped -> "Live off (the keyboard is the instrument too)"
            live.tripped != null -> "Live off (" + when (live.tripped) {
                LiveTrip.TooManyNotes -> "too many notes at once"
                LiveTrip.TooManyKeys -> "too many keys held"
                LiveTrip.Garbled -> "garbled bytes"
            } + ")"
            live.wanted -> "Live switched on, closed now"
            else -> "Live off"
        }
        return "${chosen.name} (${chosen.transport.label}), $state, $gate"
    }

    /** `settings.txt`: every preference, one a line; of the web panel's and the kiosk's PINs only whether one is set. */
    fun settings(s: PianoSettings): String = buildString {
        fun line(name: String, value: Any?) = append(name).append(" = ").append(value ?: "(none)").append('\n')
        line("autoConnect", s.autoConnect)
        line("lastDeviceAddress", s.lastDeviceAddress)
        line("lastDeviceName", s.lastDeviceName)
        line("noteDisplay", s.noteDisplay)
        line("notesSplitStacked", s.notesSplitStacked)
        line("notesSplitSide", s.notesSplitSide)
        line("preRollMs", s.preRollMs)
        line("defaultTempoPct", s.defaultTempoPct)
        line("transpose", s.transpose)
        line("velocityPct", s.velocityPct)
        line("foldOutOfRange", s.foldOutOfRange)
        line("skipDrumChannel", s.skipDrumChannel)
        line("keysViewportStart", s.keysViewportStart)
        line("shuffle", s.shuffle)
        line("repeat", s.repeat)
        line("artworkMonochrome", s.artworkMonochrome)
        line("fetchArtworkAutomatically", s.fetchArtworkAutomatically)
        line("albumCovers", s.albumCovers)
        line("fingering", s.fingering)
        line("chordNames", s.chordNames)
        line("handColours", s.handColours)
        line("checkForUpdates", s.checkForUpdates)
        line("channelVolumes", s.channelVolumes.toSortedMap().entries.joinToString(", ", "{", "}") { (key, pct) -> "$key=$pct" })
        line("appearance", s.appearance)
        line("displayModeAfterMinute", s.displayModeAfterMinute)
        line("standbyCanvas", s.standbyCanvas)
        line("standbyShows", s.standbyShows)
        line("webEnabled", s.webEnabled)
        line("webGuests", s.webGuests)
        line("webApproveFirst", s.webApproveFirst)
        line("webOnWifi", s.webOnWifi)
        line("webHostName", s.webHostName)
        line("webPinSet", s.webPinSet)
        line("kioskEnabled", s.kioskEnabled)
        line("kioskPinSet", s.kioskPinSet)
        line("tabletSound", s.tabletSound)
        line("tabletVolume", s.tabletVolume)
        // Steven Piano Cloud (v1.10 — M26): on or off, the relay's address, whether enrolled; never the piano's id or its secret.
        line("cloudEnabled", s.cloudEnabled)
        line("cloudHost", s.cloudHost)
        line("cloudEnrolled", s.cloudEnrolled)
        // Steven's library (v1.10 — M27): the version of the pack loaded, 0: none.
        line("libraryPackVersion", s.libraryPackVersion)
        // The Playlists listing's order (v1.10.1 — M28): a preference, so it is here; the repair of older uploads is not.
        line("playlistSort", s.playlistSort)
        line("libraryScope", s.libraryScope)
        // Keyboards and instruments (v1.11 — M29): the keyboard and the MIDI piano chosen, as the piano's address is.
        line("keyboardId", s.keyboardId)
        line("keyboardName", s.keyboardName)
        line("liveToPiano", s.liveToPiano)
        line("instrumentKind", s.instrumentKind)
        line("midiOutId", s.midiOutId)
        line("midiOutName", s.midiOutName)
    }

    fun updateLine(state: UpdateState): String = when (state) {
        UpdateState.Idle -> "not checked yet"
        UpdateState.Checking -> "checking"
        UpdateState.UpToDate -> "up to date"
        is UpdateState.Available -> "${state.manifest.versionName} (build ${state.manifest.versionCode}) available"
        is UpdateState.Downloading -> "downloading ${state.manifest.versionName}, ${state.bytes} of ${state.total} bytes"
        is UpdateState.ReadyToInstall -> "${state.manifest.versionName} downloaded and verified, waiting for the installer"
        is UpdateState.Installing -> "installing ${state.manifest.versionName}"
        is UpdateState.Installed -> "installed ${state.version}" + if (state.restartNeeded) ", restart pending" else ", running"
        is UpdateState.Failed -> "failed: ${state.message}" + (state.manifest?.let { " (${it.versionName} on offer)" } ?: "")
    }

    private fun linkLine(link: LinkState?): String = when (link) {
        null -> "not started"
        is LinkState.Connected -> if (link.mtu > 0) "connected (MTU ${link.mtu})" else "connected"   // a MIDI piano has none (v1.11 — M29)
        LinkState.Disconnected -> "not connected"
        LinkState.Scanning -> "looking for the piano"
        LinkState.Connecting -> "connecting"
        is LinkState.Reconnecting -> "reconnecting (attempt ${link.attempt})"
        is LinkState.Error -> "error: ${link.message}"
    }
}
