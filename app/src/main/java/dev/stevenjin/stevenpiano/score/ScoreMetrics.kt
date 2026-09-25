// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import kotlin.math.floor
import kotlin.math.max

/** The window's width as the score counts it: bars per system follow it (DESIGN.md › v1.2 › Score). */
enum class ScoreWidth(val barsPerSystem: Int) {
    /** Phones upright. */
    COMPACT(2),

    /** Small tablets, tablets upright, phones on their side. */
    MEDIUM(3),

    /** Tablets on their side. */
    EXPANDED(4),
}

/**
 * The score's geometry for one panel, in pixels: how many pages sit side by side and how large
 * they are, how many bars a system holds and how many systems a page, and the staff's own sizes.
 * A page's systems are spread down it evenly; each keeps one bar-number line ([numberHeight],
 * the eyebrow's line height, which grows with the font scale) above its treble staff, so the
 * numbers never touch the lines. [headWidth] and [clefWidth] are the glyphs as measured on the
 * device; other glyph widths follow from the staff space (SMuFL fonts are four spaces to the em).
 * Pure, so the layout engine and its tests need no Android.
 */
data class ScoreMetrics(
    val pages: Int,
    val pageWidth: Float,
    val pageHeight: Float,
    /** Between two pages side by side. */
    val pageGap: Float,
    val barsPerSystem: Int,
    val systemsPerPage: Int,
    /** Staff line spacing: 6 dp. */
    val space: Float,
    /** From the treble's bottom line to the bass's top line: 40 dp. */
    val staveGap: Float,
    /** From one system's bass staff to the next system's bar-number line: 32 dp, room for ledger lines. */
    val systemGap: Float,
    val numberHeight: Float,
    /** Left and right of a page's systems. */
    val marginX: Float,
    val headWidth: Float,
    val clefWidth: Float,
    /** Pixels per dp. */
    val density: Float,
    /** Where the first system's bar-number line starts, and from one system's to the next's. */
    val firstSystemTop: Float,
    val systemPitch: Float,
) {
    /** One staff: four spaces. */
    val staffHeight: Float get() = 4 * space

    /** Treble, gap, bass: 88 dp. */
    val grandStaffHeight: Float get() = 2 * staffHeight + staveGap

    /** The y of the treble staff's top line of the system in [row] (0 at the top of a page). */
    fun staffTop(row: Int): Float = firstSystemTop + row * systemPitch + numberHeight

    /** The x of the page shown in [slot] (0 on the left). */
    fun slotLeft(slot: Int): Float = slot * (pageWidth + pageGap)

    companion object {
        /** A score panel this wide (in dp) or wider shows two pages side by side. */
        const val TWO_PAGES_DP = 840f
        const val SPACE_DP = 6f
        const val STAVE_GAP_DP = 40f
        const val SYSTEM_GAP_DP = 32f
        const val PAGE_GAP_DP = 16f
        const val MARGIN_DP = 12f

        /** Above the first system of a page, and below the last (room for the lowest ledger lines). */
        const val PAD_TOP_DP = 8f
        const val PAD_BOTTOM_DP = 16f

        /** The eyebrow's line height at font scale 1. */
        const val NUMBER_HEIGHT_DP = 16f

        /**
         * The metrics for a panel of [panelWidthPx] × [panelHeightPx] in a window [width] wide, at
         * [density] pixels per dp. [numberHeight] is the bar number's line height in pixels.
         */
        fun forPanel(
            width: ScoreWidth,
            panelWidthPx: Float,
            panelHeightPx: Float,
            density: Float,
            headWidth: Float,
            clefWidth: Float,
            numberHeight: Float = NUMBER_HEIGHT_DP * density,
        ): ScoreMetrics {
            val pages = if (panelWidthPx / density >= TWO_PAGES_DP) 2 else 1
            val pageGap = if (pages == 2) PAGE_GAP_DP * density else 0f
            val space = SPACE_DP * density
            val staveGap = STAVE_GAP_DP * density
            val systemGap = SYSTEM_GAP_DP * density
            val padTop = PAD_TOP_DP * density
            val padBottom = PAD_BOTTOM_DP * density
            val block = numberHeight + 8 * space + staveGap
            val systems = max(1, floor((panelHeightPx - padTop - padBottom + systemGap) / (block + systemGap)).toInt())
            // Spread the systems down the page: what is left over goes evenly above, between and below them.
            val free = panelHeightPx - padTop - padBottom - (systems * block + (systems - 1) * systemGap)
            val extra = if (free > 0f) free / (systems + 1) else 0f
            val firstTop = if (free >= 0f) padTop + extra else (panelHeightPx - block) / 2
            return ScoreMetrics(
                pages = pages,
                pageWidth = (panelWidthPx - pageGap * (pages - 1)) / pages,
                pageHeight = panelHeightPx,
                pageGap = pageGap,
                barsPerSystem = width.barsPerSystem,
                systemsPerPage = systems,
                space = space,
                staveGap = staveGap,
                systemGap = systemGap,
                numberHeight = numberHeight,
                marginX = MARGIN_DP * density,
                headWidth = headWidth,
                clefWidth = clefWidth,
                density = density,
                firstSystemTop = firstTop,
                systemPitch = block + systemGap + extra,
            )
        }
    }
}
