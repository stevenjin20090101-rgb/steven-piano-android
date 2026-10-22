// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.power.PairStep
import dev.stevenjin.stevenpiano.power.PlugSettings
import dev.stevenjin.stevenpiano.power.PlugStatus
import dev.stevenjin.stevenpiano.power.Provision
import dev.stevenjin.stevenpiano.power.RailMessage
import dev.stevenjin.stevenpiano.power.RailPhase
import dev.stevenjin.stevenpiano.power.SetUpTask
import java.util.Locale
import kotlin.math.roundToInt

/** The 24 V smart plug's words (v1.26 — M60; DESIGN.md › v1.26 — M60): the messages, Piano › Power plug, its Pair screen. */
object PowerCopy {
    const val TITLE = "Power plug"

    // ---- The messages ------------------------------------------------------------------------------------------------

    const val WAKING = "Waking the piano…"
    const val RAIL_OFF = "24 V off"
    const val PIANO_SILENT = "The piano stopped answering — 24 V cut"
    const val PLUG_LOST = "Lost the power plug"
    const val OK = "OK"

    /** "Only 6 of 7 power boards answered" (a trunk fault), and "— 24 V cut" when the rail was cut for it. */
    fun trunk(answered: Int, total: Int, cut: Boolean): String =
        (if (answered == 0) "None of the $total power boards answered" else "Only $answered of $total power boards answered") +
            if (cut) " — 24 V cut" else ""

    fun message(message: RailMessage): String = when (message) {
        RailMessage.Waking -> WAKING
        RailMessage.Off -> RAIL_OFF
        RailMessage.PianoSilent -> PIANO_SILENT
        RailMessage.PlugLost -> PLUG_LOST
        is RailMessage.TrunkFault -> trunk(message.answered, message.total, message.cut)
    }

    // ---- The hub's row and the rail in a word --------------------------------------------------------------------------

    const val NOT_PAIRED = "Not paired"
    const val SWITCHED_OFF = "Off"
    const val RAIL_ON = "24 V on"
    const val GUEST_ON = "24 V on for guest play"

    /** The hub's row: "Not paired", "Off" (the plug's switch), or where the rail stands ("24 V off", "24 V on"…). */
    fun hub(settings: PlugSettings?, phase: RailPhase): String = when {
        settings?.pairing == null -> NOT_PAIRED
        !settings.on -> SWITCHED_OFF
        else -> rail(phase)
    }

    fun rail(phase: RailPhase): String = when (phase) {
        RailPhase.Waking -> WAKING
        RailPhase.Up, RailPhase.Kept -> RAIL_ON
        RailPhase.Off, RailPhase.Stopping -> RAIL_OFF
    }

    // ---- Piano › Power plug -------------------------------------------------------------------------------------------

    const val EXPLANATION =
        "The 24 V supply for the piano's keys can hang on a Bluetooth smart plug (a Shelly Plug US Gen4). This tablet then " +
            "keeps it on only while something plays and the piano answers with its power boards. Anything else — the tablet " +
            "asleep, the app closed, the piano silent — and the plug switches it off by itself within 30 seconds, so nothing " +
            "stays hot while the piano is idle."
    const val PAIR = "Pair the power plug"
    const val PAIR_NOTE = "About 15 minutes, with your phone and the Shelly app. Only the 24 V supply goes on the plug."

    const val SWITCH = "Switch the 24 V with the plug"
    const val SWITCH_ON_NOTE = "The 24 V comes on for each piece, Test every key, the Keys tab and updates, and goes off after."
    const val SWITCH_OFF_NOTE = "The app leaves the plug alone, and the plug stays off: to play without it, plug the 24 V supply into a wall socket."

    const val STATE = "The plug"
    const val OUTPUT = "24 V"
    const val DRAW = "The supply draws"
    const val SETUP = "Setup"
    const val FIRMWARE = "Plug firmware"
    const val NOT_ANSWERING = "Not answering"
    const val READING = "Reading…"

    const val ADMIT = "Admit a replacement tablet"
    const val ADMIT_NOTE = "For 3 minutes another tablet can pair: on it, open Piano › Power plug › Pair the power plug."
    const val ADMIT_OPEN = "The plug lets another tablet pair for 3 minutes."
    const val ADMIT_FAILED = "The plug didn't answer. Try again near it."
    const val FORGET = "Forget the plug"
    const val FORGET_NOTE = "The app stops switching the 24 V and the plug forgets this tablet."
    const val FORGET_TITLE = "Forget the power plug?"
    const val FORGET_TEXT =
        "The 24 V goes off now and the plug forgets this tablet. It stays off until the plug is paired again, or the supply " +
            "moves to a wall socket."
    const val FORGET_ACTION = "Forget"
    const val WELDED = "After the 24 V was cut the plug still reads on: its relay may be stuck. Unplug the supply and check the plug."
    const val WELDED_SHORT = "The plug's relay may be stuck on"

    /** The plug's output: "On · 24 s left", "On", "Off". */
    fun output(status: PlugStatus?, secondsLeft: Long?): String = when {
        status == null -> NOT_ANSWERING
        !status.output -> "Off"
        secondsLeft != null && secondsLeft > 0 -> "On · $secondsLeft s left"
        else -> "On"
    }

    /** The supply's draw: "6.4 W". */
    fun watts(status: PlugStatus?): String = status?.watts?.let { String.format(Locale.ROOT, "%.1f W", it) } ?: "—"

