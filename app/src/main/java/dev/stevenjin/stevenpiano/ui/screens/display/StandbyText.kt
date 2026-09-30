// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.display

import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus

/**
 * The lines about the piece on the resting screen (DESIGN.md › v1.7.1). Pure.
 *
 * The description is the piece's own notes when it has any (its Wikipedia extract, or the
 * "Made in Studio · …" line of a piece Studio made), else its composer's, else nothing at all: the
 * screen never says that nothing was found. It is set as one paragraph and cut with an ellipsis
 * after [WIDE_LINES] lines on wide frames (a tablet on the piano, read from a step away) and
 * [PHONE_LINES] on phones. Wikipedia's text carries its credit, [WIKIPEDIA_CREDIT], one line under it
 * (v1.8); the app's own line (a piece made in Studio: a row with no source) none.
 */
object StandbyText {
    /** The description and, when it is Wikipedia's, the [credit] under it. */
    data class Notes(val text: String, val credit: String?)

    /** The one line under Wikipedia's text on the resting screen, as CC BY-SA 4.0 asks. */
    const val WIKIPEDIA_CREDIT = "From Wikipedia · CC BY-SA 4.0"

    /** At most this many lines of the description on wide frames. */
    const val WIDE_LINES = 6

    /** At most this many on phones. */
    const val PHONE_LINES = 4

    /** The description's line cap: [wide] is the frame's two panes (a tablet, or a phone on its side). */
    fun maxLines(wide: Boolean): Int = if (wide) WIDE_LINES else PHONE_LINES

    /**
     * The piece's notes ([piece], its `piece:<id>` row), else the composer's ([composer]), else null;
     * credited to Wikipedia when the row that gave them has a source (its page's address or title,
     * as the piece's sheet decides), and never the app's own line.
     */
    fun notes(piece: ArtworkEntity?, composer: ArtworkEntity?): Notes? = found(piece) ?: found(composer)

    /** The description alone ([notes]'s text). */
    fun description(piece: ArtworkEntity?, composer: ArtworkEntity?): String? = notes(piece, composer)?.text

    /** [text] as one paragraph: line breaks and runs of spaces become one space, so no line of the few is spent on a break. */
    fun oneParagraph(text: String): String = text.trim().replace(WHITESPACE, " ")

    /** A row's text when it was found and says something, with Wikipedia's credit when the row has a source. */
    private fun found(row: ArtworkEntity?): Notes? {
        val ok = row?.takeIf { it.status == ArtworkStatus.OK } ?: return null
        val text = ok.description?.let(::oneParagraph)?.takeIf { it.isNotEmpty() } ?: return null
        val fromWikipedia = ok.sourceUrl != null || ok.sourceTitle != null
        return Notes(text, credit = if (fromWikipedia) WIKIPEDIA_CREDIT else null)
    }

    private val WHITESPACE = Regex("\\s+")
}
