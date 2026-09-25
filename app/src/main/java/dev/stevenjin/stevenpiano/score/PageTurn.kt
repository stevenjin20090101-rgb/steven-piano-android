// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

/**
 * Which pages are open while the score follows the music, so the cursor is always in sight.
 *
 * One page: the cursor's page. Two pages side by side: even pages sit on the left, odd pages on the
 * right, and the cursor's page keeps its slot. The other slot shows the next page once the cursor
 * reaches the last system of its page, and the previous page before that: so while the right page
 * is being finished the left one has already turned to the page after, and vice versa, and a
 * reader's eye never waits for a turn. At the start (no page before) the other slot shows the next
 * page; at the end (no page after) the previous one.
 */
object PageTurn {
    /** The page each slot shows, slot 0 on the left; -1 for a slot with nothing to show. */
    fun pagesShown(cursorSystem: Int, systemCount: Int, systemsPerPage: Int, pages: Int): IntArray {
        val perPage = systemsPerPage.coerceAtLeast(1)
        val slots = pages.coerceIn(1, 2)
        val shown = IntArray(slots) { -1 }
        if (systemCount <= 0) return shown
        val pageCount = (systemCount + perPage - 1) / perPage
        val cursor = cursorSystem.coerceIn(0, systemCount - 1)
        val page = cursor / perPage
        if (slots == 1) {
            shown[0] = page
            return shown
        }
        val onLastSystem = cursor % perPage == perPage - 1 || cursor == systemCount - 1
        val next = page + 1
        val previous = page - 1
        val other = when {
            next < pageCount && (onLastSystem || previous < 0) -> next
            previous >= 0 -> previous
            else -> -1
        }
        shown[page % 2] = page
        shown[1 - page % 2] = other
        return shown
    }

    /** The systems each slot shows ([pagesShown] as ranges; empty for an empty slot). */
    fun visibleSystems(cursorSystem: Int, systemCount: Int, systemsPerPage: Int, pages: Int): List<IntRange> {
        val perPage = systemsPerPage.coerceAtLeast(1)
        return pagesShown(cursorSystem, systemCount, perPage, pages).map { page ->
            if (page < 0) IntRange.EMPTY else page * perPage until minOf(systemCount, (page + 1) * perPage)
        }
    }

    /**
     * The pages a reader looking around sees: [first] and, with two slots, the page after it, each
     * in its own slot (even pages left). [first] is held to the book: an even page with two slots.
     */
    fun browsing(first: Int, pageCount: Int, pages: Int): IntArray {
        val slots = pages.coerceIn(1, 2)
        val last = (pageCount - 1).coerceAtLeast(0)
        val start = first.coerceIn(0, last).let { if (slots == 2) it - it % 2 else it }
        return IntArray(slots) { slot -> (start + slot).takeIf { it <= last && pageCount > 0 } ?: -1 }
    }
}
