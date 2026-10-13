// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.ble.LOCKED_OUT_COPY
import dev.stevenjin.stevenpiano.ble.NOT_ACCEPTED_COPY
import dev.stevenjin.stevenpiano.piano.LockPhase
import dev.stevenjin.stevenpiano.piano.LockWord
import dev.stevenjin.stevenpiano.piano.PianoLock

/** The piano's Bluetooth lock in words (v1.25 — M58; firmware 2.1.0, `firmware/docs/BLE_LOCK.md`): the Firmware page's rows. */
object LockCopy {
    /** The row that names the lock, and its search label. */
    const val LOCK = "Bluetooth lock"

    const val LOCKED_TO_THIS = "Locked to this tablet"
    const val OPEN = "Open: any device can claim it"
    const val PAIRING = "Pairing open for 5 min"
    const val LOCKED_TO_ANOTHER = "Locked to another device"
    const val CHECKING = "Checking this tablet's key…"

    /** Let another device pair: `lock pairwindow`. */
    const val PAIR = "Let another device pair (5 min)"
    const val PAIR_NOTE = "One more tablet or phone may claim the piano within 5 minutes. The piano's own screen can open this too."

    /** Unlock the piano: `lock forget`, after the question. */
    const val UNLOCK = "Unlock the piano"
    const val UNLOCK_NOTE = "Forgets every device's key."
    const val UNLOCK_TITLE = "Unlock the piano?"
    const val UNLOCK_TEXT = "Any device in range could then claim it. The tablet claims it again when it next connects."
    const val UNLOCK_ACTION = "Unlock"

    /** Steven's guest switch: `lock guest 60`, and off `lock guest 0`. */
    const val GUEST = "Let anyone play for 1 hour"
    const val GUEST_NOTE = "Others can play notes and the pedal, but not change settings or update."
    const val GUEST_MINUTES = 60

    /** What the lock is, as the row says it; null before the piano said (or with no lock). */
    fun state(lock: PianoLock): String? = when {
        lock.phase.readOnly -> LOCKED_TO_ANOTHER
        lock.phase == LockPhase.Proving -> CHECKING
        !lock.present -> null
        lock.piano == LockWord.Open -> OPEN
        lock.piano == LockWord.Pairing -> PAIRING
        lock.piano == LockWord.Claimed -> LOCKED_TO_THIS
        else -> null
    }

    /** Why the settings can't be changed, under the connection card and atop each piano page; null when they can. */
    fun readOnly(lock: PianoLock): String? = when (lock.phase) {
        LockPhase.LockedOut -> LOCKED_OUT_COPY
        LockPhase.NotAccepted -> NOT_ACCEPTED_COPY
        else -> null
    }

    /** The guest switch's note: what it lets others do, then the time left while it is on ("52 min left"). */
    fun guestNote(secondsLeft: Long): String = if (secondsLeft <= 0) GUEST_NOTE else "$GUEST_NOTE ${timeLeft(secondsLeft)}."

    /** "52 min left", rounded up to the minute; "less than a minute left" in its last minute. */
    fun timeLeft(secondsLeft: Long): String = when {
        secondsLeft < SECONDS_PER_MINUTE -> "Less than a minute left"
        else -> "${(secondsLeft + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE} min left"
    }

    /** The piano's own words for a lock command it refused, as the settings' banners say them. */
    fun said(text: String): String = if (text.startsWith("The ")) text else "The piano said: $text"

    private const val SECONDS_PER_MINUTE = 60L
}
