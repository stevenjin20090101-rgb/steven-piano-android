// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Piano tab's four piano pages (DESIGN.md › v1.5), in the hub's order: each opens from a row of
 * the hub's PIANO group and shows its sections of the table below.
 */
enum class PianoPage { Feel, Lighting, Pedal, Firmware }

/**
 * The sections of the piano pages, in page order and then in the order each page shows them, each
 * under its eyebrow ([title]; null for a page with one section, which needs none). Feel: PRESETS ·
 * LOUDNESS · TOUCH · TIMING · RELEASE · DRIVE; Lighting: STRIP · LAYOUT · MOTION · PIANO'S SCREEN;
 * Pedal: one section; Firmware and status: FIRMWARE · STATUS · ACTIONS.
 */
enum class PianoSection(val page: PianoPage, val title: String?) {
    Presets(PianoPage.Feel, "Presets"),
    Loudness(PianoPage.Feel, "Loudness"),
    Touch(PianoPage.Feel, "Touch"),
    Timing(PianoPage.Feel, "Timing"),
    Release(PianoPage.Feel, "Release"),
    Drive(PianoPage.Feel, "Drive"),
    Strip(PianoPage.Lighting, "Strip"),
    Layout(PianoPage.Lighting, "Layout"),
    Motion(PianoPage.Lighting, "Motion"),
    PianoScreen(PianoPage.Lighting, "Piano's screen"),
    Pedal(PianoPage.Pedal, null),
    Firmware(PianoPage.Firmware, "Firmware"),
    Status(PianoPage.Firmware, "Status"),
    Actions(PianoPage.Firmware, "Actions"),
}

/** How a setting is adjusted. Ranges are the firmware's (`firmware/docs/BLE_SETTINGS.md` › 4). */
@Immutable
sealed interface SettingKind {
    /** On or off; "1" or "0" on the wire. */
    data object Switch : SettingKind

    /**
     * Whole numbers in [min]..[max], a [step] at a time. With [lowestOn], 0 means off and the other
     * values start at [lowestOn] (restrike: off, or 40..1000 ms).
     */
    data class Stepper(val min: Int, val max: Int, val step: Int = 1, val lowestOn: Int? = null) : SettingKind {
        /** The value one step up or down from [value], kept in range and off the gap below [lowestOn]. */
        fun next(value: Int, up: Boolean): Int {
            var next = (value + if (up) step else -step).coerceIn(min, max)
            val on = lowestOn ?: return next
            if (next in 1 until on) next = if (up || value > on) on else 0
            return next
        }
    }

    /** A value in [min]..[max] snapped to [step], shown with [decimals] places. */
    data class Slider(val min: Float, val max: Float, val step: Float = 1f, val decimals: Int = 0) : SettingKind {
        /** [value] snapped to the nearest step and kept in range. */
        fun snap(value: Float): Float = (min + ((value - min) / step).roundToInt() * step).coerceIn(min, max)
    }

    /** One of [options]; the wire value is the option's index. */
    data class Choice(val options: List<String>) : SettingKind
}

/**
 * One of the piano's settings. [name] is the firmware command that sets it and its name in
 * `dump`, so a change goes back as `name value`. It sits in one [section] of one [page] of the Piano
 * tab. [unit] is shown in the control's eyebrow.
 * [readOnly] settings are shown but never sent. [zero] is how 0 reads where it means something
 * ("Off", "Never"); [asPercentOf255] shows a 0..255 value as a percentage, as the firmware's own
 * reply does; [times] shows a multiplier as "×1.00". [note] is a line of help under the control.
 */
