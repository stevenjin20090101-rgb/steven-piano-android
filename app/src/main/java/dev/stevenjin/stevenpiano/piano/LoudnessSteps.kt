// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

/**
 * Distinct loudness steps (M83, piano firmware 2.1.5; `firmware/docs/BLE_SETTINGS.md` › "Distinct loudness steps"), as the
 * app speaks it: the facts (`!stepsproto=1`, `!stepkeys`), the calibration's lines (`calstrike`, `steptable`, `velsteps`,
 * then `fullpower 0` and `save`), and their replies word for word. Steven: "distinct notes of soft and hard", as many
 * clearly different steps as each key's window holds. Pure.
 */
object LoudnessSteps {
    /** The facts: the steps exist (from 2.1.5), and how many keys hold a calibration row. */
    const val PROTO = "stepsproto"
    const val KEYS = "stepkeys"

    /** The setting: 0 (the smooth curve) or 2..8 fixed steps; stock 5. */
    const val STEPS = "velsteps"
    const val LEAST = 2
    const val MOST = 8

    /** A step's PWM: 1..4095 (4095 is full on); `calstrike` takes 1..4096. */
    const val PWM_LEAST = 1
    const val PWM_MOST = 4095

    /** The firmware's limit: one `calstrike` every 300 ms across all keys, each 100 ms long (its reply says how long). */
    const val STRIKE_GAP_MS = 300L
    const val STRIKE_MS = 100

    /**
     * After a strike's 100 ms the firmware lets the key go through its verified OFF on the next 10 ms safety tick, the
     * write itself a few ms more: how much longer than its 100 ms a `calstrike` counts as that key held for the power
     * watch ([heldSpan]).
     */
    const val RELEASE_LAG_MS = 20L

    /** The calibration's last two lines. */
    const val FULL_POWER_OFF = "fullpower 0"
    const val SAVE = "save"

    /** The piano has the steps: its dump says `!stepsproto=1`. */
    fun supported(facts: Map<String, String>?): Boolean = facts?.get(PROTO)?.trim() == "1"

    /** How many keys hold a calibration row (`!stepkeys`); null before the piano says. */
    fun calibrated(facts: Map<String, String>?): Int? = facts?.get(KEYS)?.trim()?.toIntOrNull()

    /** `calstrike <note> <pwm>`. */
    fun strike(note: Int, pwm: Int): String = "calstrike $note $pwm"

    /** `steptable <note> <p1> … <pN>`: that key's row, 2..8 PWMs, strictly rising. */
    fun row(note: Int, pwms: List<Int>): String = "steptable $note " + pwms.joinToString(" ")

    /** `steptable <note> clear`: the key spreads its steps over `min`..`max` again. */
    fun clear(note: Int): String = "steptable $note clear"

    /** `velsteps <n>`. */
    fun steps(n: Int): String = "velsteps $n"

    /** A reply as the calibration reads it. */
    sealed interface Reply {
        /** `calstrike` struck, for [ms]. */
        data class Struck(val ms: Int) : Reply

        /** `calstrike refused: too soon (one every 300 ms)`: wait, and send it again. */
        data object TooSoon : Reply

        /** The line did what the spec says it does. */
        data object Done : Reply

        /** Anything else: [text], trimmed, as the piano wrote it. */
        data class Other(val text: String) : Reply
    }

    /** Whether [line] is one of the calibration's own: nothing else goes through its conversation. */
    fun ours(line: String): Boolean = STRIKE.matches(line) || ROW.matches(line) || CLEAR.matches(line) || STEPS_LINE.matches(line) ||
        line == FULL_POWER_OFF || line == SAVE

