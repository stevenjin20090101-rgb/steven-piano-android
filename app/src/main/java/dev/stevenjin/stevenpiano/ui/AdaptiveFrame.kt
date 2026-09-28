// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.score.ScoreWidth
import dev.stevenjin.stevenpiano.settings.Appearance
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.StandbyCanvas
import dev.stevenjin.stevenpiano.settings.WideLayout
import dev.stevenjin.stevenpiano.ui.components.KeyLayout

/** How Now playing arranges its note views. */
enum class NotesLayout {
    /** The roll alone, in the plan's roll style. */
    ROLL,

    /** The score alone. */
    SCORE,

    /** Score on top (a third of the height), the roll below (two thirds). */
    STACKED,

    /** Score on the left, the roll on the right, equal widths. */
    SIDE_BY_SIDE,
}

/**
 * What the Piano tab calls each Note display choice. The settings keep their v1.1 names (STAFF)
 * so a saved choice carries over; people see the score's name.
 */
val NoteDisplay.label: String
    get() = when (this) {
        NoteDisplay.PAPER_ROLL -> "Paper roll"
        NoteDisplay.FALLING -> "Falling notes"
        NoteDisplay.STAFF -> "Score"
    }

/** What the Piano tab calls each Wide layout choice (saved under the v1.1 names). */
val WideLayout.label: String
    get() = when (this) {
        WideLayout.STAFF_AND_NOTES -> "Score and notes"
        WideLayout.NOTES_ONLY -> "Notes only"
        WideLayout.STAFF_ONLY -> "Score only"
    }

/** What the Display page calls each Appearance choice. */
val Appearance.label: String
    get() = when (this) {
        Appearance.SYSTEM -> "Follow system"
        Appearance.LIGHT -> "Light"
        Appearance.DARK -> "Dark"
    }

/** What the Display page calls each Standby canvas choice. */
val StandbyCanvas.label: String
    get() = when (this) {
        StandbyCanvas.BLACK -> "Black"
        StandbyCanvas.INK -> "Same as the app"
    }

/** Now playing's note views: their arrangement and the roll's style (paper roll or falling notes). */
data class NotesPlan(val layout: NotesLayout, val rollStyle: NoteDisplay)

/**
 * What the window's width class decides (DESIGN.md › v1.1 › Adaptive layout). Nothing else
 * changes with size. Compact (under 600 dp: phones upright) keeps v1.0's bottom bar; Medium
 * (600-840 dp: small tablets, phones on their side) and Expanded (840 dp and up: tablets on
 * their side) move the four destinations to a rail on the left. Never a rail and a bar at once.
 * The class also sets how Now playing arranges the score and the notes, how many bars a system of
 * the score holds, how many keys the Keys screen shows, how many tiles the Library's grids set side
 * by side, which note-display choices the Piano tab offers, and whether the Piano tab's pages open
 * beside its hub.
 *
 * A phone on its side is often 840 dp wide or more, but only 360-480 dp tall: an expanded width
 * over a compact height ([height]) counts as medium, so landscape phones get the medium layout,
 * as the design says, rather than 49 slivers of keys and two views 400 dp tall.
 */
@Immutable
class AppFrame(width: WindowWidthSizeClass, height: WindowHeightSizeClass = WindowHeightSizeClass.Medium) {
    /** The width class the frame follows. */
    val widthClass: WindowWidthSizeClass =
        if (width == WindowWidthSizeClass.Expanded && height == WindowHeightSizeClass.Compact) WindowWidthSizeClass.Medium else width

    /** The navigation rail on the left instead of the bottom bar. */
    val rail: Boolean get() = widthClass != WindowWidthSizeClass.Compact

    /**
     * White keys the Keys screen shows at once: two octaves and the C that closes them (Compact),
     * about four octaves (Medium), or all 49 (Expanded), which needs no scrolling.
     */
    val keysVisibleWhites: Int
        get() = when (widthClass) {
            WindowWidthSizeClass.Compact -> COMPACT_WHITES
            WindowWidthSizeClass.Medium -> MEDIUM_WHITES
            else -> KeyLayout.WHITE_KEYS
        }