@Immutable
data class PianoSetting(
    val name: String,
    val label: String,
    val section: PianoSection,
    val kind: SettingKind,
    val unit: String = "",
    val readOnly: Boolean = false,
    val zero: String? = null,
    val asPercentOf255: Boolean = false,
    val times: Boolean = false,
    val note: String? = null,
) {
    /** The page the setting is on: its section's. */
    val page: PianoPage get() = section.page

    /** How [wire] (the piano's value) reads on screen: "63", "1.60", "On", "Rainbow", "Off"; "—" when unknown. */
    fun display(wire: String?): String {
        if (wire == null) return NO_VALUE
        return when (kind) {
            SettingKind.Switch -> if (wire.trim() != "0") "On" else "Off"
            is SettingKind.Choice -> wire.toIntOrNull()?.let(kind.options::getOrNull) ?: wire
            is SettingKind.Stepper -> wire.toIntOrNull()?.let { if (it == 0 && zero != null) zero else signed(it) } ?: wire
            is SettingKind.Slider -> {
                val v = wire.toFloatOrNull() ?: return wire
                when {
                    asPercentOf255 -> "${(v.roundToInt() * 100) / 255}"   // the firmware's own (v * 100) / 255
                    times -> "×" + "%.2f".format(Locale.ROOT, v)
                    v == 0f && zero != null -> zero
                    kind.decimals > 0 -> "%.${kind.decimals}f".format(Locale.ROOT, v)
                    else -> signed(v.roundToInt())
                }
            }
        }
    }

    /** The value and its unit, spoken: "63 percent", "60 milliseconds", "1.00 times", "Off"; "not known" when unknown. */
    fun spoken(wire: String?): String {
        if (wire == null) return "not known"
        val shown = display(wire)
        if (times) return shown.removePrefix("×") + " times"
        if (unit.isEmpty() || shown == zero || kind is SettingKind.Switch || kind is SettingKind.Choice) return shown
        return "$shown ${SPOKEN_UNITS[unit] ?: unit}"
    }

    private companion object {
        const val NO_VALUE = "—"
        val SPOKEN_UNITS = mapOf("%" to "percent", "ms" to "milliseconds", "s" to "seconds", "Hz" to "hertz")

        /** Negative numbers with a true minus sign, as elsewhere in the app. */
        fun signed(n: Int): String = if (n < 0) "−${-n}" else n.toString()
    }
}

/** A feel preset: the firmware command that applies it (and saves it on the piano), and its chip. */
@Immutable
data class Preset(val command: String, val label: String)

/** A read-only fact from the dump's "!" lines, shown in [section]. */
@Immutable
data class Fact(val name: String, val label: String, val section: PianoSection)

/**
 * One row of a piano page, in the order [PianoSettings.rows] gives for its section: the settings
 * from the table, the facts, and the rows that are not settings.
 */
@Immutable
sealed interface PianoRow {
    /** A setting from [PianoSettings.all], in the control its kind takes. */
    data class Control(val setting: PianoSetting) : PianoRow

    /** A fact the piano reports, read only. */
    data class Reading(val fact: Fact) : PianoRow

    /** PRESETS: the four feel presets as chips. */
    data object Presets : PianoRow

    /** TOUCH, after the floors and the ceiling: one key struck at its floor or at the ceiling. */
    data object StrikeTest : PianoRow

    /** LAYOUT, after the strip's geometry: one key's LED lit, to line the strip up. */
    data object TestLed : PianoRow

    /** STATUS, under the two key-force lines: where key force is set. */
    data object KeyForceNote : PianoRow

    /** ACTIONS: Read status (and the piano's report), All keys off, Save now. */
    data object Actions : PianoRow
}

/** What the Piano tab may ask the piano to do besides settings. [takesKey]: followed by a MIDI key. */
enum class PianoAction(val command: String, val takesKey: Boolean = false) {
    AllKeysOff("off"),
    Save("save"),
    LedTest("ledtest", takesKey = true),
    TestMin("testmin", takesKey = true),
    TestMax("testmax", takesKey = true),
    Status("status"),
}

/**
 * The static table of the piano's settings the app shows, in page and section order (BUILD_SPEC.md
 * › v1.5 — M15 › The table). Every name is a firmware command; the unit test checks each against
 * BLE_SETTINGS.md's `dump` list. Not shown: `keyviz` (the piano's own screen) and everything
 * refused over Bluetooth.
 */
object PianoSettings {
    private val LOUDNESS = PianoSection.Loudness
    private val TOUCH = PianoSection.Touch
    private val TIMING = PianoSection.Timing
    private val RELEASE = PianoSection.Release
    private val DRIVE = PianoSection.Drive
    private val STRIP = PianoSection.Strip
    private val LAYOUT = PianoSection.Layout
    private val MOTION = PianoSection.Motion
    private val SCREEN = PianoSection.PianoScreen
    private val PEDAL = PianoSection.Pedal
    private val STATUS = PianoSection.Status