    /**
     * Whether [reply] answers [line] (the first such line is its answer; any other line is the settings' as before): the
     * line's own reply or refusal, or a refusal the firmware gives any command (`refused — updating firmware`, the lock's
     * `refused — not authorised …`, `refused over Bluetooth …`, an unknown command). Never the echo (`> …`) or a value.
     */
    fun answers(line: String, reply: String): Boolean {
        val text = reply.trim()
        if (text.isEmpty() || text.startsWith(">") || VALUE.matches(text)) return false
        if (text.startsWith(REFUSED) || text.contains(UNKNOWN)) return true
        return when (line.substringBefore(' ')) {
            "calstrike" -> text.startsWith("calstrike ") || text.startsWith("usage: calstrike")
            "steptable" -> text.startsWith("steptable ") || text.startsWith("usage: steptable")
            STEPS -> text.startsWith("$STEPS ")
            "fullpower" -> text.startsWith("fullpower = ")
            SAVE -> text.startsWith(SETTINGS_SAVE)
            else -> false
        }
    }

    /** What [reply] (one that [answers] [line]) says, word for word against the spec. */
    fun read(line: String, reply: String): Reply {
        val text = reply.trim()
        STRIKE.matchEntire(line)?.let { sent ->
            if (text == TOO_SOON) return Reply.TooSoon
            val struck = STRUCK.matchEntire(text) ?: return Reply.Other(text)
            val same = struck.groupValues[1] == sent.groupValues[1] && struck.groupValues[2] == sent.groupValues[2]
            return if (same) Reply.Struck(struck.groupValues[3].toInt()) else Reply.Other(text)
        }
        CLEAR.matchEntire(line)?.let { sent ->
            return if (text == "steptable ${sent.groupValues[1]} cleared") Reply.Done else Reply.Other(text)
        }
        ROW.matchEntire(line)?.let { sent ->
            val n = line.split(' ').size - 2
            return if (text == "steptable ${sent.groupValues[1]} = $n steps (saved by save or the auto-save)") Reply.Done else Reply.Other(text)
        }
        STEPS_LINE.matchEntire(line)?.let { sent ->
            val n = sent.groupValues[1].toInt()
            val said = if (n == 0) {
                text == "velsteps = 0: the smooth velocity curve"
            } else {
                val base = "velsteps = $n: every note snaps to one of $n fixed loudness steps"
                text == base || text == "$base (shows once fullpower is 0)"
            }
            return if (said) Reply.Done else Reply.Other(text)
        }
        return when (line) {
            FULL_POWER_OFF -> if (text.startsWith("fullpower = OFF")) Reply.Done else Reply.Other(text)
            SAVE -> if (text == SAVED) Reply.Done else Reply.Other(text)
            else -> Reply.Other(text)
        }
    }

    /**
     * What a `calstrike` counts as for the power watch ([dev.stevenjin.stevenpiano.power.HeldKeys.struckOutside]): that key
     * held from when the line was [sentMs] until its reply came ([repliedMs]: the strike began at the piano somewhere in
     * between) plus the [struckMs] the reply said and [RELEASE_LAG_MS], and no more. On the keeper's clock, ms.
     */
    fun heldSpan(sentMs: Long, repliedMs: Long, struckMs: Int): Pair<Long, Long> = sentMs to maxOf(sentMs, repliedMs) + struckMs + RELEASE_LAG_MS

    private const val REFUSED = "refused"
    private const val UNKNOWN = "unknown command"
    private const val TOO_SOON = "calstrike refused: too soon (one every 300 ms)"
    private const val SETTINGS_SAVE = "[settings] save"
    private const val SAVED = "[settings] saved to NVS"

    private val STRIKE = Regex("""^calstrike (\d{1,3}) (\d{1,4})$""")
    private val ROW = Regex("""^steptable (\d{1,3})( \d{1,4}){2,8}$""")
    private val CLEAR = Regex("""^steptable (\d{1,3}) clear$""")
    private val STEPS_LINE = Regex("""^velsteps (\d)$""")
    private val STRUCK = Regex("""^calstrike (\d+) at (\d+): struck for (\d+) ms$""")
    private val VALUE = Regex("""^!?[a-z][a-z0-9_]*=.*$""")
}
