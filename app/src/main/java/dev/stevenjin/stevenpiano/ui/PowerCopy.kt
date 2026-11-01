// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.power.FoundPlug
import dev.stevenjin.stevenpiano.power.OnHours
import dev.stevenjin.stevenpiano.power.PairState
import dev.stevenjin.stevenpiano.power.PairStep
import dev.stevenjin.stevenpiano.power.Peak
import dev.stevenjin.stevenpiano.power.PlugAdvert
import dev.stevenjin.stevenpiano.power.PlugSettings
import dev.stevenjin.stevenpiano.power.RailMessage
import dev.stevenjin.stevenpiano.power.RailPhase
import dev.stevenjin.stevenpiano.power.SwitchBot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The 24 V smart plug's words (v1.26 — M60; DESIGN.md › v1.26 — M60): the messages, Piano › Power plug and its Pair screen
 * (the SwitchBot Plug Mini since v1.36 — M72: no setup in its app, an outlet timer as the backup), and Piano › Power use.
 */
object PowerCopy {
    const val TITLE = "Power plug"

    // ---- The messages ------------------------------------------------------------------------------------------------

    const val WAKING = "Waking the piano…"
    const val RAIL_OFF = "24 V off"
    const val PIANO_SILENT = "The piano stopped answering — 24 V cut"
    const val PLUG_LOST = "Lost the power plug"

    /** v1.38 — M75: the piano woken from off, and its link never came up. */
    const val PIANO_DIDNT_START = "The piano didn't start. Check its power."

    /** The plug watch (v1.29 — M66). */
    const val SWITCHED_ON = "The power plug had been switched on by something else: switched off"
    const val SETTINGS_PUT_BACK = "The power plug's settings had been changed: put back"
    const val OK = "OK"

    /** The power watch (v1.30 — M67): "The 24 V draws 36 W with nothing playing — 24 V cut", at rail-up. */
    fun overdraw(watts: Int): String = "The 24 V draws $watts W with nothing playing — 24 V cut"

    /** The power watch (v1.31 — M68): "The 24 V draws 12 W more than usual with nothing playing — 24 V cut", at rail-up. */
    fun aboveUsual(watts: Int): String = "The 24 V draws $watts W more than usual with nothing playing — 24 V cut"

    /** "Only 6 of 7 power boards answered" (a trunk fault), and "— 24 V cut" when the rail was cut for it. */
    fun trunk(answered: Int, total: Int, cut: Boolean): String =
        (if (answered == 0) "None of the $total power boards answered" else "Only $answered of $total power boards answered") +
            if (cut) " — 24 V cut" else ""

    fun message(message: RailMessage): String = when (message) {
        RailMessage.Waking -> WAKING
        RailMessage.Off -> RAIL_OFF
        RailMessage.PianoSilent -> PIANO_SILENT
        RailMessage.PlugLost -> PLUG_LOST
        RailMessage.PianoDidntStart -> PIANO_DIDNT_START
        is RailMessage.TrunkFault -> trunk(message.answered, message.total, message.cut)
        RailMessage.SwitchedOn -> SWITCHED_ON
        RailMessage.SettingsPutBack -> SETTINGS_PUT_BACK
        is RailMessage.Overdraw -> overdraw(message.watts)
        is RailMessage.AboveUsual -> aboveUsual(message.watts)
        is RailMessage.BoardOn -> if (message.playing || message.idle == null) {
            "24 V cut: a board stayed on, ${message.above} W more than the notes explain"
        } else {
            "24 V cut: a board stayed on, ${message.above} W more than the piano's idle (${message.idle} W)"
        }
        is RailMessage.CoilOn -> "24 V cut: a key stayed on, ${message.above} W more than the piano's idle (${message.idle} W) for ${message.seconds} s"
        is RailMessage.BoardLost ->
            "24 V cut: the piano lost contact with a board" + (if (message.afterPiece) " after the piece" else "") + " (I²C errors ${message.before} → ${message.after})"
        is RailMessage.AfterPiece -> "24 V cut: ${message.above} W more than the piano's idle (${message.idle} W) after the piece"
        is RailMessage.KeysOn -> "24 V cut: the piano says ${message.keys} " + (if (message.keys == 1) "key is" else "keys are") + " still on after the piece"
    }

    /** v1.39 — M76: the plug's adverts silent for over 10 s with the rail up (amber, small; never a CUT by itself). */
    const val READING_LOST = "Power reading lost"

