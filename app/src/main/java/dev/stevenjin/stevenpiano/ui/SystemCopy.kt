// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.runtime.Immutable
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.diag.AppReading
import dev.stevenjin.stevenpiano.diag.Attention
import dev.stevenjin.stevenpiano.diag.PianoDiag
import dev.stevenjin.stevenpiano.diag.RunningNow
import dev.stevenjin.stevenpiano.diag.SystemReading
import dev.stevenjin.stevenpiano.diag.SystemSample
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.ui.components.DialValue
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The tablet's System page in words (v1.18 — M50): the web panel's `system.js` said the same way, with the same words for
 * the same states and the same ranges, so the two pages never disagree. Pure.
 */
object SystemCopy {
    const val TABLET = "Tablet"
    const val CONTROLLER = "Controller"
    const val PIANO = "Piano"
    const val RUNNING = "Running now"
    const val TODAY = "Today"
    const val TOOLS = "Tools"

    const val ALL_WELL = "Everything is running normally"
    const val READING_SYSTEM = "Reading the system…"
    const val NEEDS_FIRMWARE = "Needs newer firmware"
    const val NOT_CONNECTED = "Not connected"
    const val NOT_KNOWN = "Not known"

    // ---- Tools ---------------------------------------------------------------------------------------------
    const val READ_STATUS = "Read status"
    const val READING_STATUS = "Reading status…"
    const val FIND_COVERS = "Find missing covers"
    const val RECONNECT = "Reconnect the piano"
    const val SHARE_DIAGNOSTICS = "Share diagnostics"
    const val RECONNECT_TITLE = "Reconnect the piano?"
    const val RECONNECT_TEXT = "The link drops and connects again: the music stops for a few seconds."
    const val RECONNECT_ACTION = "Reconnect"
    const val COVERS_ASKED = "Looking again for the missing covers"
    const val RECONNECTING = "Reconnecting to the piano…"
    const val RECONNECT_BUSY = "Not while the piano's firmware is being updated"
    const val KEYS_OFF = "Every key let go"
    const val NO_ANSWER = "The piano didn't answer."

    // ---- Today ---------------------------------------------------------------------------------------------
    const val LAST_DAY = "Last 24 hours"
    const val NO_READINGS = "No readings yet: the tablet adds one a minute."
    const val BATTERY = "Battery"
    const val TABLET_HEAT = "Tablet temperature"
    const val PIANO_HEAT = "Piano temperature"

    /** The controller's and the piano's cards: another instrument plays, the piano isn't connected, it is being read, its firmware too old to say, or ready. */
    enum class Mode { Midi, Off, Reading, Old, Ready }

    fun modeOf(kind: InstrumentKind, link: LinkState, piano: PianoState): Mode = when {
        kind == InstrumentKind.MidiPiano -> Mode.Midi
        link !is LinkState.Connected -> Mode.Off
        piano is PianoState.Unsupported -> Mode.Old
        piano is PianoState.Ready -> Mode.Ready
        else -> Mode.Reading
    }

    /** A dial's word while it has no figure. */
    fun quiet(mode: Mode): String = when (mode) {
        Mode.Midi -> "Not in use"
        Mode.Off -> NOT_CONNECTED
        Mode.Reading -> "Reading…"
        Mode.Old, Mode.Ready -> NEEDS_FIRMWARE
    }

    // ---- The tablet ----------------------------------------------------------------------------------------

    /** "Lenovo Tab P11 · Android 13". */
    fun tabletLine(reading: SystemReading?): String =
        listOfNotNull(reading?.model, reading?.android?.let { "Android $it" }).joinToString(" · ")