    /** The System page's fact: "On · 24 s left · 6.4 W", "Off · 0.0 W", "Not answering". */
    fun fact(status: PlugStatus?, secondsLeft: Long?): String =
        if (status == null) NOT_ANSWERING else output(status, secondsLeft) + " · " + watts(status)

    fun provision(provision: Provision): String = when (provision) {
        Provision.Pending -> "Pending: new, not set up yet"
        Provision.Confirmed -> "Confirmed: set up, finishing"
        Provision.Complete -> "Complete"
        Provision.Locked -> "Locked: needs a power cycle"
        Provision.Unknown -> "—"
    }

    // ---- The Pair screen ----------------------------------------------------------------------------------------------

    fun stepTitle(step: PairStep): String = when (step) {
        PairStep.ShellyApp -> "Set it up with the Shelly app"
        PairStep.Find -> "Find the plug"
        PairStep.SetUp -> "Pair it with this tablet"
        PairStep.Complete -> "Wait while it finishes its setup"
        PairStep.Network -> "Close its network"
        PairStep.Verify -> "Check it"
        PairStep.Done -> "Done"
    }

    fun stepNote(step: PairStep): String = when (step) {
        PairStep.ShellyApp ->
            "Plug the 24 V supply into the plug and the plug into the wall. On your phone, turn on a hotspot that has internet, " +
                "add the plug in the Shelly app on that hotspot, decline the cloud, and let it update its firmware (2.0.0 or " +
                "later). Then come straight back here: the next steps must start within about 5 minutes."
        PairStep.Find -> "The tablet looks for plugs nearby. Choose yours: its name begins ShellyPlugUSG4."
        PairStep.SetUp ->
            "Its settings (off after a power cut, its button can't hold the 24 V on), Bluetooth control, then pairing: when " +
                "Android asks to pair with the plug, tap Pair. Then its cloud goes off."
        PairStep.Complete -> "The plug finishes its setup on its own, about 5 minutes. Keep the phone's hotspot on until it does."
        PairStep.Network ->
            "The plug forgets every network and closes its own: from now on only this tablet can talk to it. Then turn the " +
                "hotspot off."
        PairStep.Verify ->
            "The 24 V comes on for 5 seconds: every power board should answer, then the plug switches off by itself. Connect " +
                "to the piano first."
        PairStep.Done -> "The 24 V now follows the music."
    }

    const val SHELLY_DONE = "Done in the Shelly app"
    const val SEARCH = "Search again"
    const val SEARCHING = "Searching…"
    const val SET_UP = "Pair and set up"
    const val CLOSE_NETWORK = "Close its network"
    const val VERIFY = "Turn the 24 V on for 5 s"
    const val VERIFY_AGAIN = "Check again"
    const val FINISH = "Finish"
    const val SKIP_CHECK = "Finish without the check"
    const val START_OVER = "Start over"
    const val FIND_AGAIN = "Find the plug again"

    fun task(task: SetUpTask): String = when (task) {
        SetUpTask.Settings -> "Settings"
        SetUpTask.Bluetooth -> "Bluetooth control"
        SetUpTask.Window -> "Pairing window"
        SetUpTask.Bond -> "Paired with this tablet"
        SetUpTask.Listed -> "On the plug's list"
        SetUpTask.Cloud -> "Cloud off"
    }

    /** "7 of 7 answering" while the check runs. */
    fun answering(answered: Int?, total: Int): String = if (answered == null) "Reading the boards…" else "$answered of $total answering"

    const val VERIFY_PASSED = "Every board answered with the 24 V on, and none once it went off."

    // The fallback: the plug finished its setup without this tablet.
    const val FALLBACK =
        "Reset the plug: hold its button for about 10 seconds until its light flashes (a factory reset keeps its firmware, not " +
            "its pairings or network). Then find it again: it will be new, and this tablet pairs first, then gives it the " +
            "hotspot over Bluetooth."
    const val JOIN = "Pair with a plug another tablet set up"
    const val JOIN_NOTE = "If the tablet the plug knows opened Admit a replacement tablet in the last 3 minutes."
    const val NEW_PLUG =
        "This plug hasn't been set up yet. Do step 1 in the Shelly app first, or set it up from here: this tablet pairs first, " +
            "then gives the plug your hotspot's name and password over Bluetooth."
    const val OVER_BLUETOOTH = "Set it up from here"
    const val HOTSPOT = "Hotspot name"
    const val PASSWORD = "Hotspot password"
    const val SEND_NETWORK = "Send to the plug"

    // ---- Firmware and status: guest play with the plug -------------------------------------------------------------------

    /** Under the guest switch while the plug switches the 24 V. */
    const val GUEST_WITH_PLUG = "The 24 V stays on for it (an hour at most), and this tablet lets go of the piano so another device can connect."
    const val END_GUEST = "End guest play"
    const val END_GUEST_NOTE = "Connects to the piano again, ends guest play, releases every key, then turns the 24 V off."

    /** Guest play's line while this tablet keeps the 24 V on for it: "The 24 V stays on for guest play · 52 min left". */
    fun guestLeft(secondsLeft: Long): String {
        val minutes = (secondsLeft / 60.0).roundToInt().coerceAtLeast(if (secondsLeft > 0) 1 else 0)
        return "The 24 V stays on for guest play · " + if (minutes >= 1) "$minutes min left" else "ending"
    }
}