    // ---- v1.39 — M76: the On hours ----------------------------------------------------------------------------------

    const val ON_HOURS = "On hours"
    const val ON_HOURS_FROM = "From"
    const val ON_HOURS_TO = "To"
    const val ON_HOURS_ON_NOTE = "Within them the piano stays on and connected, so Play starts at once. Outside them it is " +
        "off: Play, Keys, Turn the piano on, Test every key and the safety check here still wake it, and the web panel can't."
    const val ON_HOURS_OFF_NOTE = "No hours: the piano comes on for each piece, from here or the web panel, and goes off after."

    /** The On hours as a line: "7:00–22:00 every day". */
    fun hours(start: Int, end: Int): String =
        "${OnHours.time(start)}–${OnHours.time(end)} every day" + if (end < start) " (past midnight)" else ""

    /** Remote starts outside the On hours: "The piano is off until 7:00". */
    fun offUntil(hours: OnHours): String = "The piano is off until ${hours.startText}"

    /**
     * The piano asleep with its plug, idle (the tablet's piano line, the System fact, the panel): "Piano off until 7:00 ·
     * press Play here to wake it" outside the On hours; with none, or within them after a fault, "Piano off · press Play
     * here to wake it".
     */
    fun asleep(hours: OnHours?, outside: Boolean): String =
        if (hours != null && outside) "Piano off until ${hours.startText} · press Play here to wake it" else "Piano off · press Play here to wake it"

    /**
     * Now playing's power line (v1.39 — M76): "12.3 W now · peak 145.0 W this piece", or "— W" without a reading under
     * 3 s old; the peak left out before one this piece.
     */
    fun nowLine(now: Double?, peak: Double?): String =
        if (now == null) "— W" else "${watts(now)} now" + (peak?.let { " · peak ${watts(it)} this piece" } ?: "")

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

    /** v1.36 — M72: the SwitchBot, with no timer of its own, and the outlet timer behind it. */
    const val EXPLANATION =
        "The 24 V supply for the piano's keys can hang on a Bluetooth smart plug, a SwitchBot Plug Mini. This tablet then " +
            "keeps it on only while something plays and the piano answers with its power boards, and switches it off after " +
            "every piece. The plug has no timer of its own: if the tablet stops, the 24 V stays on, so keep an outlet timer " +
            "behind it as the backup."
    const val PAIR = "Pair the SwitchBot plug"
    const val PAIR_NOTE = "About a minute, all from this tablet. Only the piano's 24 V supply goes on the plug."

    /** v1.36 — M72: after the update cleared a Shelly plug's pairing. */
    const val SHELLY_CLEARED = "This version works with a SwitchBot plug: the Shelly plug's pairing was cleared. Pair the SwitchBot plug."

    const val SWITCH = "Switch the 24 V with the plug"
    const val SWITCH_ON_NOTE = "The 24 V comes on for each piece, Test every key, the Keys tab and updates, and goes off after."
    const val SWITCH_OFF_NOTE = "The app leaves the plug alone: to play without it, plug the 24 V supply into a wall socket."

    /** v1.38 — M75: Piano › Power plug's manual switch for the 24 V (and the piano's controller with it). */
    const val TURN_ON = "Turn the piano on"
    const val TURN_OFF = "Turn the piano off"
    const val MANUAL_OFF_NOTE = "Plays still turn it on by themselves. On, it stays on until 30 minutes pass without a note."

    /** The manual switch's state: "On · turns off in 28 min" (the minutes rounded up, 1 at least). */
    fun manualOn(msLeft: Long): String = "On · turns off in ${((msLeft + 59_999) / 60_000).coerceAtLeast(1)} min"

    const val STATE = "The plug"
    const val OUTPUT = "24 V"
    const val DRAW = "The supply draws"
    const val NOT_SEEN = "Not seen"
    const val READING = "Reading…"

    const val FORGET = "Forget the plug"
    const val FORGET_NOTE = "The 24 V goes off and the app stops switching it."
    const val FORGET_TITLE = "Forget the power plug?"
    const val FORGET_TEXT =
        "The 24 V goes off now. It stays off until the plug is paired again, or the supply moves to a wall socket."
    const val FORGET_ACTION = "Forget"
    const val WELDED = "After the 24 V was cut the plug still reads on: its relay may be stuck. Unplug the supply and check the plug."
    const val WELDED_SHORT = "The plug's relay may be stuck on"

