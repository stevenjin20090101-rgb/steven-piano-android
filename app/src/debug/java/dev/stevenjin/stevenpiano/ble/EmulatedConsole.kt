// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import java.util.Locale

/**
 * The piano's console as the firmware answers it over Bluetooth (`firmware/docs/BLE_SETTINGS.md`),
 * for the emulator's stand-in link and for tests: `dump` and `get`, every tunable with the
 * firmware's range and its reply, the four presets, the actions, `status`, and the refusals.
 * Plausible defaults (the firmware's own, lights on). A stand-in for the screen to talk to, not a
 * model of the instrument: replies are shortened, every out-of-range number says "out of range",
 * and nothing plays. Not thread-safe.
 */
class EmulatedConsole(facts: Map<String, String> = DEFAULT_FACTS) {
    private val facts = LinkedHashMap(facts)
    private val values = LinkedHashMap<String, String>().apply { TUNABLES.forEach { put(it.name, it.default) } }

    /** The piano's fact [name] (without its "!") now reads [value]: a firmware update's `!fw` and `!ota` (v1.6 — M21). */
    fun setFact(name: String, value: String) {
        facts[name] = value
    }

    /** What the piano prints back for one command line, without the "> command" echo. */
    fun handle(line: String): List<String> {
        val command = line.trim()
        if (command.isEmpty()) return emptyList()
        val name = command.substringBefore(' ')
        val argument = command.substringAfter(' ', "").trim()
        return when {
            name in REFUSED -> listOf("  refused over Bluetooth — use the USB console")
            command == "dump" -> dump()
            name == "get" -> listOf(read(argument) ?: "  unknown setting")
            command == "status" -> status()
            command == "help" -> listOf("  dump, get <name>, status, off, save, ledtest <midi>, soft, cinematic, expressive, snappy …")
            command == "off" -> listOf("  all keys released (per-board + ALLCALL)")
            command == "panic" -> {
                values["fullpower"] = "1"
                listOf("  PANIC: all solenoids released, Full Power forced ON")
            }
            command == "save" -> listOf(SAVED)
            command in PRESETS -> preset(command)
            name == "ledtest" -> ledTest(argument)
            name == "testmin" || name == "testmax" -> strikeTest(name == "testmax", argument)
            name == "note" -> emptyList()
            argument.isNotEmpty() && (name in values || name == "minwhite") -> set(if (name == "minwhite") "min" else name, argument)
            else -> listOf("  unknown command — type 'help'")
        }
    }

    /** The value [name] holds, as `dump` writes it; facts with their "!". */
    fun valueOf(name: String): String? = if (name.startsWith("!")) facts[name.removePrefix("!")] else values[name]

    private fun dump(): List<String> =
        facts.map { (name, value) -> "!$name=$value" } + values.map { (name, value) -> "$name=$value" } + "end"

    private fun read(name: String): String? = valueOf(name)?.let { "$name=$it" }

    private fun set(name: String, argument: String): List<String> {
        val tunable = TUNABLES.first { it.name == name }
        return when (val range = tunable.range) {
            Range.Switch -> {
                val on = (argument.toIntOrNull() ?: 0) != 0   // the firmware's atoi: anything else reads as 0
                values[name] = if (on) "1" else "0"
                listOf("  $name = ${if (on) "ON" else "OFF"}")
            }
            is Range.Whole -> {
                val v = argument.toIntOrNull() ?: 0
                val allowed = v in range.low..range.high || (range.zeroIsOff && v == 0)
                when {
                    range.clamps -> {
                        values[name] = v.coerceIn(range.low, range.high).toString()
                        listOf("  $name = ${values[name]} (clamped to ${range.low}..${range.high})")
                    }
                    !allowed -> listOf("  $name out of range (${if (range.zeroIsOff) "0 = off, or " else ""}${range.low}..${range.high})")
                    else -> {
                        values[name] = v.toString()
                        buildList {
                            if (name == "volume" && v < 100 && values["fullpower"] == "1") {
                                values["fullpower"] = "0"
                                add("[vol] Full Power turned OFF — required for soft playing (it forces every note to max force)")
                            }
                            add("  $name = $v")
                        }
                    }
                }
            }
            is Range.Decimal -> {
                val v = argument.toFloatOrNull() ?: 0f
                if (v < range.low || v > range.high) {
                    listOf("  $name out of range (${range.low}..${range.high})")
                } else {
                    values[name] = two(v)
                    listOf("  $name = ${two(v)}")
                }
            }
            Range.Refused -> listOf("  refused over Bluetooth — use the USB console")
        }
    }

