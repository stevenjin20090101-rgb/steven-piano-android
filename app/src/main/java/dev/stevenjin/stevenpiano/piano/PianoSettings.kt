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

/** The piano's own settings on the Piano tab, in four sections, in this order. */
enum class PianoSection(val title: String) {
    Lighting("Lighting"),
    Feel("Feel"),
    Pedal("Pedal"),
    Diagnostics("Diagnostics"),
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
 * `dump`, so a change goes back as `name value`. [unit] is shown in the control's eyebrow.
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

/** A read-only fact from the dump's "!" lines. */
@Immutable
data class Fact(val name: String, val label: String)

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
 * The static table of the piano's settings the app shows, in section order (BUILD_SPEC.md ›
 * Piano settings over Bluetooth › The table). Every name is a firmware command; the unit test
 * checks each against BLE_SETTINGS.md's `dump` list. Not shown: `keyviz` (the piano's own
 * screen) and everything refused over Bluetooth.
 */
object PianoSettings {
    private val L = PianoSection.Lighting
    private val F = PianoSection.Feel
    private val P = PianoSection.Pedal
    private val D = PianoSection.Diagnostics

    val all: List<PianoSetting> = listOf(
        // LIGHTING
        PianoSetting("leds", "Strip", L, SettingKind.Switch),
        PianoSetting("ledmode", "Mode", L, SettingKind.Choice(listOf("Off", "Static", "Rainbow", "Reactive"))),
        PianoSetting("ledbright", "Brightness", L, SettingKind.Slider(0f, 255f), unit = "%", asPercentOf255 = true),
        PianoSetting(
            "reactcolor", "Reactive palette", L,
            SettingKind.Choice(listOf("Rainbow", "Solid", "Velocity", "Fire", "Ocean", "Forest", "Lava", "Party")),
        ),
        PianoSetting(
            "ledcount", "Strip length", L, SettingKind.Stepper(1, 300), unit = "LEDs",
            note = "A new length maps notes at once; the strip itself follows after the piano restarts.",
        ),
        PianoSetting("ledoffset", "Offset", L, SettingKind.Stepper(-300, 300), unit = "LEDs"),
        PianoSetting("ledscale", "Scale", L, SettingKind.Stepper(10, 400), unit = "%"),
        PianoSetting("ledtail", "Unlit at the end", L, SettingKind.Stepper(0, 255), unit = "LEDs"),
        PianoSetting("ledreverse", "Strip runs high to low", L, SettingKind.Switch),
        PianoSetting("ledglow", "Glow", L, SettingKind.Stepper(0, 10), unit = "LEDs each side"),
        PianoSetting("velbright", "Brightness follows velocity", L, SettingKind.Switch),
        PianoSetting("decay", "Fade speed", L, SettingKind.Stepper(1, 40)),
        PianoSetting("rainspeed", "Rainbow speed", L, SettingKind.Stepper(1, 40)),
        PianoSetting("dimsecs", "Piano screen dims after", L, SettingKind.Stepper(0, 3600, step = 30), unit = "s", zero = "Never"),
        PianoSetting("dimfloor", "Dimmed screen brightness", L, SettingKind.Slider(0f, 255f)),
        // FEEL (the preset chips come first)
        PianoSetting("fullpower", "Full power (no dynamics)", F, SettingKind.Switch),
        PianoSetting("volume", "Volume", F, SettingKind.Slider(0f, 100f), unit = "%"),
        PianoSetting("velcurve", "Velocity curve", F, SettingKind.Slider(0.4f, 3.0f, step = 0.05f, decimals = 2)),
        PianoSetting("velmult", "Velocity multiplier", F, SettingKind.Slider(0.1f, 5.0f, step = 0.1f, decimals = 2)),
        PianoSetting("min", "White-key floor", F, SettingKind.Slider(0f, 4095f)),
        PianoSetting("minblack", "Black-key floor (0 = same as white)", F, SettingKind.Slider(0f, 4095f)),
        PianoSetting("max", "Ceiling", F, SettingKind.Slider(0f, 4095f)),
        PianoSetting("humanvel", "Velocity scatter", F, SettingKind.Stepper(0, 30)),
        PianoSetting("humantime", "Timing scatter", F, SettingKind.Stepper(0, 40), unit = "ms"),
        PianoSetting("burstgap", "Burst window", F, SettingKind.Stepper(0, 600, step = 10), unit = "ms"),
        PianoSetting("burstboost", "Burst boost", F, SettingKind.Slider(0f, 100f), unit = "%"),
        PianoSetting("minstrike", "Shortest strike", F, SettingKind.Stepper(0, 500, step = 5), unit = "ms"),
        PianoSetting("isostrike", "Lone-note strike", F, SettingKind.Stepper(0, 500, step = 5), unit = "ms"),
        PianoSetting("isogap", "Silence before a lone note", F, SettingKind.Stepper(0, 2000, step = 10), unit = "ms"),
        PianoSetting("gap", "Repeat gap", F, SettingKind.Stepper(0, 300), unit = "ms"),
        PianoSetting("hold", "Longest hold", F, SettingKind.Stepper(50, 4000, step = 50), unit = "ms"),
        PianoSetting("restrike", "Re-strike held notes", F, SettingKind.Stepper(0, 1000, step = 10, lowestOn = 40), unit = "ms", zero = "Off"),
        PianoSetting("softrelease", "Soft release", F, SettingKind.Switch),
        PianoSetting("releasepwm", "Release cushion", F, SettingKind.Slider(0f, 4095f)),
        PianoSetting("releasems", "Release time", F, SettingKind.Stepper(0, 200), unit = "ms"),
        PianoSetting("freq", "Drive frequency", F, SettingKind.Stepper(24, 1526, step = 10), unit = "Hz"),
        // PEDAL (pedaltest is refused over Bluetooth, so not here)
        PianoSetting("pedalon", "Sustain pedal", P, SettingKind.Switch),
        PianoSetting("pedalhalf", "Half-pedalling", P, SettingKind.Switch),
        PianoSetting("pedalup", "Up position", P, SettingKind.Stepper(80, 600)),
        PianoSetting("pedaldown", "Down position", P, SettingKind.Stepper(80, 600)),
        // DIAGNOSTICS: per-key force is set at the piano's USB console only
        PianoSetting("keyforce_white", "White-key force", D, SettingKind.Slider(0f, 4f, step = 0.01f, decimals = 2), readOnly = true, times = true),
        PianoSetting("keyforce_black", "Black-key force", D, SettingKind.Slider(0f, 4f, step = 0.01f, decimals = 2), readOnly = true, times = true),
    )

    private val byName: Map<String, PianoSetting> = all.associateBy { it.name }

    fun named(name: String): PianoSetting? = byName[name]

    fun inSection(section: PianoSection): List<PianoSetting> = all.filter { it.section == section }

    /** The four feel presets, as a chip row at the top of FEEL. Each saves itself on the piano. */
    val presets: List<Preset> = listOf(
        Preset("soft", "Soft"),
        Preset("cinematic", "Cinematic"),
        Preset("expressive", "Expressive"),
        Preset("snappy", "Snappy"),
    )

    /** Diagnostics' read-only facts, in the order shown. */
    val facts: List<Fact> = listOf(
        Fact("fw", "Firmware"),
        Fact("boards", "Power boards"),
        Fact("i2cfails", "I²C errors"),
        Fact("pedalboard", "Pedal board"),
        Fact("uptime", "Uptime"),
    )

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