    val all: List<PianoSetting> = listOf(
        // FEEL (PRESETS, the chips, comes first and holds no setting)
        PianoSetting("fullpower", "Full power (no dynamics)", LOUDNESS, SettingKind.Switch),
        PianoSetting("volume", "Volume", LOUDNESS, SettingKind.Slider(0f, 100f), unit = "%"),
        PianoSetting("velcurve", "Velocity curve", TOUCH, SettingKind.Slider(0.4f, 3.0f, step = 0.05f, decimals = 2)),
        PianoSetting("velmult", "Velocity multiplier", TOUCH, SettingKind.Slider(0.1f, 5.0f, step = 0.1f, decimals = 2)),
        PianoSetting("min", "White-key floor", TOUCH, SettingKind.Slider(0f, 4095f)),
        PianoSetting("minblack", "Black-key floor (0 = same as white)", TOUCH, SettingKind.Slider(0f, 4095f)),
        PianoSetting("max", "Ceiling", TOUCH, SettingKind.Slider(0f, 4095f)),
        PianoSetting("humanvel", "Velocity scatter", TIMING, SettingKind.Stepper(0, 30)),
        PianoSetting("humantime", "Timing scatter", TIMING, SettingKind.Stepper(0, 40), unit = "ms"),
        PianoSetting("burstgap", "Burst window", TIMING, SettingKind.Stepper(0, 600, step = 10), unit = "ms"),
        PianoSetting("burstboost", "Burst boost", TIMING, SettingKind.Slider(0f, 100f), unit = "%"),
        PianoSetting("minstrike", "Shortest strike", TIMING, SettingKind.Stepper(0, 500, step = 5), unit = "ms"),
        PianoSetting("isostrike", "Lone-note strike", TIMING, SettingKind.Stepper(0, 500, step = 5), unit = "ms"),
        PianoSetting("isogap", "Silence before a lone note", TIMING, SettingKind.Stepper(0, 2000, step = 10), unit = "ms"),
        PianoSetting("gap", "Repeat gap", TIMING, SettingKind.Stepper(0, 300), unit = "ms"),
        PianoSetting("hold", "Longest hold", TIMING, SettingKind.Stepper(50, 4000, step = 50), unit = "ms"),
        PianoSetting("restrike", "Re-strike held notes", TIMING, SettingKind.Stepper(0, 1000, step = 10, lowestOn = 40), unit = "ms", zero = "Off"),
        PianoSetting("softrelease", "Soft release", RELEASE, SettingKind.Switch),
        PianoSetting("releasepwm", "Release cushion", RELEASE, SettingKind.Slider(0f, 4095f)),
        PianoSetting("releasems", "Release time", RELEASE, SettingKind.Stepper(0, 200), unit = "ms"),
        PianoSetting("freq", "Drive frequency", DRIVE, SettingKind.Stepper(24, 1526, step = 10), unit = "Hz"),
        // LIGHTING
        PianoSetting("leds", "Strip", STRIP, SettingKind.Switch),
        PianoSetting("ledmode", "Mode", STRIP, SettingKind.Choice(listOf("Off", "Static", "Rainbow", "Reactive"))),
        PianoSetting("ledbright", "Brightness", STRIP, SettingKind.Slider(0f, 255f), unit = "%", asPercentOf255 = true),
        PianoSetting(
            "reactcolor", "Reactive palette", STRIP,
            SettingKind.Choice(listOf("Rainbow", "Solid", "Velocity", "Fire", "Ocean", "Forest", "Lava", "Party")),
        ),
        PianoSetting(
            "ledcount", "Strip length", LAYOUT, SettingKind.Stepper(1, 300), unit = "LEDs",
            note = "A new length maps notes at once; the strip itself follows after the piano restarts.",
        ),
        PianoSetting("ledoffset", "Offset", LAYOUT, SettingKind.Stepper(-300, 300), unit = "LEDs"),
        PianoSetting("ledscale", "Scale", LAYOUT, SettingKind.Stepper(10, 400), unit = "%"),
        PianoSetting("ledtail", "Unlit at the end", LAYOUT, SettingKind.Stepper(0, 255), unit = "LEDs"),
        PianoSetting("ledreverse", "Strip runs high to low", LAYOUT, SettingKind.Switch),
        PianoSetting("ledglow", "Glow", LAYOUT, SettingKind.Stepper(0, 10), unit = "LEDs each side"),
        PianoSetting("velbright", "Brightness follows velocity", MOTION, SettingKind.Switch),
        PianoSetting("decay", "Fade speed", MOTION, SettingKind.Stepper(1, 40)),
        PianoSetting("rainspeed", "Rainbow speed", MOTION, SettingKind.Stepper(1, 40)),
        PianoSetting("dimsecs", "Piano screen dims after", SCREEN, SettingKind.Stepper(0, 3600, step = 30), unit = "s", zero = "Never"),
        PianoSetting("dimfloor", "Dimmed screen brightness", SCREEN, SettingKind.Slider(0f, 255f)),
        // PEDAL (pedaltest is refused over Bluetooth, so not here)
        PianoSetting("pedalon", "Sustain pedal", PEDAL, SettingKind.Switch),
        PianoSetting("pedalhalf", "Half-pedalling", PEDAL, SettingKind.Switch),
        PianoSetting("pedalup", "Up position", PEDAL, SettingKind.Stepper(80, 600)),
        PianoSetting("pedaldown", "Down position", PEDAL, SettingKind.Stepper(80, 600)),
        // FIRMWARE AND STATUS › STATUS: per-key force is set at the piano's USB console only
        PianoSetting("keyforce_white", "White-key force", STATUS, SettingKind.Slider(0f, 4f, step = 0.01f, decimals = 2), readOnly = true, times = true),
        PianoSetting("keyforce_black", "Black-key force", STATUS, SettingKind.Slider(0f, 4f, step = 0.01f, decimals = 2), readOnly = true, times = true),
    )

