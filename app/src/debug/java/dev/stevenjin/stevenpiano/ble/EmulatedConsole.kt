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
 * With a [lock] (v1.25 — M58), it is a 2.1.0 piano: the lock's facts close the dump, `claim`, `auth` and
 * `lock …` are the lock's, and on a claimed piano an untrusted connection gets "refused — not
 * authorised" for anything but reading, `off` and `broadcast`. Without one it is 2.0.x, as before.
 * With [ledColour] (M80, firmware 2.1.2, the default) it has the strip's colour: `ledcolor`, `ledtint`, `ledfade` and
 * `ledambient` after `rainspeed`, read back lower case, the tint never kept by `save`, and `!ledcolorproto=1` after the
 * lock's facts (where 2.1.2 lists it, after `!restartproto`, which this stand-in doesn't report). Without, none of them.
 * With [ledRest] too (M81, firmware 2.1.3, the default) it has the strip at rest: `ledrest` after `ledambient`, exactly `0`
 * or `1` ("usage: ledrest 0|1" otherwise), never kept by `save`, back to 0 when the link that set it drops
 * ([linkDropped]), and `!ledrestproto=1` after `!ledcolorproto=1`. Without (2.1.2), neither.
 * With [steps] too (M83, firmware 2.1.5, the default) it has the distinct loudness steps (BLE_SETTINGS.md › "Distinct
 * loudness steps"): `velsteps` after `velcurve` (stock 5), `steptable` and `calstrike` with the firmware's replies and
 * refusals word for word, one `calstrike` every 300 ms across all keys ([now]'s clock), refused on a key whose board doesn't
 * answer (the 24 V off) and while a firmware update runs ([updating]); and `!stepsproto=1`, `!stepkeys` after
 * `!ledrestproto=1`. Without (2.1.4), none of it.
 */
class EmulatedConsole(
    facts: Map<String, String> = DEFAULT_FACTS,
    var lock: EmulatedLock? = null,
    var ledColour: Boolean = true,
    var ledRest: Boolean = true,
    var steps: Boolean = true,
    /** M85, firmware 2.1.6: `boardaddr` and its facts. */
    var boardAddr: Boolean = true,
    /** The piano's millis() for `calstrike`'s 300 ms. */
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val facts = LinkedHashMap(facts)
    private val values = LinkedHashMap<String, String>().apply { TUNABLES.forEach { put(it.name, it.default) } }

    /**
     * The boards as the emulator's power plug leaves them (v1.26 — M60): MISSING while the 24 V supply is off, OK once it
     * is up; null, the default, while no fake plug feeds it, and `!boards` is the facts' own, as before.
     */
    var railBoards: () -> String? = { null }

    /**
     * M85: each board's address as the piano looks for it (`boardaddr`, kept by the piano), and where a board really
     * answers when its switches say otherwise ([stuck]: board to address, e.g. 5 to 0x4D for the new piano's dead A3).
     */
    private val boardAddresses = IntArray(BOARD_COUNT) { BOARD_BASE + it }
    var stuck: Map<Int, Int> = emptyMap()

    /** Where board [board] really answers. */
    private fun realAddress(board: Int): Int = stuck[board] ?: (BOARD_BASE + board)

    /** `!boards` with M85's moves: a board answers where the piano looks only if that's where it really is. */
    private fun movedBoards(base: String): String {
        if (stuck.isEmpty()) return base
        return base.split(',').mapIndexed { board, state ->
            if (board < BOARD_COUNT && boardAddresses[board] != realAddress(board)) "MISSING" else state
        }.joinToString(",")
    }

    /** `!strays`: the addresses a board really answers at where the piano doesn't look. */
    private fun strays(): String =
        (0 until BOARD_COUNT).map(::realAddress).filter { it !in boardAddresses }.joinToString(",") { "%02x".format(it) }

    /** Whether a firmware update runs now (M83: `calstrike`, which drives a coil, is refused meanwhile). */
    var updating: () -> Boolean = { false }

    /** M83, firmware 2.1.5: each calibrated key's row, and when the last `calstrike` struck (the piano's ms). */
    private val rows = sortedMapOf<Int, List<Int>>()
    private var lastStrikeMs: Long? = null

    /** A new connection (the lock's trust and nonce are each connection's own). */
    fun connected() {
        lock?.connected()
    }

    /** Whether notes and the pedal from this connection play: no lock, an open piano, a trusted connection or guest play. */
    fun notesAllowed(): Boolean = lock?.notesAllowed() ?: true

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
        if (name in REFUSED) return listOf("  refused over Bluetooth — use the USB console")
        lock?.let { piano ->
            piano.handle(command)?.let { return it }
            if (!piano.allows(command)) return listOf(EmulatedLock.NOT_AUTHORISED)
        }
        return when {
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
            name == BOARD_ADDR && boardAddr -> boardAddr(argument)   // M85, firmware 2.1.6
            name == "calstrike" && stepsHere() -> calStrike(command)   // M83, firmware 2.1.5
            name == "steptable" && stepsHere() -> stepTable(command)
            name in SOFT_RELEASE && argument.isNotEmpty() -> listOf("  unknown — soft release was removed in 2.1.4 (every key-up is a plain cut)")
            argument.isNotEmpty() && (name in values || name == "minwhite") && has(name) ->
                set(if (name == "minwhite") "min" else name, argument)
            else -> listOf("  unknown command — type 'help'")
        }
    }

    /** The value [name] holds, as `dump` writes it; facts with their "!" (the lock's too). */
    fun valueOf(name: String): String? {
        if (name == BOARD_ADDR) return if (boardAddr) boardAddresses.joinToString(",") { "%02x".format(it) } else null   // M85
        if (!name.startsWith("!")) return values[name]?.takeIf { has(name) }
        val fact = name.removePrefix("!")
        if (fact == BOARDS) return movedBoards(railBoards() ?: facts[BOARDS] ?: "")
        if (fact == BOARD_ADDR_PROTO && boardAddr) return "1"   // M85
        if (fact == STRAYS && boardAddr) return strays()
        if (fact == LED_COLOR_PROTO && ledColour) return "1"
        if (fact == LED_REST_PROTO && restsHere()) return "1"
        if (fact == STEPS_PROTO && stepsHere()) return "1"   // M83
        if (fact == STEP_KEYS && stepsHere()) return rows.size.toString()
        return facts[fact] ?: lock?.fact(fact)
    }

    private fun dump(): List<String> =
        facts.map { (name, value) -> "!$name=" + (if (name == BOARDS) valueOf("!$BOARDS") ?: value else value) } +
            lock?.facts().orEmpty().map { (name, value) -> "!$name=$value" } +
            (if (ledColour) listOf("!$LED_COLOR_PROTO=1") else emptyList()) +
            (if (restsHere()) listOf("!$LED_REST_PROTO=1") else emptyList()) +
            (if (stepsHere()) listOf("!$STEPS_PROTO=1", "!$STEP_KEYS=${rows.size}") else emptyList()) +
            (if (boardAddr) listOf("!$BOARD_ADDR_PROTO=1", "!$STRAYS=${strays()}") else emptyList()) +
            values.filterKeys(::has).map { (name, value) -> "$name=$value" } +
            (if (boardAddr) listOf("$BOARD_ADDR=${valueOf(BOARD_ADDR)}") else emptyList()) + "end"

    /** Whether this piano has [name]: the LED colour's keys from 2.1.2 ([ledColour]), `ledrest` from 2.1.3 ([ledRest]). */
    private fun has(name: String): Boolean = when (name) {
        LED_REST -> restsHere()
        VEL_STEPS -> stepsHere()
        in LED_COLOUR -> ledColour
        else -> true
    }

    /** 2.1.3 has the strip's colour too. */
    private fun restsHere(): Boolean = ledColour && ledRest

    /** 2.1.5 has everything 2.1.3 has. */
    private fun stepsHere(): Boolean = restsHere() && steps

    /** The keys holding a calibration row and their PWMs (M83), for the emulator's log. */
    fun stepRows(): Map<Int, List<Int>> = rows.toMap()

    /**
     * `boardaddr <board> <hex>|default` (M85) as 2.1.6 answers it: `usage:` unless a board 0..6 and an address 40..77 or
     * `default`; refused while an update runs, on a clash with another board, or on a reserved address; else taken and
     * kept, answering whether a board is there. (The emulator has no coils on to refuse for.)
     */
    private fun boardAddr(argument: String): List<String> {
        val usage = listOf("  usage: boardaddr [<board 0..6> <hex 40..77>|default]")
        val parts = argument.split(' ').filter { it.isNotEmpty() }
        val board = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0 until BOARD_COUNT } ?: return usage
        if (parts.size != 2) return usage
        if (updating()) return listOf("  boardaddr refused: updating firmware")
        val address = if (parts[1].equals("default", true)) {
            BOARD_BASE + board
        } else {
            parts[1].removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0x40..0x77 } ?: return usage
        }
        if (address in RESERVED) return listOf("  boardaddr refused: 0x%02X is reserved".format(address))
        if ((0 until BOARD_COUNT).any { it != board && boardAddresses[it] == address }) {
            return listOf("  boardaddr refused: 0x%02X is another board's".format(address))
        }
        boardAddresses[board] = address
        val answers = realAddress(board) == address
        return listOf("  boardaddr $board = 0x%02X: ".format(address) + if (answers) "OK (it answers)" else "MISSING (nothing answers there)")
    }

    /**
     * `calstrike <note> <pwm>` (M83) as 2.1.5 answers it: refused while an update runs; `usage:` unless exactly a note
     * 0..127 and a PWM 1..4096; "too soon" within 300 ms of the last strike, any key; "busy or has no board" on a key whose
     * power board doesn't answer (the 24 V off) or off the piano's 84; else struck for 100 ms.
     */
    private fun calStrike(command: String): List<String> {
        if (updating()) return listOf("  refused — updating firmware")
        val parts = command.removePrefix("calstrike ").split(' ')
        val note = parts.getOrNull(0)?.toIntOrNull()
        val pwm = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != 2 || note == null || pwm == null || note !in 0..127 || pwm !in 1..4096) {
            return listOf("  usage: calstrike <note 0..127> <pwm 1..4096>")
        }
        val t = now()
        val last = lastStrikeMs
        if (last != null && t - last < CAL_STRIKE_GAP_MS) return listOf("  calstrike refused: too soon (one every $CAL_STRIKE_GAP_MS ms)")
        if (!boardAnswers(note)) return listOf("  calstrike refused: that key is busy or has no board")
        lastStrikeMs = t
        return listOf("  calstrike $note at $pwm: struck for $CAL_STRIKE_MS ms")
    }

    /** Whether [note]'s power board answers: one of the piano's 84, its board OK in `!boards`. */
    private fun boardAnswers(note: Int): Boolean {
        if (note !in KEYS) return false
        val boards = (valueOf("!$BOARDS") ?: "").split(',')
        return boards.getOrNull((note - KEYS.first) / 12)?.trim() == "OK"
    }

    /** `steptable …` (M83) as 2.1.5 answers it: print, clear one, clear all, or set 2..8 rising PWMs 1..4095. */
    private fun stepTable(command: String): List<String> {
        val usage = listOf("  usage: steptable <note> [p1..pN rising 1..4095 | clear] | steptable clear")
        if (command == "steptable clear") {
            rows.clear()
            return listOf("  steptable cleared: every key spreads its steps over min..max")
        }
        val tokens = command.removePrefix("steptable").split(' ').filter { it.isNotEmpty() }
        val note = tokens.firstOrNull()?.toIntOrNull()?.takeIf { it in 0..127 } ?: return usage
        val rest = tokens.drop(1)
        if (rest.isEmpty()) {
            val row = rows[note] ?: return listOf("  steptable $note: not calibrated")
            return listOf("  steptable $note:" + row.joinToString("") { " $it" })
        }
        if (rest == listOf("clear")) {
            rows.remove(note)
            return listOf("  steptable $note cleared")
        }
        val pwms = ArrayList<Int>()
        for (token in rest) {
            val pwm = token.toIntOrNull()
            if (pwms.size >= VEL_STEPS_MAX || pwm == null || pwm !in 1..4095 || (pwms.isNotEmpty() && pwm <= pwms.last())) return usage
            pwms += pwm
        }
        if (pwms.size < 2) return usage
        rows[note] = pwms
        return listOf("  steptable $note = ${pwms.size} steps (saved by save or the auto-save)")
    }

    /**
     * The link that set `ledrest` dropped (M81, firmware 2.1.3): it goes back to 0, as the piano lets the strip down to the
     * notes. True when it was 1 (the emulator's log says so).
     */
    fun linkDropped(): Boolean {
        val was = values[LED_REST] == "1"
        values[LED_REST] = "0"
        return was
    }

    /** What the strip shows now, in words, for the emulator's log (M80): the tint over the colour, and the fade to it. */
    fun stripColour(): String {
        val tint = values["ledtint"]
        val shown = if (tint == null || tint == "off") "ledcolor ${values["ledcolor"]}" else "ledtint $tint over ledcolor ${values["ledcolor"]}"
        val rest = if (!restsHere()) "" else if (values[LED_REST] == "1") ", at rest: solid" else ", at rest: notes only"
        return "$shown, fading over ${values["ledfade"]} ms, glow ${values["ledambient"]}, mode ${values["ledmode"]}, palette ${values["reactcolor"]}$rest"
    }

    /** `get`'s line: a setting as `name=value`; a fact as `!name=value`, named with its "!" or without (v1.26 — M60: as the firmware). */
    private fun read(name: String): String? {
        valueOf(name)?.let { return "$name=$it" }
        if (!name.startsWith("!")) valueOf("!$name")?.let { return "!$name=$it" }
        return null
    }

    private fun set(name: String, argument: String): List<String> {
        val tunable = TUNABLES.first { it.name == name }
        return when (val range = tunable.range) {
            Range.Switch -> {
                val on = (argument.toIntOrNull() ?: 0) != 0   // the firmware's atoi: anything else reads as 0
                values[name] = if (on) "1" else "0"
                when {
                    // M83b: Full power's replies word for word (BLE_SETTINGS.md › "Distinct loudness steps" quotes them).
                    name == "fullpower" && on -> listOf("  fullpower = ON — every NoteOn drives FULL_ON. Max force, no hum, no velocity.")
                    name == "fullpower" -> listOf("  fullpower = OFF — velocity-mapped PWM with min/max/velmult.")
                    else -> listOf("  $name = ${if (on) "ON" else "OFF"}")
                }
            }
            is Range.Whole -> {
                val v = argument.toIntOrNull() ?: 0
                val allowed = v in range.low..range.high || (range.zeroIsOff && v == 0)
                when {
                    range.clamps -> {
                        values[name] = v.coerceIn(range.low, range.high).toString()
                        listOf("  $name = ${values[name]} (clamped to ${range.low}..${range.high})")
                    }
                    !allowed -> listOf("  $name out of range (${if (range.zeroIsOff) "0 = off, or " else ""}${range.low}..${range.high}${range.unit})")
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
            // M80, firmware 2.1.2: six hex digits (any case, read back lower case), the tint `off` too.
            is Range.Hex -> {
                val hex = argument.lowercase(Locale.ROOT)
                if (HEX.matches(hex) || (range.off && hex == "off")) {
                    values[name] = hex
                    listOf("  $name = $hex")
                } else {
                    listOf(if (range.off) "  usage: $name RRGGBB|off" else "  usage: $name RRGGBB (6 hex digits)")
                }
            }
            // M83, firmware 2.1.5: 0 (the smooth curve) or 2..8, in the firmware's own words.
            Range.Steps -> {
                val v = argument.toIntOrNull()
                if (v == null || v !in 0..VEL_STEPS_MAX || v == 1) {
                    listOf("  velsteps out of range (0 or 2..$VEL_STEPS_MAX)")
                } else {
                    values[name] = v.toString()
                    if (v == 0) {
                        listOf("  velsteps = 0: the smooth velocity curve")
                    } else {
                        listOf("  velsteps = $v: every note snaps to one of $v fixed loudness steps" + if (values["fullpower"] == "1") " (shows once fullpower is 0)" else "")
                    }
                }
            }
            // M81, firmware 2.1.3: exactly 0 or 1, live only.
            Range.Rest -> if (argument == "0" || argument == "1") {
                values[name] = argument
                val elsewhere = if (values["ledmode"] == "3") "" else "; it shows in reactive mode only"
                listOf("  LED rest = $argument (live only, never saved; ends with this Bluetooth link)$elsewhere")
            } else {
                listOf("  usage: $name 0|1")
            }
            Range.Refused -> listOf("  refused over Bluetooth — use the USB console")
        }
    }

    private fun preset(name: String): List<String> {
        val changes = when (name) {
            // Firmware 2.1.4 removed soft release: no preset touches it.
            "soft" -> mapOf("fullpower" to "0", "volume" to "55")
            "cinematic" -> mapOf(
                "fullpower" to "0", "minstrike" to "45", "gap" to "22", "restrike" to "0", "leds" to "1", "ledmode" to "3",
            )
            "expressive" -> mapOf(
                "fullpower" to "0", "min" to "2400", "max" to "4095", "velcurve" to "1.70", "humanvel" to "6",
                "humantime" to "12", "volume" to "100",
            )
            else -> mapOf("leds" to "0", "fullpower" to "1", "gap" to "0", "minstrike" to "20", "restrike" to "0")
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
            "  leds=${onOff("leds")}   led: bright=${v("ledbright")}/255 count=${v("ledcount")} offset=${v("ledoffset")}",
            "  pedal servo: ${if (v("pedalon") == "1") "ENABLED" else "disabled"}  board=${facts["pedalboard"]}",
            "  i2cFails=${facts["i2cfails"]} (rises during PWM-EMI storms; steady 0 is healthy)",
            "  boards:",
        ) + (valueOf("!$BOARDS") ?: "").split(',').mapIndexed { i, state -> "    [$i] (MIDI ${24 + 12 * i}-${35 + 12 * i}): $state" }
    }

    private sealed interface Range {
        data object Switch : Range

        data class Whole(val low: Int, val high: Int, val zeroIsOff: Boolean = false, val clamps: Boolean = false, val unit: String = "") : Range

        /** Six hex digits; [off]: or `off` (the tint). */
        data class Hex(val off: Boolean = false) : Range

        /** M81: exactly `0` or `1` (`ledrest`; not the switches' atoi). */
        data object Rest : Range

        /** M83: `velsteps`, 0 or 2..8. */
        data object Steps : Range

        data class Decimal(val low: Float, val high: Float) : Range

        data object Refused : Range
    }

    private class Tunable(val name: String, val default: String, val range: Range)

    companion object {
        private const val SAVED = "[settings] saved to NVS"
        private const val BOARDS = "boards"

        /** The LED colour's keys and fact (M80, firmware 2.1.2). */
        private val LED_COLOUR = setOf("ledcolor", "ledtint", "ledfade", "ledambient")
        private const val LED_COLOR_PROTO = "ledcolorproto"

        /** The strip at rest's key and fact (M81, firmware 2.1.3). */
        private const val LED_REST = "ledrest"
        private const val LED_REST_PROTO = "ledrestproto"

        /** M85, firmware 2.1.6: the boards' addresses, their fact, and what answers where no board is looked for. */
        private const val BOARD_ADDR = "boardaddr"
        private const val BOARD_ADDR_PROTO = "boardaddrproto"
        private const val STRAYS = "strays"
        private const val BOARD_COUNT = 7
        private const val BOARD_BASE = 0x40
        private val RESERVED = setOf(0x47, 0x70, 0x71, 0x72, 0x74) + (0x78..0x7F)

        /** The distinct loudness steps' key and facts (M83, firmware 2.1.5), and its limits (config.h). */
        private const val VEL_STEPS = "velsteps"
        private const val STEPS_PROTO = "stepsproto"
        private const val STEP_KEYS = "stepkeys"
        private const val VEL_STEPS_MAX = 8
        private const val CAL_STRIKE_MS = 100
        private const val CAL_STRIKE_GAP_MS = 300L
        private const val NANOS_PER_MS = 1_000_000L
        private val KEYS = 24..107
        private val HEX = Regex("^[0-9a-f]{6}$")

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

        /** Soft release's keys: firmware 2.1.4 removed them, and answers a write with its own refusal. */
        private val SOFT_RELEASE = setOf("softrelease", "releasepwm", "releasems")

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
            // M80, firmware 2.1.2: the strip's colour, its tint (never saved), the fade and the glow.
            Tunable("ledcolor", "ffb066", Range.Hex()),
            Tunable("ledtint", "off", Range.Hex(off = true)),
            Tunable("ledfade", "1500", Range.Whole(0, 5000, unit = " ms")),
            Tunable("ledambient", "0", Range.Whole(0, 64)),
            Tunable("ledrest", "0", Range.Rest),   // M81, firmware 2.1.3: live only, 0 at boot
            Tunable("dimsecs", "60", Range.Whole(0, 3600)),
            Tunable("dimfloor", "0", Range.Whole(0, 255)),
            Tunable("keyviz", "1", Range.Switch),
            Tunable("fullpower", "1", Range.Switch),
            Tunable("volume", "100", Range.Whole(0, 100)),
            Tunable("velcurve", "1.60", Range.Decimal(0.4f, 3.0f)),
            Tunable("velsteps", "5", Range.Steps),   // M83, firmware 2.1.5: stock 5
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
