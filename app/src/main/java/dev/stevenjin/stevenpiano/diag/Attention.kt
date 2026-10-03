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
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import kotlin.math.roundToInt

/**
 * What needs attention on the tablet's System page (v1.18 — M50; DESIGN.md › v1.18 — M47b › Needs attention): one rule,
 * the web panel's `attention()` (`assets/web/system.js`) with its thresholds and its words, most pressing first. The page
 * colours a part with the attention amber only where an item names it, always beside these words, and the hub's System
 * row says the first. Pure, so it is tested on the JVM.
 */
object Attention {
    /** What an item colours on the page: a dial, a meter, the boards, a running row. */
    enum class Part { Battery, Heat, Memory, Storage, Boards, Chip, Heap, Relay, Running }

    /** One thing that needs attention: the [part] it colours, the sentence that says it ([text]), the running row's [key]. */
    data class Item(val part: Part, val text: String, val key: String? = null)

    /**
     * Everything the rule reads: the tablet's [reading] (null before the first), what [running] lists, the piano's facts
     * ([piano]: null unless Steven Piano is connected and has answered, [PianoDiag.of]), whether the web panel is on the
     * internet ([cloudOn]: remote access on and the tablet enrolled, the panel's `web.cloud`) and how its link stands ([cloud]).
     */
    data class Inputs(
        val reading: SystemReading? = null,
        val running: List<RunningNow.Activity> = emptyList(),
        val piano: PianoDiag? = null,
        val cloudOn: Boolean = false,
        val cloud: CloudStatus = CloudStatus.Off,
    )

    /** Where attention starts (system.js's constants). */
    const val BATTERY_LOW_PCT = 20
    const val BATTERY_HOT_C = 42.0
    const val STORAGE_LOW_BYTES = 1_000_000_000L
    const val CHIP_HOT_C = 70.0
    const val HEAP_LOW_BYTES = 30 * 1024L

    /** A battery's health that needs attention, and its word; "unknown" is a tablet that doesn't say, not a fault. */
    val BAD_HEALTH: Map<String, String> = mapOf("overheat" to "Overheating", "cold" to "Too cold", "dead" to "Failed", "overVoltage" to "Over voltage")

    /** The tablet's heat words. */
    const val NORMAL = "Normal"
    const val WARM = "Warm"
    const val HOT = "Hot"

    /** Android's thermal status (`SystemReading.THERMAL_WORDS`, 0–6) as a word: none Normal, light and moderate Warm, severe and above Hot. */
    private val HEAT_WORDS = listOf(NORMAL, WARM, WARM, HOT, HOT, HOT, HOT)

    /** Android's `THERMAL_STATUS_MODERATE`: from there the tablet needs attention. */
    private const val MODERATE = 2

    /** The covers' running row, which needs attention while it waits out Apple's stop. */
    private const val COVERS = "covers"

    /** Everything that needs attention now, in the panel's order: the tablet, the piano, the internet link, what runs. */
    fun of(inputs: Inputs): List<Item> {
        val items = mutableListOf<Item>()
        inputs.reading?.let { reading ->
            val battery = reading.battery
            val percent = battery.percent
            if (percent != null && percent < BATTERY_LOW_PCT && battery.charging != true) {
                items += Item(Part.Battery, "The tablet's battery is low · $percent%")
            }
            BAD_HEALTH[battery.health]?.let { items += Item(Part.Battery, "The tablet's battery: ${it.lowercase()}") }
            val status = reading.thermal.status
            val heat = heatWord(status, battery.tempC)
            if (heat == HOT || (status != null && status >= MODERATE)) {
                items += Item(Part.Heat, if (heat == HOT) "The tablet is hot" else "The tablet is warm")
            }
            if (reading.memory.low == true) items += Item(Part.Memory, "The tablet is low on memory")
            val free = reading.storage.free
            if (free != null && free < STORAGE_LOW_BYTES) items += Item(Part.Storage, "The tablet's storage is almost full")
        }
        inputs.piano?.let { piano ->
            val missing = piano.boards?.count { it == PianoDiag.MISSING } ?: 0
            if (missing > 0) items += Item(Part.Boards, if (missing == 1) "A power board is missing" else "$missing power boards are missing")
            piano.temp?.takeIf { it >= CHIP_HOT_C }?.let { items += Item(Part.Chip, "The controller is hot · ${it.roundToInt()} °C") }
            piano.heapMin?.takeIf { it < HEAP_LOW_BYTES }?.let { items += Item(Part.Heap, "The controller ran low on memory") }
        }
        if (inputs.cloudOn && inputs.cloud !is CloudStatus.Connected) items += Item(Part.Relay, "The internet link is not connected")
        for (row in inputs.running) {
            if (row.state == RunningNow.PROBLEM) items += Item(Part.Running, "${row.title} needs attention", row.key)
        }
        // Apple's hour-long stop: its row's dot is amber, so the head says it too, last of all (as the panel does).
        for (row in inputs.running) {
            if (row.key == COVERS && row.state == RunningNow.WAITING) items += Item(Part.Running, "Album covers are waiting", row.key)
        }
        return items
    }

