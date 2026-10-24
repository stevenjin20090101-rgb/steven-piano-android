// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.power.PairState
import dev.stevenjin.stevenpiano.power.PairStep
import dev.stevenjin.stevenpiano.power.PlugSettings
import dev.stevenjin.stevenpiano.power.PlugStatus
import dev.stevenjin.stevenpiano.power.Provision
import dev.stevenjin.stevenpiano.power.RailMessage
import dev.stevenjin.stevenpiano.power.RailPhase
import dev.stevenjin.stevenpiano.power.SetUpTask
import dev.stevenjin.stevenpiano.power.UpdateState
import dev.stevenjin.stevenpiano.power.UpdateTask
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The 24 V smart plug's words (v1.26 — M60; DESIGN.md › v1.26 — M60): the messages, Piano › Power plug, its Pair screen
 * (v1.28 — M65: without the Shelly app, the update on old firmware).
 */
object PowerCopy {
    const val TITLE = "Power plug"

    // ---- The messages ------------------------------------------------------------------------------------------------

    const val WAKING = "Waking the piano…"
    const val RAIL_OFF = "24 V off"
    const val PIANO_SILENT = "The piano stopped answering — 24 V cut"
    const val PLUG_LOST = "Lost the power plug"

    /** The plug watch (v1.29 — M66). */
    const val SWITCHED_ON = "The power plug had been switched on by something else: switched off"
    const val SETTINGS_PUT_BACK = "The power plug's settings had been changed: put back"
    const val OK = "OK"

    /** The power watch (v1.30 — M67): a key on, seen in the 24 V's draw while a piece played, or in the check after it. */
    const val STUCK_KEY = "A key stayed on — 24 V cut"
    const val STUCK_AFTER_PIECE = "A key stayed on after the piece — 24 V cut"