    /** The battery: its charge, and Charging, Full, On battery, Low or what ails it (the panel's words). */
    fun battery(reading: SystemReading?, attention: Boolean): DialValue {
        if (reading == null) return DialValue.waiting("")
        val battery = reading.battery
        val percent = battery.percent ?: return DialValue.waiting(NOT_KNOWN)
        val low = percent < Attention.BATTERY_LOW_PCT && battery.charging != true
        val word = Attention.BAD_HEALTH[battery.health] ?: when {
            low -> "Low"
            battery.charging == true -> if (percent >= FULL) "Full" else "Charging"
            battery.charging == false -> "On battery"
            else -> ""
        }
        return DialValue(
            figure = "$percent", unit = "%", word = word, share = percent / FULL.toFloat(), attention = attention,
            current = percent.toFloat(), max = FULL.toFloat(), spoken = "$percent percent" + if (word.isNotEmpty()) ", ${word.lowercase()}" else "",
        )
    }

    /** The tablet's temperature, the battery's, 0–60 °C: Normal, Warm or Hot ([Attention.heatWord]). */
    fun temperature(reading: SystemReading?, attention: Boolean): DialValue {
        if (reading == null) return DialValue.waiting("")
        val celsius = reading.battery.tempC ?: return DialValue.waiting(NOT_KNOWN)
        val word = Attention.heatWord(reading.thermal.status, celsius)
        val shown = celsius.roundToInt()
        return DialValue(
            figure = "$shown", unit = "°C", word = word, share = (celsius / TABLET_HOT_END).toFloat(), attention = attention,
            current = shown.toFloat(), max = TABLET_HOT_END.toFloat(), spoken = "$shown degrees, ${word.lowercase()}",
        )
    }

    /** A meter's words and share: "2.4 of 4 GB in use". */
    @Immutable
    data class Level(val text: String, val share: Float)

    fun memory(reading: SystemReading?): Level? {
        if (reading == null) return null
        val total = reading.memory.total ?: return null
        val available = reading.memory.available ?: return null
        if (total <= 0) return null
        val used = (total - available).coerceAtLeast(0)
        return Level("${gb(used)} of ${gb(total)} GB in use" + if (reading.memory.low == true) " · low" else "", used.toFloat() / total)
    }

    fun storage(reading: SystemReading?, attention: Boolean): Level? {
        if (reading == null) return null
        val size = reading.storage.total ?: return null
        val free = reading.storage.free ?: return null
        if (size <= 0) return null
        val used = (size - free).coerceAtLeast(0)
        return Level("${gb(used)} of ${gb(size)} GB in use" + if (attention) " · almost full" else "", used.toFloat() / size)
    }

    /** The network row's name: Wi-Fi, or Network on Ethernet, mobile data or a VPN. */
    fun networkLabel(reading: SystemReading?): String =
        reading?.network?.transport?.takeIf { it != "wifi" }?.let { "Network" } ?: "Wi-Fi"

    /** "−58 dBm · 72 Mbps" on Wi-Fi, "Offline", or the network's kind; null when not known. */
    fun network(reading: SystemReading?): String? {
        val network = reading?.network ?: return null
        val transport = network.transport
        return when {
            network.online == false -> "Offline"
            transport == "wifi" -> listOfNotNull(
                network.signalDbm?.let { "${signed(it.toLong())} dBm" },
                network.downKbps?.let { "${(it / KBPS_PER_MBPS).roundToInt()} Mbps" },
            ).joinToString(" · ").ifEmpty { "Connected" }
            transport != null -> TRANSPORTS[transport] ?: transport
            else -> null
        }
    }

    fun screen(reading: SystemReading?): String? = when (reading?.screenOn) {
        true -> "On"
        false -> "Off"
        null -> null
    }

    // ---- The controller and the piano ----------------------------------------------------------------------

    /** The controller card's line: the instrument that plays instead, "Not connected", or the chip and its firmware. */
    fun controllerLine(mode: Mode, diag: PianoDiag?, instrument: String): String = when (mode) {
        Mode.Midi -> "$instrument is the instrument"
        Mode.Off -> NOT_CONNECTED
        else -> diag?.fw?.let { "ESP32-S3 · firmware $it" } ?: "ESP32-S3"
    }

