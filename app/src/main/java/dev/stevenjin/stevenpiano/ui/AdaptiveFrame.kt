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
import dev.stevenjin.stevenpiano.settings.Appearance
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.StandbyCanvas
import dev.stevenjin.stevenpiano.settings.StandbyShows
import dev.stevenjin.stevenpiano.ui.components.KeyLayout
import dev.stevenjin.stevenpiano.ui.components.SplitAxis

/** How Now playing arranges its note views. */
enum class NotesLayout {
    /** The roll alone, in the plan's roll style. */
    ROLL,

    /** The score alone. */
    SCORE,

    /** Score on top, the roll below, as the split says (a third for the score at first). */
    STACKED,

    /** Score at the start, the roll at the end, as the split says (half each at first). */
    SIDE_BY_SIDE,
}

/**
 * What the View menu calls each Note display choice. The settings keep their v1.1 names (STAFF)
 * so a saved choice carries over; people see the score's name.
 */
val NoteDisplay.label: String
    get() = when (this) {
        NoteDisplay.PAPER_ROLL -> "Paper roll"
        NoteDisplay.FALLING -> "Falling notes"
        NoteDisplay.STAFF -> "Score"
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

/** What the Display page calls each Standby shows choice. */
val StandbyShows.label: String
    get() = when (this) {
        StandbyShows.ART_AND_NOTES -> "Art and notes"
        StandbyShows.PAPER_ROLL -> "Paper roll"
    }

/**
 * Now playing's note views: their arrangement and the roll's style (paper roll or falling notes). On wide frames
 * (v1.12 — M31a) also the [axis] the score and the notes share and the score's committed [split] of it (0: the
 * notes alone, 1: the score alone); a phone's plan has no axis. [artOnly] (wide frames, v1.18 — M49): neither view,
 * the cover and its controls alone, whatever the split.
 */
data class NotesPlan(val layout: NotesLayout, val rollStyle: NoteDisplay, val split: Float = 0f, val axis: SplitAxis? = null, val artOnly: Boolean = false)

/**
 * What the window's width class decides (DESIGN.md › v1.1 › Adaptive layout). Nothing else
 * changes with size. Compact (under 600 dp: phones upright) keeps v1.0's bottom bar; Medium
 * (600-840 dp: small tablets, phones on their side) and Expanded (840 dp and up: tablets on
 * their side) move the four destinations to a rail on the left. Never a rail and a bar at once.
 * The class also sets how Now playing arranges the score and the notes, how many keys the Keys
 * screen shows, how many tiles the Library's grids set side by side, which note-display choices the
 * View menu offers, and whether the Piano tab's pages open beside its hub. (Bars per system follow
 * the score's page width since v1.12, not the class: `ScoreWidth.forPage`.)
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

    /** Wide screens show the score above or beside the notes, as the split says (v1.12 — M31a). */
    val wide: Boolean get() = widthClass != WindowWidthSizeClass.Compact

    /**
     * A list with its detail beside it (DESIGN.md › v1.5): the Piano tab's hub with the open page at
     * its side, iPad Settings style, on anything wider than a phone held upright (a phone on its side
     * included). Compact widths push the page over the list instead.
     */
    val twoPane: Boolean get() = widthClass != WindowWidthSizeClass.Compact

    /**
     * The Note display choices the View menu offers: on compact widths the score is a third style;
     * on wide ones it has its own pane, so the choice is the roll's style.
     */
    val noteDisplayChoices: List<NoteDisplay>
        get() = if (wide) listOf(NoteDisplay.PAPER_ROLL, NoteDisplay.FALLING) else NoteDisplay.entries

    /**
     * Now playing's note views. Compact: one canvas, chosen by [display]. Medium: the score
     * stacked over the notes, sharing the height as [stacked] says. Expanded: side by side, sharing
     * the width as [side] says (v1.12 — M31a). A share of null is the arrangement's default (a third,
     * a half), 0 shows the notes alone and 1 the score alone. [artOnly] (v1.18 — M49) shows neither, on wide frames
     * alone: a phone shows its views below the cover whatever it says.
     */
    fun notesPlan(display: NoteDisplay, stacked: Float?, side: Float?, artOnly: Boolean = false): NotesPlan {
        if (!wide) {
            return if (display == NoteDisplay.STAFF) NotesPlan(NotesLayout.SCORE, display.rollStyle, split = 1f) else NotesPlan(NotesLayout.ROLL, display.rollStyle)
        }
        val axis = if (widthClass == WindowWidthSizeClass.Medium) SplitAxis.Stacked else SplitAxis.SideBySide
        val split = ((if (axis == SplitAxis.Stacked) stacked else side) ?: axis.defaultShare).coerceIn(0f, 1f)
        val layout = when {
            split <= 0f -> NotesLayout.ROLL
            split >= 1f -> NotesLayout.SCORE
            axis == SplitAxis.Stacked -> NotesLayout.STACKED
            else -> NotesLayout.SIDE_BY_SIDE
        }
        return NotesPlan(layout, display.rollStyle, split, axis, artOnly)
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
 * What the floating controls cover of a screen that draws beneath them (DESIGN.md › v1.5 — M16,
 * v1.9): bottom, the tab bar's column (the mini player, the bar and the navigation bar beneath it),
 * or on wide frames the navigation bar alone; start, the rail on wide frames; end, the system bars
 * and cutout there; top, the status bar, until a pane's glass header gives its own height instead
 * (the status bar's included, [dev.stevenjin.stevenpiano.ui.components.GlassHeaderPane]). Lists take
 * the top and the bottom as content padding, so they scroll under the glass at both ends and their
 * first and last rows can still rest clear of it; fixed layouts (Keys, Now playing) take it all as
 * padding, so the keyboard is never under glass. Provided by the nav host, measured, so it follows
 * the mini player as it comes and goes, and by each header, measured, so it follows the header's text.
 */
val LocalFloatingPadding = compositionLocalOf { PaddingValues(0.dp) }