    /** The power watch (v1.30 — M67): "The 24 V draws 36 W with nothing playing — 24 V cut", at rail-up. */
    fun overdraw(watts: Int): String = "The 24 V draws $watts W with nothing playing — 24 V cut"

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
        RailMessage.SwitchedOn -> SWITCHED_ON
        RailMessage.SettingsPutBack -> SETTINGS_PUT_BACK
        is RailMessage.StuckKey -> if (message.afterPiece) STUCK_AFTER_PIECE else STUCK_KEY
        is RailMessage.Overdraw -> overdraw(message.watts)
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
    const val PAIR_NOTE = "About 15 minutes, with your phone's hotspot. Only the 24 V supply goes on the plug."

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
        PairStep.Ready -> "Get it ready"
        PairStep.Find -> "Find the plug"
        PairStep.Update -> "Update its firmware"
        PairStep.SetUp -> "Pair it with this tablet"
        PairStep.Complete -> "Wait while it finishes its setup"
        PairStep.Network -> "Close its network"
        PairStep.Verify -> "Check it"
        PairStep.Done -> "Done"
    }

    /** A step's words (v1.28 — M65: the Update step's name the plug's firmware; a new plug set up from here has its own order). */
    fun stepNote(step: PairStep, pair: PairState): String = when (step) {
        PairStep.Ready ->
            "Plug the 24 V supply into the plug, and the plug into the wall. On your phone, turn on a hotspot that has internet, " +
                "and keep it on until the setup ends (about 15 minutes). The plug only sees 2.4 GHz: on an iPhone, turn on " +
                "Maximize Compatibility; on Android, choose the 2.4 GHz band."
        PairStep.Find -> "The tablet looks for plugs nearby. Choose yours: its name begins ShellyPlugUSG4."
        PairStep.Update -> updateNote(pair.firmwareUpdate?.from)
        PairStep.SetUp -> if (pair.asIs) {
            AS_IS_SETUP + if (pair.hotspotWanted) " " + AS_IS_HOTSPOT else ""
        } else if (pair.overBluetooth) {
            "Pairing first: when Android asks to pair with the plug, tap Pair. Then the plug joins your hotspot, takes its " +
                "settings (off after a power cut, its button can't hold the 24 V on) and Bluetooth control, and its cloud goes off."
        } else {
            "Its settings (off after a power cut, its button can't hold the 24 V on), Bluetooth control, then pairing: when " +
                "Android asks to pair with the plug, tap Pair. Then its cloud goes off."
        }
        PairStep.Complete -> if (pair.overBluetooth) {
            "The plug finishes its setup on its own, about 5 minutes. Keep the phone's hotspot on until it does: then the plug " +
                "forgets every network by itself."
        } else {
            "The plug finishes its setup on its own, about 5 minutes. Keep the phone's hotspot on until it does."
        }
        PairStep.Network ->
            "The plug forgets every network and closes its own: from now on only this tablet can talk to it. Then turn the " +
                "hotspot off."
        PairStep.Verify -> (if (pair.overBluetooth) "The plug is off every network now: turn the hotspot off. " else "") +
            "The 24 V comes on for 5 seconds: every power board should answer, then the plug switches off by itself. Connect " +
            "to the piano first."
        PairStep.Done -> "The 24 V now follows the music."
    }

    /**
     * The Update step's words (v1.28 — M65): the plug's firmware, why it must change, and what happens next; and (v1.28.1)
     * that this firmware can't encrypt Bluetooth before pairing, so the password crosses in the clear this once
     * (SMART_PLUG.md › One-time setup).
     */
    fun updateNote(firmware: String?): String =
        "The plug has firmware ${firmware ?: "older than 2.0.0"}, and locking it to this tablet needs 2.0.0 or later. Use it " +
            "as it is, or update it: given your hotspot's name and password, it joins the hotspot and updates itself from " +
            "Shelly (about 2 minutes), its setup is cleared, " +
            "then it pairs with this tablet and finishes its setup on the hotspot, all by itself. Keep the hotspot on and the " +
            "tablet near the plug. This firmware can't encrypt Bluetooth yet, so the password goes over unencrypted this " +
            "once: give the hotspot a password just for today, and change it afterwards."

    // ---- As it is (v1.29 — M66) -----------------------------------------------------------------------------------------

    const val AS_IS = "Use it as it is"
    const val AS_IS_NOTE =
        "It works, but any phone nearby with the Shelly app could switch it. The tablet checks it every minute and switches " +
            "it back off."
    const val LOCK_IT = "Update and lock it to this tablet"
    const val LOCK_NOTE = "About 2 more minutes on the hotspot; then only this tablet can talk to it."
    const val AS_IS_SETUP =
        "Its settings (off after a power cut, its button can't hold the 24 V on), Bluetooth control and the cloud off, with " +
            "no update and no pairing."
    const val AS_IS_HOTSPOT =
        "It must get online once to finish its setup (about 5 minutes), then it forgets every network: give it your " +
            "hotspot's name and password. This firmware can't encrypt Bluetooth, so the password goes over unencrypted: " +
            "give the hotspot a password just for today, and change it afterwards."

    /** Piano › Power plug for a plug used as it is (v1.29 — M66). */
    const val UNLOCKED =
        "Not locked to this tablet. A phone nearby with the Shelly app could switch it; the tablet checks it every minute " +
            "and switches it back off. To lock it later: Forget the plug, then Pair the power plug and choose Update and " +
            "lock it to this tablet."

    const val FIND = "Find the plug"

    /** Step 1's quiet second line (v1.28 — M65): the Shelly app's way still works. */
    const val SHELLY_STILL = "The Shelly app still works: set the plug up there, then come straight back here within about 5 minutes."
    const val UPDATE = "Update the plug"
    const val INSTALLING = "Downloading and installing (about 2 minutes)"
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

    /** One part of the update (v1.28 — M65); the install says what it does while it runs. */
    fun updateTask(task: UpdateTask, update: UpdateState, working: Boolean): String = when (task) {
        UpdateTask.RailOff -> "24 V off through its restarts"
        UpdateTask.Online -> "On the hotspot"
        UpdateTask.Offered -> "Update from Shelly" + (update.to?.let { ": $it" } ?: "")
        UpdateTask.Started -> "Update started"
        UpdateTask.Installed -> if (working && UpdateTask.Started in update.tasks && task !in update.tasks) INSTALLING else "Downloaded and installed"
        UpdateTask.Cleared -> "Setup cleared"
    }

    /** "7 of 7 answering" while the check runs. */
    fun answering(answered: Int?, total: Int): String = if (answered == null) "Reading the boards…" else "$answered of $total answering"

    const val VERIFY_PASSED = "Every board answered with the 24 V on, and none once it went off."

    // The fallback: the plug finished its setup without this tablet.
    const val FALLBACK =
        "Reset the plug: unplug it, plug it back in, and within the first minute hold its button for 10 seconds (5 seconds " +
            "only restarts it). A factory reset keeps its firmware, not its pairings or network. Then find it again: it will " +
            "be new, and this tablet sets it up from here, pairing first."
    const val JOIN = "Pair with a plug another tablet set up"
    const val JOIN_NOTE = "If the tablet the plug knows opened Admit a replacement tablet in the last 3 minutes."
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
