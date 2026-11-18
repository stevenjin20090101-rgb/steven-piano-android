// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.sort.PieceSort
import dev.stevenjin.stevenpiano.library.PackState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the app says about Steven's library pack (DESIGN.md › v1.10 — M27): the empty Library's button and
 * its line, the `+` sheet's row, the licence sheet shown before the first load, the Library's progress line
 * and the notification. One line each, in words, sentence case; nothing red. And (M82) the piece lists' order: the
 * header row's words and each row's quiet measure; the Disney section's empty listing.
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
            "${Format.count(offer.pieces, "piece", "pieces", locale)} · ${Format.size(offer.sizeBytes)} · $SOURCES · $NON_COMMERCIAL"
        }

    /**
     * The line under the library's row and the empty Library's button: the pack's facts while nothing runs, or
     * what the load is doing ("Asking for the library…", "Loading · 23 of 61 MB", "Adding the pieces…"), or
     * why it stopped.
     */
    fun line(state: PackState, offer: PackState.Offered?, locale: Locale = Locale.getDefault()): String = when (state) {
        PackState.Checking -> "Asking for the library…"
        is PackState.Downloading -> "Loading · ${Format.megabytes(state.done, state.total, locale)}"
        PackState.Importing -> "Adding the pieces…"
        is PackState.Failed -> state.line
        else -> facts(offer, locale)
    }

    /** The Library's progress line while a load runs: "Loading Steven's library · 23 of 61 MB"; null otherwise (the import shows its own). */
    fun bar(state: PackState, locale: Locale = Locale.getDefault()): String? = when (state) {
        PackState.Checking -> "Loading Steven's library…"
        is PackState.Downloading -> "Loading Steven's library · ${Format.megabytes(state.done, state.total, locale)}"
        else -> null
    }

    /** The download's progress, 0–1, while it runs; null otherwise. */
    fun progress(state: PackState): Float? =
        (state as? PackState.Downloading)?.let { if (it.total > 0) (it.done.toDouble() / it.total).toFloat().coerceIn(0f, 1f) else 0f }

    /** The notification's line: the megabytes, then the import's own count. */
    fun notificationText(state: PackState, import: ImportProgress, locale: Locale = Locale.getDefault()): String? = when (state) {
        PackState.Checking -> "Asking for the library…"
        is PackState.Downloading -> Format.megabytes(state.done, state.total, locale)
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
                "${Format.size(offer.sizeBytes)} download from Steven Piano's releases on GitHub."
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
    fun loadButton(offer: PackState.Offered?): String = if (offer == null) "Load" else "Load · ${Format.size(offer.sizeBytes)}"

    const val NOT_NOW = "Not now"

    // ---- The piece lists' order (M82) ----------------------------------------------------------------

    /** The header row's eyebrow over Pieces, a composer's pieces and a search's results. */
    const val PIECES = "Pieces"

    /** What TalkBack reads for the sort's pop-up button: "Sort pieces, Hardest". */
    fun sortDescription(sort: PieceSort): String = "Sort pieces, ${sort.label}"

    /** The Disney section's empty listing: its title and the line under it. */
    const val NO_DISNEY = "No Disney songs yet."
    const val NO_DISNEY_LINE = "Songs by Disney's composers, or from a Disney album, appear here."

    /**
     * A row's one quiet trailing value under [sort] (null under A–Z, and when there is nothing to show): Hardest "18
     * notes/s · 10 at once", Most keys at once "10 keys at once", Recently played "played 2 days ago" (at [now]), Popular
     * the plays here, "played 12 times".
     */
    fun measure(piece: PieceEntity, sort: PieceSort, now: Long, locale: Locale = Locale.getDefault()): String? = when (sort) {
        PieceSort.TITLE -> null
        PieceSort.HARDEST -> {
            val speed = piece.busiestPerSecond
            val keys = piece.mostKeys
            if (speed == null || keys == null || keys == 0) null else "${perSecond(speed, locale)} notes/s · ${Format.count(keys, locale)} at once"
        }
        PieceSort.MOST_KEYS -> piece.mostKeys?.takeIf { it > 0 }?.let { Format.count(it, "key at once", "keys at once", locale) }
        PieceSort.RECENTLY_PLAYED -> piece.lastPlayedAt?.let { playedAgo(now - it, locale) }
        PieceSort.POPULAR -> when {
            piece.playCount <= 0 -> null
            piece.playCount == 1 -> "played once"
            else -> "played ${Format.count(piece.playCount, locale)} times"
        }
    }

    /** Notes a second: whole from 10 up ("18"), one decimal below ("2.4"; "3", not "3.0"). */
    private fun perSecond(value: Double, locale: Locale): String {
        if (value >= 10) return Format.count(value.roundToInt(), locale)
        val tenths = (value * 10).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}" else String.format(locale, "%.1f", tenths / 10.0)
    }

    /** "played just now", "played 5 min ago", "played an hour ago", "played yesterday", "played 3 days ago", "played 2 months ago". */
    fun playedAgo(elapsedMs: Long, locale: Locale = Locale.getDefault()): String {
        val minutes = elapsedMs.coerceAtLeast(0) / MINUTE_MS
        val hours = minutes / 60
        val days = hours / 24
        return "played " + when {
            minutes < 1 -> "just now"
            hours < 1 -> "$minutes min ago"
            hours == 1L -> "an hour ago"
            days < 1 -> "$hours hours ago"
            days == 1L -> "yesterday"
            days < 31 -> "$days days ago"
            days < 61 -> "a month ago"
            days < 365 -> "${days / 30} months ago"
            days < 730 -> "a year ago"
            else -> "${Format.count((days / 365).toInt(), locale)} years ago"
        }
    }

    private const val MINUTE_MS = 60_000L
}