    private fun preset(name: String): List<String> {
        val changes = when (name) {
            "soft" -> mapOf("fullpower" to "0", "volume" to "55", "softrelease" to "1")
            "cinematic" -> mapOf(
                "fullpower" to "0", "softrelease" to "1", "releasepwm" to "1100", "releasems" to "28",
                "minstrike" to "45", "gap" to "22", "restrike" to "0", "leds" to "1", "ledmode" to "3",
            )
            "expressive" -> mapOf(
                "fullpower" to "0", "min" to "2400", "max" to "4095", "velcurve" to "1.70", "humanvel" to "6",
                "humantime" to "12", "softrelease" to "1", "volume" to "100",
            )
            else -> mapOf("leds" to "0", "fullpower" to "1", "gap" to "0", "minstrike" to "20", "softrelease" to "0", "restrike" to "0")
        }
        values.putAll(changes)
        return listOf(SAVED, "  ${name.uppercase(Locale.ROOT)} preset applied + saved")
    }

    private fun ledTest(argument: String): List<String> {
        val note = argument.toIntOrNull() ?: 0
        if (note !in 0..127) return listOf("  usage: ledtest <midi 0..127>")
        return listOf("  ledtest: note $note (${noteName(note)}) → LED ${(note - 24).coerceAtLeast(0)} lit white for 4 s — align it under the key")
    }

    private fun strikeTest(max: Boolean, argument: String): List<String> {
        val note = if (argument.isEmpty()) 60 else argument.toIntOrNull() ?: 0
        if (note !in 0..127) return listOf("  usage: testmin|testmax [midi]")
        val pwm = if (max) values["max"] else values["min"]
        return listOf("  ${if (max) "MAX" else "MIN"} on ${noteName(note)} at pwm=$pwm")
    }

    private fun status(): List<String> {
        fun v(name: String) = values.getValue(name)
        fun onOff(name: String) = if (v(name) == "1") "ON" else "OFF"
        return listOf(
            "",
            "  PWM settings: min=${v("min")}  max=${v("max")}  hold=${v("hold")} ms  velmult=${v("velmult")}",
            "  volume=${v("volume")}%  fullpower=${onOff("fullpower")}  velcurve=${v("velcurve")}",
            "  freq=${v("freq")} Hz   gap=${v("gap")} ms   minstrike=${v("minstrike")} ms",
            "  softrelease=${onOff("softrelease")}  releasepwm=${v("releasepwm")}  releasems=${v("releasems")} ms",
            "  leds=${onOff("leds")}   led: bright=${v("ledbright")}/255 count=${v("ledcount")} offset=${v("ledoffset")}",
            "  pedal servo: ${if (v("pedalon") == "1") "ENABLED" else "disabled"}  board=${facts["pedalboard"]}",
            "  i2cFails=${facts["i2cfails"]} (rises during PWM-EMI storms; steady 0 is healthy)",
            "  boards:",
        ) + (facts["boards"] ?: "").split(',').mapIndexed { i, state -> "    [$i] (MIDI ${24 + 12 * i}-${35 + 12 * i}): $state" }
    }

    private sealed interface Range {
        data object Switch : Range

        data class Whole(val low: Int, val high: Int, val zeroIsOff: Boolean = false, val clamps: Boolean = false) : Range

        data class Decimal(val low: Float, val high: Float) : Range

        data object Refused : Range
    }

    private class Tunable(val name: String, val default: String, val range: Range)