    /** The piano card's line: "84 keys · 7 power boards", the other instrument, "Not connected", or nothing yet. */
    fun pianoLine(mode: Mode, boards: List<String>?, instrument: String): String = when {
        boards != null -> "${boards.size * KEYS_PER_BOARD} keys · ${boards.size} power boards"
        mode == Mode.Midi -> "$instrument is the instrument"
        mode == Mode.Off -> NOT_CONNECTED
        else -> ""
    }

    /** The controller's chip, 0–80 °C: Normal, or Hot from 70 °C. */
    fun chip(mode: Mode, diag: PianoDiag?, attention: Boolean): DialValue {
        val chip = diag?.temp?.takeIf { mode == Mode.Ready } ?: return DialValue.waiting(if (mode == Mode.Ready) NEEDS_FIRMWARE else quiet(mode))
        val shown = chip.roundToInt()
        val word = if (chip >= Attention.CHIP_HOT_C) Attention.HOT else Attention.NORMAL
        return DialValue(
            figure = "$shown", unit = "°C", word = word, share = (chip / CHIP_HOT_END).toFloat(), attention = attention,
            current = shown.toFloat(), max = CHIP_HOT_END.toFloat(), spoken = "$shown degrees, ${word.lowercase()}",
        )
    }

    /**
     * The controller's memory: the share in use of its heap's size with "212 KB free" (its size the dial's far end), else
     * the free figure alone and no arc; "Ran low" while it once fell under 30 KB. The second is the far end's label.
     */
    fun heap(mode: Mode, diag: PianoDiag?, attention: Boolean): Pair<DialValue, String> {
        val ready = diag?.takeIf { mode == Mode.Ready }
        val heap = ready?.heap
        if (ready == null || heap == null) return DialValue.waiting(if (mode == Mode.Ready) NEEDS_FIRMWARE else quiet(mode)) to ""
        val lowest = ready.heapMin?.takeIf { attention }?.let { ", ran as low as ${kb(it)} kilobytes" }.orEmpty()
        val size = ready.heapSize?.takeIf { it > 0 }
        if (size != null) {
            val share = ((size - heap).toFloat() / size).coerceIn(0f, 1f)
            val shown = (share * PERCENT).roundToInt()
            return DialValue(
                figure = "$shown", unit = "%", word = if (attention) RAN_LOW else "${kb(heap)} KB free", share = share, attention = attention,
                current = shown.toFloat(), max = PERCENT, spoken = "$shown percent in use, ${kb(heap)} kilobytes free$lowest",
            ) to "${kb(size)} KB"
        }
        val kilobytes = (heap / BYTES_PER_KB).roundToInt()
        return DialValue(
            figure = kb(heap), unit = "KB", word = if (attention) RAN_LOW else "free", share = null, attention = attention,
            current = kilobytes.toFloat(), max = kilobytes.coerceAtLeast(1).toFloat(), spoken = "${kb(heap)} kilobytes free$lowest",
        ) to ""
    }

    /**
     * A row of a card: its [label] and its [value]; null [value] reads "—", [needs] reads the small "Needs newer firmware"
     * tag, [attention] colours the value, [live] (the Bluetooth row) puts the live dot before it.
     */
    @Immutable
    data class Fact(val label: String, val value: String?, val needs: Boolean = false, val attention: Boolean = false, val live: Boolean? = null)