    /**
     * The tablet's heat in a word: [HOT] from 42 °C on the battery ([celsius]), else Android's [status] in a word; "Normal"
     * with a temperature and no status, "" with neither.
     */
    fun heatWord(status: Int?, celsius: Double?): String = when {
        celsius != null && celsius >= BATTERY_HOT_C -> HOT
        status != null && status in HEAT_WORDS.indices -> HEAT_WORDS[status]
        celsius == null -> ""
        else -> NORMAL
    }
}

/**
 * The piano's facts as the System pages read them (`firmware/docs/BLE_DIAG.md`; the panel's are `WebApi.pianoDiag`'s):
 * `temp` and `loopms` decimals, the counts and bytes whole numbers, `reset` `ota` `fw` `pedalboard` words, `boards` a list
 * of `ok` / `missing` (a power board each, C1 first); each null when the facts lack it or it doesn't read. [facts] keeps
 * them as they came, for the facts the page has no row of its own for.
 */
data class PianoDiag(val facts: Map<String, String>) {
    val temp: Double? = decimal("temp")
    val heap: Long? = whole("heap")
    val heapMin: Long? = whole("heapmin")

    /** The heap's whole size (`!heapsize`): with it the memory dial is a share, without it the free figure alone. */
    val heapSize: Long? = whole("heapsize")
    val tasks: Long? = whole("tasks")
    val stack: Long? = whole("stack")
    val loopRate: Long? = whole("looprate")
    val loopMs: Double? = decimal("loopms")
    val rssi: Long? = whole("rssi")
    val active: Long? = whole("active")
    val trips: Long? = whole("trips")
    val crashes: Long? = whole("crashes")
    val i2cFails: Long? = whole("i2cfails")

    /** Seconds since the controller started. */
    val uptime: Long? = whole("uptime")
    val repeatMs: Long? = whole("repeatms")
    val reset: String? = word("reset")
    val ota: String? = word("ota")

    /** The firmware's version without its build ("2.1.0" of "2.1.0+a1b2c3d"). */
    val fw: String? = word("fw")?.substringBefore('+')?.trim()?.takeIf { it.isNotEmpty() }
    val pedalBoard: String? = word("pedalboard")
    val boards: List<String>? = text("boards")?.split(',')?.map { it.trim().lowercase() }
        ?.takeIf { list -> list.all { board -> board.isNotEmpty() && board.all(Char::isLetter) } }

    private fun text(name: String): String? = facts[name]?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_FACT }

    private fun decimal(name: String): Double? = text(name)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun whole(name: String): Long? = text(name)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(Math::round) }

    private fun word(name: String): String? = text(name)?.takeIf { word -> word.all { it in ' '..'~' } }

    companion object {
        /** A power board's state in `boards`. */
        const val OK = "ok"
        const val MISSING = "missing"

        /** A fact longer than this is no reading (as the panel's). */
        private const val MAX_FACT = 128

        /** The facts of the instrument when it is Steven Piano, connected and answering ([piano] ready); null otherwise. */
        fun of(kind: InstrumentKind, link: LinkState, piano: PianoState): PianoDiag? =
            if (kind == InstrumentKind.StevenPiano && link is LinkState.Connected && piano is PianoState.Ready) PianoDiag(piano.facts) else null
    }
}
