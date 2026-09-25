// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {
    private fun two(text: String) = Sentences.firstTwo(text)

    @Test
    fun `initials never end a sentence`() {
        assertEquals("J. S. Bach wrote it. He was German.", two("J. S. Bach wrote it. He was German. He had twenty children."))
        assertEquals(
            "C. P. E. Bach was his son. He worked in Berlin.",
            two("C. P. E. Bach was his son. He worked in Berlin. Then Hamburg."),
        )
    }

    @Test
    fun `the first two of a real extract`() {
        val bach = "Johann Sebastian Bach (31 March [O.S. 21 March] 1685 – 28 July 1750) was a German composer and musician of the " +
            "late Baroque period. He is known for his prolific output across a variety of instruments and forms. Bach's " +
            "compositions include the Brandenburg Concertos."
        assertEquals(
            "Johann Sebastian Bach (31 March [O.S. 21 March] 1685 – 28 July 1750) was a German composer and musician of the " +
                "late Baroque period. He is known for his prolific output across a variety of instruments and forms.",
            two(bach),
        )
    }

    @Test
    fun `music's abbreviations, dates and decimals stay inside the sentence`() {
        assertEquals(
            "The Nocturne Op. 9 No. 2 is by Chopin. It is in E-flat major.",
            two("The Nocturne Op. 9 No. 2 is by Chopin. It is in E-flat major. It was published in 1832."),
        )
        assertEquals("Written c. 1830 in St. Petersburg. It is short.", two("Written c. 1830 in St. Petersburg. It is short. Very."))
        assertEquals("It lasts 3.5 minutes. It is loud.", two("It lasts 3.5 minutes. It is loud. Really."))
        assertEquals("Many forms, e.g. Fugues. And suites.", two("Many forms, e.g. Fugues. And suites. And more."))
    }

    @Test
    fun `other marks, quotes and short texts`() {
        assertEquals("Why? Because.", two("Why? Because. Third."))
        assertEquals("He said \"Play.\" They did.", two("He said \"Play.\" They did. Then left."))
        assertEquals("A piece", two("A piece"))
        assertEquals("One sentence.", two("One sentence."))
        assertEquals("Spaces collapse. Here.", two("  Spaces   collapse.\nHere.  Gone. "))
        assertEquals("", two("   "))
        assertEquals("It ends in lower case. and goes on.", two("It ends in lower case. and goes on."))
    }
}