    /** The controller's rows. A fact the piano doesn't give needs newer firmware once it is ready (or too old to say); else "—". */
    fun controllerFacts(mode: Mode, diag: PianoDiag?): List<Fact> {
        val ready = diag?.takeIf { mode == Mode.Ready }
        val needs = mode == Mode.Ready || mode == Mode.Old
        fun fact(label: String, value: String?) = Fact(label, value, needs = value == null && needs)
        return listOf(
            fact("Running for", ready?.uptime?.let(::duration)),
            fact("Update state", ready?.ota?.let { OTA_WORDS[it] ?: capital(it) }),
            fact("Tasks", ready?.let { d -> joined(d.tasks?.toString(), d.stack?.let { "loop stack ${kb(it)} KB spare" }) }),
            fact("Speed", ready?.let { d -> joined(d.loopRate?.let { "${count(it)} passes a second" }, d.loopMs?.let { "longest ${"%.1f".format(Locale.ROOT, it)} ms" }) }),
            fact("Last start", ready?.let { d -> joined(d.reset?.let { RESET_WORDS[it] ?: capital(it) }, d.crashes?.let { "$it ${if (it == 1L) "crash" else "crashes"}" }) }),
        )
    }

    /**
     * The piano's rows: its firmware, the power boards ([boardsAttention]: one is missing), Bluetooth with the live dot
     * ([link]; none while another instrument plays), the pedal board, I²C errors, the repeat period, the keys held now and
     * the hold watchdog; then every other fact it gives, under its own name.
     */
    fun pianoFacts(mode: Mode, diag: PianoDiag?, link: LinkState, boardsAttention: Boolean): List<Fact> {
        val ready = diag?.takeIf { mode == Mode.Ready }
        val needs = mode == Mode.Ready || mode == Mode.Old
        fun fact(label: String, value: String?, attention: Boolean = false) = Fact(label, value, needs = value == null && needs, attention = attention && value != null)
        val boards = ready?.boards
        val boardsLine = boards?.let { list ->
            val absent = list.indices.filter { list[it] == PianoDiag.MISSING }.map { "C${it + 1}" }
            "${list.count { it == PianoDiag.OK }} of ${list.size} answering" + if (absent.isNotEmpty()) " · ${absent.joinToString(", ")} missing" else ""
        }
        val bluetooth = if (mode == Mode.Midi) {
            Fact("Bluetooth", null)
        } else {
            Fact("Bluetooth", joined(linkWord(link), ready?.rssi?.let { "${signed(it)} dBm" }), live = link is LinkState.Connected)
        }
        val named = listOf(
            fact("Firmware", ready?.let { d -> d.fw?.let { joined(it, d.ota) } }),
            fact("Power boards", boardsLine, boardsAttention),
            bluetooth,
            fact("Pedal board", ready?.pedalBoard?.let(::capital)),
            fact("I²C errors", ready?.i2cFails?.let(::count)),
            fact("Repeat period", ready?.repeatMs?.let { "$it ms" }),
            fact("Keys held now", ready?.active?.let(::count)),
            fact("Hold watchdog", ready?.trips?.let { "${count(it)} ${if (it == 1L) "release" else "releases"} since start" }),
        )
        val others = ready?.facts.orEmpty().filterKeys { it !in SHOWN_FACTS }.toSortedMap().map { (name, value) -> Fact(name, value) }
        return named + others
    }

    /** The link as the Bluetooth row says it. */
    fun linkWord(link: LinkState): String = when (link) {
        is LinkState.Connected -> "Connected"
        LinkState.Disconnected -> NOT_CONNECTED
        LinkState.Scanning -> "Looking for it…"
        LinkState.Connecting -> "Connecting…"
        is LinkState.Reconnecting -> "Reconnecting…"
        is LinkState.Error -> "Couldn't connect"
    }

    // ---- Running now ---------------------------------------------------------------------------------------

    /** "Steven Piano 1.18 · 41 threads · 48 MB · 3% of a core". */
    fun appLine(app: AppReading?): String = listOfNotNull(
        app?.version?.let { "Steven Piano $it" } ?: "Steven Piano",
        app?.threads?.let { "$it threads" },
        app?.heapUsed?.let { "${(it / BYTES_PER_MB).roundToLong()} MB" },
        app?.cpuPct?.let { pct -> "${if (pct > 0 && pct < 1) "%.1f".format(Locale.ROOT, pct) else pct.roundToInt().toString()}% of a core" },
    ).joinToString(" · ")