    private val byName: Map<String, PianoSetting> = all.associateBy { it.name }

    fun named(name: String): PianoSetting? = byName[name]

    fun inSection(section: PianoSection): List<PianoSetting> = all.filter { it.section == section }

    /** [page]'s sections, in the order the page shows them. */
    fun sections(page: PianoPage): List<PianoSection> = PianoSection.entries.filter { it.page == page }

    /** What [section] shows, in order: its facts, its settings, and the rows that are not settings where they belong. */
    fun rows(section: PianoSection): List<PianoRow> = rowsBySection.getValue(section)

    /** The four feel presets, as the chip row of Feel's PRESETS. Each saves itself on the piano. */
    val presets: List<Preset> = listOf(
        Preset("soft", "Soft"),
        Preset("cinematic", "Cinematic"),
        Preset("expressive", "Expressive"),
        Preset("snappy", "Snappy"),
    )

    /** The read-only facts, in the order shown: the version under FIRMWARE, the rest under STATUS. */
    val facts: List<Fact> = listOf(
        Fact("fw", "Piano firmware", PianoSection.Firmware),
        Fact("boards", "Power boards", STATUS),
        Fact("i2cfails", "I²C errors", STATUS),
        Fact("pedalboard", "Pedal board", STATUS),
        Fact("uptime", "Uptime", STATUS),
    )

    private val rowsBySection: Map<PianoSection, List<PianoRow>> = PianoSection.entries.associateWith { section ->
        buildList {
            if (section == PianoSection.Presets) add(PianoRow.Presets)
            facts.filter { it.section == section }.forEach { add(PianoRow.Reading(it)) }
            inSection(section).forEach { add(PianoRow.Control(it)) }
            when (section) {
                PianoSection.Touch -> add(PianoRow.StrikeTest)
                PianoSection.Layout -> add(PianoRow.TestLed)
                PianoSection.Status -> add(PianoRow.KeyForceNote)
                PianoSection.Actions -> add(PianoRow.Actions)
                else -> Unit
            }
        }
    }

    /** Facts that change while the piano runs: read again with the status. */
    val liveFacts: List<String> = listOf("boards", "i2cfails", "pedalboard", "uptime")

    /** Writing one of these changes others on the piano, so those are read back too (volume below 100 turns full power off). */
    val alsoRead: Map<String, List<String>> = mapOf("volume" to listOf("fullpower"))

    /** The keys the test actions take (the piano's 84), and where they start. */
    val testKeys: IntRange = 24..107
    const val TEST_KEY_DEFAULT = 60

    /** What the protocol fact must say for the app to use the console (BLE_SETTINGS.md › 9). */
    const val PROTOCOL = "1"

    /** How values travel: booleans as 0 or 1, decimals with two places. */
    fun wire(on: Boolean): String = if (on) "1" else "0"

    fun wire(value: Float): String = "%.2f".format(Locale.ROOT, value)

    fun wire(value: Int): String = value.toString()

    /** "0:02" for 123 s: the piano's uptime as hours and minutes. */
    fun uptime(seconds: Long): String = "%d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60)
}