    /** The plug's switch as its adverts say (v1.36 — M72): "On", "Off", or "Not seen" for none in 10 s. */
    fun output(seen: PlugAdvert?): String = when {
        seen == null -> NOT_SEEN
        seen.on -> "On"
        else -> "Off"
    }

    /** A draw: "6.4 W"; "—" for none. */
    fun watts(watts: Double?): String = watts?.let { String.format(Locale.ROOT, "%.1f W", it) } ?: "—"

    /** The System page's and the panel's fact (v1.36 — M72): "SwitchBot plug · off · 0.0 W", "… · on · 6.4 W", "… · not seen". */
    fun fact(seen: PlugAdvert?): String =
        if (seen == null) "$SWITCHBOT · not seen" else "$SWITCHBOT · " + (if (seen.on) "on" else "off") + " · " + watts(seen.watts)

    const val SWITCHBOT = "SwitchBot plug"

    // ---- The Pair screen (v1.36 — M72) --------------------------------------------------------------------------------

    fun stepTitle(step: PairStep): String = when (step) {
        PairStep.Find -> "Find the plug"
        PairStep.Check -> "Check it clicks"
        PairStep.Done -> "Paired"
    }

    /** What to do first, before Find: the plug in the wall, the supply in it, nothing in the SwitchBot app, the outlet timer. */
    const val PLUG_IN = "Plug the SwitchBot into the wall, and the piano's 24 V supply into it."
    const val NOT_IN_APP = "Don't add it to the SwitchBot app: this tablet talks to it directly."
    const val OUTLET_TIMER =
        "Keep an outlet timer as the backup: the plug has no timer of its own, so if the tablet stops, the 24 V stays on."

    fun stepNote(step: PairStep, pair: PairState): String = when (step) {
        PairStep.Find -> "The tablet looks for SwitchBot plugs nearby for 15 seconds, the strongest signal first. Tap yours: " +
            "the last 4 characters of its MAC are on its label."
        PairStep.Check -> if (pair.clicked) CLICKED else
            "The tablet connects to it and switches it off, on and off again: listen for the clicks."
        PairStep.Done -> "The 24 V now follows the music."
    }

    const val CLICKED = "The plug clicked on and off."
    const val FIND = "Find the plug"
    const val SEARCH = "Search again"
    const val SEARCHING = "Searching…"
    const val START_OVER = "Start over"

    /** A plug the search found: "SwitchBot Plug Mini (US) …47CA". */
    fun found(plug: FoundPlug): String = "SwitchBot ${plug.model} …${SwitchBot.lastFour(plug.mac)}"

    // ---- Piano › Power use (v1.36 — M72) -------------------------------------------------------------------------------

    const val USE_TITLE = "Power use"
    const val USE_NOW = "Now"
    const val USE_PEAK = "Peak"
    const val USE_ENERGY = "Energy"
    const val USE_TODAY = "Today"
    const val USE_MONTH = "This month"
    const val USE_ALL_TIME = "Since pairing"
    const val USE_LINE = "The last 10 minutes"
    const val USE_NOTE = "Measured by the SwitchBot plug: the piano's 24 V supply only."
    const val USE_NO_PLUG = "No plug paired: pair the SwitchBot plug on Power plug to see the piano's power use."
    const val USE_OPEN = "Watts now, peaks and energy"

    /** Power use's row on the hub: "Not paired", or the watts now ("12.3 W"; "—" with no advert for 10 s). */
    fun useHub(settings: PlugSettings?, seen: PlugAdvert?): String = if (settings?.pairing == null) NOT_PAIRED else watts(seen?.watts)

    /** Energy: "0.042 kWh" (3 decimals) from watt-hours. */
    fun kwh(wh: Double): String = String.format(Locale.ROOT, "%.3f kWh", wh / 1_000.0)

    /** A peak and when: "14.2 W · 14:05" today, "14.2 W · 3 Nov 14:05" another day; "—" before one. */
    fun peak(peak: Peak?, now: Long, zone: ZoneId): String {
        peak ?: return "—"
        val at = Instant.ofEpochMilli(peak.at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate() == at.toLocalDate()
        return watts(peak.watts) + " · " + at.format(if (today) TIME else DAY_TIME)
    }

    private val TIME = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)
    private val DAY_TIME = DateTimeFormatter.ofPattern("d MMM H:mm", Locale.ENGLISH)

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