    /** A running row's dot: the live red for the player while it plays to the piano, amber, filled, or hollow. */
    enum class Dot { Live, Attention, On, Hollow }

    fun dotOf(row: RunningNow.Activity, playingToPiano: Boolean): Dot = when {
        row.key == PLAYER && row.state == RunningNow.RUNNING && playingToPiano -> Dot.Live
        row.state == RunningNow.PROBLEM || (row.state == RunningNow.WAITING && row.key in WAITING_NEEDS_ATTENTION) -> Dot.Attention
        row.state == RunningNow.RUNNING -> Dot.On
        else -> Dot.Hollow
    }

    /** A running row's state in a word: Playing, Connected, Serving…; Paused, Reconnecting, Waiting; Problem; Off; Idle (the player's Stopped). */
    fun stateWord(row: RunningNow.Activity, loading: Boolean): String = when (row.state) {
        RunningNow.RUNNING -> if (row.key == PLAYER && loading) "Loading" else RUNNING_WORDS[row.key] ?: "Running"
        RunningNow.WAITING -> WAITING_WORDS[row.key] ?: "Waiting"
        RunningNow.PROBLEM -> "Problem"
        RunningNow.OFF -> "Off"
        else -> if (row.key == PLAYER) "Stopped" else "Idle"
    }

    // ---- Today ---------------------------------------------------------------------------------------------

    /** One series of the day's chart, broken where a value is missing or the samples stop for a while: runs of (at, value). */
    fun runs(samples: List<SystemSample>, value: (SystemSample) -> Double?, gapMs: Long = GAP_MS): List<List<Pair<Long, Double>>> {
        val out = mutableListOf<List<Pair<Long, Double>>>()
        var run = mutableListOf<Pair<Long, Double>>()
        var last: Long? = null
        for (sample in samples) {
            val v = value(sample)
            if (v == null || (last != null && sample.at - last > gapMs)) {
                if (run.isNotEmpty()) out += run
                run = mutableListOf()
            }
            if (v != null) run += sample.at to v
            last = sample.at
        }
        if (run.isNotEmpty()) out += run
        return out
    }

    /** "Last 24 hours", or "Since 14:05" when the app has run for less than a day. */
    fun dayLine(samples: List<SystemSample>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val first = samples.firstOrNull()?.at ?: return LAST_DAY
        return if (now - first < DAY_MS - HALF_HOUR_MS) "Since ${clock(first, zone)}" else LAST_DAY
    }

    /** The day in one sentence, for everyone and for TalkBack: "Battery between 60 and 100 %, tablet between 27 and 35 °C". */
    fun daySentence(samples: List<SystemSample>): String {
        fun span(value: (SystemSample) -> Double?): Pair<Int, Int>? {
            val values = samples.mapNotNull(value)
            if (values.isEmpty()) return null
            return values.min().roundToInt() to values.max().roundToInt()
        }
        fun say(name: String, range: Pair<Int, Int>?, unit: String): String? = range?.let { (low, high) ->
            if (low == high) "$name at $low $unit" else "$name between $low and $high $unit"
        }
        val parts = listOfNotNull(
            say("battery", span { it.batteryPct?.toDouble() }, "%"),
            say("tablet", span { it.batteryTenthsC?.div(TENTHS) }, "°C"),
            say("piano", span { it.pianoTenthsC?.div(TENTHS) }, "°C"),
        )
        if (parts.isEmpty()) return NO_READINGS
        return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    // ---- Small helpers -------------------------------------------------------------------------------------

    /** "3 days 4 h", "2 h 5 min", "5 min", "40 s". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val days = s / DAY_S
        val hours = s % DAY_S / HOUR_S
        val minutes = s % HOUR_S / MINUTE_S
        return when {
            days > 0 -> "$days ${if (days == 1L) "day" else "days"} $hours h"
            hours > 0 -> "$hours h $minutes min"
            minutes > 0 -> "$minutes min"
            else -> "$s s"
        }
    }

    /** Gigabytes as the cards say them: "2.4", "4", "24". */
    fun gb(bytes: Long): String {
        val v = bytes / BYTES_PER_GB
        return if (v >= TEN) v.roundToLong().toString() else "%.1f".format(Locale.ROOT, v).removeSuffix(".0")
    }

    /** Kilobytes: "13.9", "212". */
    fun kb(bytes: Long): String {
        val v = bytes / BYTES_PER_KB
        return if (v >= HUNDRED) v.roundToLong().toString() else "%.1f".format(Locale.ROOT, v).removeSuffix(".0")
    }

    /** A whole number with a true minus sign: "−61". */
    fun signed(n: Long): String = if (n < 0) "−${-n}" else "$n"

    /** "14:05" in [zone]. */
    fun clock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        return "%02d:%02d".format(Locale.ROOT, at.hour, at.minute)
    }

