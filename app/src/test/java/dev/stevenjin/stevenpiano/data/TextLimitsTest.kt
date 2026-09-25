// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.named
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextLimitsTest {
    @Test
    fun `text within the cap is kept as it is`() {
        val text = "Clair de lune "
        assertSame(text, TextLimits.clip(text, 200))
        assertEquals("", TextLimits.clip("", 5))
    }

    @Test
    fun `a cut counts code points and never splits a surrogate pair`() {
        val clef = "𝄞"   // 𝄞, two chars, one code point
        val text = clef.repeat(10)
        assertEquals(clef.repeat(3), TextLimits.clip(text, 3))
        assertEquals(3, TextLimits.clip(text, 3).codePointCount(0, 6))
        assertEquals("ab", TextLimits.clip("ab cd", 3))   // the space the cut leaves goes too
        assertEquals("abc", TextLimits.clip("abcdef", 3))
    }

    @Test
    fun `a piece named with megabytes of text keeps its caps, and its keys come from what is kept`() {
        val blank = PieceEntity(
            title = "", composer = "", composerKey = "", composerShort = "", collection = null, sha256 = "0", fileName = "0.mid",
            sourceName = "x.mid", sizeBytes = 1, durationMs = 1, noteCount = 1, addedAt = 0, searchText = "", titleKey = "",
        )
        val title = "Étude " + "é".repeat(1_000_000)
        val composer = ComposerNames.Name("Z".repeat(500_000), "Z".repeat(500_000), "z".repeat(500_000))
        val piece = blank.named(title, composer)
        assertEquals(TextLimits.TITLE, piece.title.length)
        assertEquals(TextLimits.COMPOSER, piece.composer.length)
        assertEquals(TextLimits.COMPOSER, piece.composerShort.length)
        assertEquals(TextLimits.COMPOSER, piece.composerKey.length)
        assertEquals(TextLimits.TITLE_KEY, piece.titleKey.length)
        assertEquals(TextKeys.fold(piece.title), piece.titleKey)
        assertEquals(TextKeys.searchText(piece.title, piece.composer), piece.searchText)
        assertEquals(true, piece.searchText.length <= TextLimits.SEARCH_TEXT)
    }
}
