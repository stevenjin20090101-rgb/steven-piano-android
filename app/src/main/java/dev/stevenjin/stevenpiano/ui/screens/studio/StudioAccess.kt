// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.studio

import dev.stevenjin.stevenpiano.studio.Studio

/** What a person can do on the Studio tab. */
enum class StudioAction {
    /** Typing in the idea box, and the suggestions that fill it. */
    Type,

    /** Send an idea; Options and Adjust… (the sheet composes as Send does). */
    Send,

    /** "Another like it". */
    Again,

    /** Listen to a piece Studio made. */
    Listen,

    /** Attach: transcribe a recording. */
    Attach,

    /** The Models sheet: download, cancel or remove a model. */
    Models,

    Keep,
    Discard,

    /** Take a turn out of the history. */
    DeleteTurn,

    /** Cancel a job. */
    Cancel,
}

/**
 * The kiosk rules of the Studio tab (v1.12 — M30, Steven's choice: in kiosk mode typing ideas is free). While kiosk
 * mode keeps the settings locked ([locked]), the idea box, Send, Options, "Another like it", Listen and Cancel need
 * no PIN; Attach, Models, Keep, Discard and taking a turn out of the history ask for it. The guards that make the
 * freedom safe: at most [Studio.MAX_WAITING] ideas wait (always, kiosk or not); "Not used" shows a count instead of
 * the words; the idea's own text is hidden in the history until the PIN opens the settings; and at most
 * [Studio.KIOSK_UNDECIDED] of Studio's pieces wait for the PIN (the oldest is discarded; Studio does that). No new
 * switch. Pure.
 */
object StudioAccess {
    private val GUARDED = setOf(StudioAction.Attach, StudioAction.Models, StudioAction.Keep, StudioAction.Discard, StudioAction.DeleteTurn)

    /** Whether [action] waits for the kiosk PIN. */
    fun needsPin(action: StudioAction, locked: Boolean): Boolean = locked && action in GUARDED

    /** Whether an idea may be sent now: something typed, and fewer than three waiting. */
    fun canSend(text: String, waiting: Int): Boolean = text.isNotBlank() && waiting < Studio.MAX_WAITING

    /** "Another like it" waits for room as Send does. */
    fun canAgain(waiting: Int): Boolean = waiting < Studio.MAX_WAITING

    /** Whether the history shows an idea's own words ([locked]: hidden, in their place "An idea typed here"). */
    fun showsTypedText(locked: Boolean): Boolean = !locked

    /** "Not used: unicorn, rainbow", or while locked only how many: "Not used: 2 words". Null when every word was used. */
    fun unusedLine(unused: List<String>, locked: Boolean): String? = when {
        unused.isEmpty() -> null
        locked -> "Not used: ${unused.size} ${if (unused.size == 1) "word" else "words"}"
        else -> "Not used: ${unused.joinToString(", ")}"
    }

    /** What the history shows in place of an idea's words while they are hidden. */
    const val HIDDEN = "An idea typed here"
}