    private fun count(n: Long): String = "%,d".format(Locale.getDefault(), n)

    private fun capital(word: String): String = word.replaceFirstChar { it.uppercase() }

    private fun joined(vararg parts: String?): String? = parts.filterNotNull().joinToString(" · ").ifEmpty { null }

    private const val PLAYER = "player"
    private const val FULL = 100
    private const val PERCENT = 100f
    private const val TABLET_HOT_END = 60.0
    private const val CHIP_HOT_END = 80.0
    private const val KEYS_PER_BOARD = 12
    private const val RAN_LOW = "Ran low"
    private const val TEN = 10.0
    private const val HUNDRED = 100.0
    private const val TENTHS = 10.0
    private const val BYTES_PER_KB = 1024.0
    private const val BYTES_PER_MB = 1_048_576.0
    private const val BYTES_PER_GB = 1e9
    private const val KBPS_PER_MBPS = 1000.0
    private const val MINUTE_S = 60L
    private const val HOUR_S = 3_600L
    private const val DAY_S = 86_400L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val HALF_HOUR_MS = 30 * 60 * 1000L

    /** The day's lines break where the samples stop for three minutes. */
    private const val GAP_MS = 3 * 60_000L

    /** The rows whose waiting needs attention: the covers waiting out Apple's stop, the internet link reconnecting. */
    private val WAITING_NEEDS_ATTENTION = setOf("covers", "relay")

    private val RUNNING_WORDS = mapOf(
        "player" to "Playing", "link" to "Connected", "web" to "Serving", "relay" to "Online", "covers" to "Fetching", "import" to "Importing",
        "studio" to "Working", "pack" to "Loading", "update" to "Working", "firmware" to "Updating", "sound" to "On",
    )
    private val WAITING_WORDS = mapOf("player" to "Paused", "link" to "Connecting", "relay" to "Reconnecting")

    /** The controller's words (firmware/docs/BLE_SETTINGS.md › 4, BLE_DIAG.md). */
    private val OTA_WORDS = mapOf("none" to "Flashed over USB", "pending" to "Waiting to confirm", "confirmed" to "Confirmed")
    private val RESET_WORDS = mapOf(
        "poweron" to "Power on", "software" to "Restarted", "panic" to "After a crash", "watchdog" to "Watchdog", "brownout" to "Brownout",
        "usb" to "USB", "other" to "Other",
    )
    private val TRANSPORTS = mapOf("wifi" to "Wi-Fi", "ethernet" to "Ethernet", "cellular" to "Mobile data", "vpn" to "VPN", "other" to "Connected")

    /** The facts the cards show by name (and the protocol's own markers); every other fact the piano gives is a row of its own. */
    private val SHOWN_FACTS = setOf(
        "temp", "heap", "heapmin", "heapblock", "heapsize", "tasks", "stack", "looprate", "loopms", "rssi", "active", "trips", "crashes",
        "i2cfails", "uptime", "repeatms", "reset", "ota", "fw", "boards", "pedalboard", "proto", "ble",
    )
}
