// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.library.PackState
import java.util.Locale

/**
 * What the app says about Steven's library pack (DESIGN.md › v1.10 — M27): the empty Library's button and
 * its line, the `+` sheet's row, the licence sheet shown before the first load, the Library's progress line
 * and the notification. One line each, in words, sentence case; nothing red.
 */
object LibraryCopy {
    const val LOAD = "Load Steven's library"
    const val UPDATE = "Update the library"
    const val SOURCES = "MAESTRO, piano-midi.de, Mutopia"
    const val NON_COMMERCIAL = "for non-commercial use"
    const val NOTIFICATION_TITLE = "Loading Steven's library"

    /** The `+` sheet's row when a newer pack is on offer: "Update the library · 2 new pieces" (no count when none is known). */
    fun updateLabel(newPieces: Int?): String =
        if (newPieces == null || newPieces <= 0) UPDATE else "$UPDATE · ${Format.count(newPieces, "new piece", "new pieces")}"

    /** "1,726 pieces · 61 MB · MAESTRO, piano-midi.de, Mutopia · for non-commercial use"; the sources alone until the pack is known. */
    fun facts(offer: PackState.Offered?, locale: Locale = Locale.getDefault()): String =
        if (offer == null) {
            "$SOURCES · $NON_COMMERCIAL"
        } else {
            "${Format.count(offer.pieces, "piece", "pieces", locale)} · ${StudioCopy.size(offer.sizeBytes)} · $SOURCES · $NON_COMMERCIAL"
        }

    /**
     * The line under the library's row and the empty Library's button: the pack's facts while nothing runs, or
     * what the load is doing ("Asking for the library…", "Loading · 23 of 61 MB", "Adding the pieces…"), or
     * why it stopped.
     */
    fun line(state: PackState, offer: PackState.Offered?, locale: Locale = Locale.getDefault()): String = when (state) {
        PackState.Checking -> "Asking for the library…"
        is PackState.Downloading -> "Loading · ${StudioCopy.megabytes(state.done, state.total, locale)}"
        PackState.Importing -> "Adding the pieces…"
        is PackState.Failed -> state.line
        else -> facts(offer, locale)
    }

    /** The Library's progress line while a load runs: "Loading Steven's library · 23 of 61 MB"; null otherwise (the import shows its own). */
    fun bar(state: PackState, locale: Locale = Locale.getDefault()): String? = when (state) {
        PackState.Checking -> "Loading Steven's library…"
        is PackState.Downloading -> "Loading Steven's library · ${StudioCopy.megabytes(state.done, state.total, locale)}"
        else -> null
    }

    /** The download's progress, 0–1, while it runs; null otherwise. */
    fun progress(state: PackState): Float? =
        (state as? PackState.Downloading)?.let { if (it.total > 0) (it.done.toDouble() / it.total).toFloat().coerceIn(0f, 1f) else 0f }

    /** The notification's line: the megabytes, then the import's own count. */
    fun notificationText(state: PackState, import: ImportProgress, locale: Locale = Locale.getDefault()): String? = when (state) {
        PackState.Checking -> "Asking for the library…"
        is PackState.Downloading -> StudioCopy.megabytes(state.done, state.total, locale)
        PackState.Importing -> if (import.finished) "Adding the pieces…" else ImportCopy.running(import)
        else -> null
    }

    // ---- The licence sheet, before the first load ---------------------------------------------------

    const val SHEET_TITLE = "Steven's library"
    const val CREDITS = "Credits"

    /** Under the title: what the pack is and where it comes from. */
    fun sheetIntro(offer: PackState.Offered?, locale: Locale = Locale.getDefault()): String =
        if (offer == null) {
            "Piano pieces from three open collections, downloaded from Steven Piano's releases on GitHub."
        } else {
            "${Format.count(offer.pieces, "piano piece", "piano pieces", locale)} from three open collections, a " +
                "${StudioCopy.size(offer.sizeBytes)} download from Steven Piano's releases on GitHub."
        }

    /** One collection's credit: its name in Body, then who made it and its licence. */
    class Credit(val name: String, val line: String)

    /** The three collections' credits, as their licences ask (the library's README.md, and AUTHORS). */
    val CREDIT_LINES = listOf(
        Credit(
            "MAESTRO v3.0.0 · Google Magenta",
            "Curtis Hawthorne et al., “Enabling Factorized Piano Music Modeling and Generation with the MAESTRO Dataset”, ICLR 2019. CC BY-NC-SA 4.0.",
        ),
        Credit("piano-midi.de", "Bernd Krueger, www.piano-midi.de. CC BY-SA 3.0 Germany."),
        Credit("The Mutopia Project", "www.mutopiaproject.org. Public domain."),
    )

    /** The sheet's last line, before its buttons. */
    const val NON_COMMERCIAL_NOTE = "For non-commercial use: MAESTRO's performances may not be sold or used for profit. " +
        "The credits stay with the pieces, in the pack's README."

    /** The sheet's button: "Load · 61 MB". */
    fun loadButton(offer: PackState.Offered?): String = if (offer == null) "Load" else "Load · ${StudioCopy.size(offer.sizeBytes)}"

    const val NOT_NOW = "Not now"
}