    companion object {
        private const val SAVED = "[settings] saved to NVS"

        /** The read-only facts in `dump` order, as the emulator reports them. */
        val DEFAULT_FACTS: Map<String, String> = linkedMapOf(
            "proto" to "1",
            "fw" to "emulator",
            "ble" to "1",
            "boards" to "OK,OK,OK,OK,OK,OK,OK",
            "i2cfails" to "0",
            "pedalboard" to "absent",
            "uptime" to "123",
        )

        private val PRESETS = setOf("soft", "cinematic", "expressive", "snappy")

        private val REFUSED = setOf(
            "fire", "rawnote", "sweep", "pwm", "ramp", "reset", "blereset", "pedaltest",
            "keyforce", "keyforce_all", "keyforce_white", "keyforce_black", "waterfall",
        )

        /** Every tunable in `dump` order, with the firmware's default and range (BLE_SETTINGS.md › 4). */
        private val TUNABLES = listOf(
            Tunable("leds", "1", Range.Switch),
            Tunable("ledmode", "3", Range.Whole(0, 3)),
            Tunable("ledbright", "160", Range.Whole(0, 255)),
            Tunable("ledcount", "73", Range.Whole(1, 300)),
            Tunable("ledoffset", "0", Range.Whole(-300, 300)),
            Tunable("ledscale", "100", Range.Whole(10, 400)),
            Tunable("ledtail", "0", Range.Whole(0, 255)),
            Tunable("ledreverse", "0", Range.Switch),
            Tunable("reactcolor", "0", Range.Whole(0, 7)),
            Tunable("ledglow", "1", Range.Whole(0, 10)),
            Tunable("velbright", "0", Range.Switch),
            Tunable("decay", "6", Range.Whole(1, 40)),
            Tunable("rainspeed", "1", Range.Whole(1, 40)),
            Tunable("dimsecs", "60", Range.Whole(0, 3600)),
            Tunable("dimfloor", "0", Range.Whole(0, 255)),
            Tunable("keyviz", "1", Range.Switch),
            Tunable("fullpower", "1", Range.Switch),
            Tunable("volume", "100", Range.Whole(0, 100)),
            Tunable("velcurve", "1.60", Range.Decimal(0.4f, 3.0f)),
            Tunable("velmult", "1.00", Range.Decimal(0.1f, 5.0f)),
            Tunable("min", "1500", Range.Whole(0, 4095)),
            Tunable("minblack", "0", Range.Whole(0, 4095)),
            Tunable("max", "4095", Range.Whole(0, 4095)),
            Tunable("humanvel", "0", Range.Whole(0, 30)),
            Tunable("humantime", "0", Range.Whole(0, 40)),
            Tunable("burstgap", "110", Range.Whole(0, 600)),
            Tunable("burstboost", "60", Range.Whole(0, 100)),
            Tunable("minstrike", "60", Range.Whole(0, 500)),
            Tunable("isostrike", "95", Range.Whole(0, 500)),
            Tunable("isogap", "180", Range.Whole(0, 2000)),
            Tunable("gap", "30", Range.Whole(0, 300)),
            Tunable("hold", "2000", Range.Whole(50, 4000, clamps = true)),
            Tunable("restrike", "0", Range.Whole(40, 1000, zeroIsOff = true)),
            Tunable("softrelease", "1", Range.Switch),
            Tunable("releasepwm", "1100", Range.Whole(0, 4095)),
            Tunable("releasems", "22", Range.Whole(0, 200)),
            Tunable("freq", "1500", Range.Whole(24, 1526)),
            Tunable("keyforce_white", "1.00", Range.Refused),
            Tunable("keyforce_black", "1.00", Range.Refused),
            Tunable("pedalon", "0", Range.Switch),
            Tunable("pedalhalf", "0", Range.Switch),
            Tunable("pedalup", "205", Range.Whole(80, 600)),
            Tunable("pedaldown", "410", Range.Whole(80, 600)),
        )

        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        private fun noteName(note: Int): String = NAMES[note % 12] + (note / 12 - 1)

        private fun two(v: Float): String = "%.2f".format(Locale.ROOT, v)
    }
}