    /** Columns in the Playlists and Composers grids: 2 on phones, 3 at medium widths, 4 on tablets on their side. */
    val tileColumns: Int
        get() = when (widthClass) {
            WindowWidthSizeClass.Compact -> 2
            WindowWidthSizeClass.Medium -> 3
            else -> 4
        }

    /** Whether the Keys screen can scroll, and so shows the mini-map and the octave buttons. */
    val keysScroll: Boolean get() = keysVisibleWhites < KeyLayout.WHITE_KEYS

    /** Bars in a system of the score: 2 on phones, 3 at medium widths, 4 on tablets on their side. */
    val scoreWidth: ScoreWidth
        get() = when (widthClass) {
            WindowWidthSizeClass.Compact -> ScoreWidth.COMPACT
            WindowWidthSizeClass.Medium -> ScoreWidth.MEDIUM
            else -> ScoreWidth.EXPANDED
        }

    /** Wide screens show the score beside or above the notes, as the Wide layout preference says. */
    val wide: Boolean get() = widthClass != WindowWidthSizeClass.Compact

    /**
     * A list with its detail beside it (DESIGN.md › v1.5): the Piano tab's hub with the open page at
     * its side, iPad Settings style, on anything wider than a phone held upright (a phone on its side
     * included). Compact widths push the page over the list instead.
     */
    val twoPane: Boolean get() = widthClass != WindowWidthSizeClass.Compact

    /**
     * The Note display choices the Piano tab offers: on compact widths the score is a third style;
     * on wide ones it has its own place, so the choice is the roll's style.
     */
    val noteDisplayChoices: List<NoteDisplay>
        get() = if (wide) listOf(NoteDisplay.PAPER_ROLL, NoteDisplay.FALLING) else NoteDisplay.entries

    /**
     * Now playing's note views. Compact: one canvas, chosen by [display]. Medium: the score
     * stacked over the notes. Expanded: side by side. Wide screens follow [wideLayout].
     */
    fun notesPlan(display: NoteDisplay, wideLayout: WideLayout): NotesPlan {
        val layout = when {
            !wide -> if (display == NoteDisplay.STAFF) NotesLayout.SCORE else NotesLayout.ROLL
            wideLayout == WideLayout.NOTES_ONLY -> NotesLayout.ROLL
            wideLayout == WideLayout.STAFF_ONLY -> NotesLayout.SCORE
            widthClass == WindowWidthSizeClass.Medium -> NotesLayout.STACKED
            else -> NotesLayout.SIDE_BY_SIDE
        }
        return NotesPlan(layout, display.rollStyle)
    }

    override fun equals(other: Any?): Boolean = other is AppFrame && other.widthClass == widthClass

    override fun hashCode(): Int = widthClass.hashCode()

    override fun toString(): String = "AppFrame($widthClass)"

    companion object {
        const val COMPACT_WHITES = 15
        const val MEDIUM_WHITES = 29
    }
}

/** The frame the screens are in, provided by the nav host. */
val LocalAppFrame = staticCompositionLocalOf { AppFrame(WindowWidthSizeClass.Compact) }

/**
 * What the floating controls cover of a screen that draws beneath them (DESIGN.md › v1.5 — M16):
 * bottom, the tab bar's column (the mini player, the bar and the navigation bar beneath it), or on
 * wide frames the navigation bar alone; start, the rail on wide frames; end, the system bars and
 * cutout there. The top inset is the nav host's. Lists take the bottom as content padding, so
 * they scroll under the glass and their last row can still rise above it; fixed layouts (Keys, Now
 * playing) take it all as padding, so the keyboard is never under glass. Provided by the nav host,
 * measured, so it follows the mini player as it comes and goes.
 */
val LocalFloatingPadding = compositionLocalOf { PaddingValues(0.dp) }
