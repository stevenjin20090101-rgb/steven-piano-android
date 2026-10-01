// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.data.builtin.LibraryFixture
import dev.stevenjin.stevenpiano.data.imports.ComposerNames.Name
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerNamesTest {
    private fun n(raw: String) = ComposerNames.normalize(raw)

    @Test
    fun `short forms of a known composer take the full name and share one key`() {
        for (raw in listOf("chopin", "Chopin", "Chopin, F", "Chopin, F.", "Frédéric Chopin", "Frederic Chopin", "  frédéric   chopin ")) {
            assertEquals(raw, Name("Frédéric Chopin", "Chopin", "chopin"), n(raw))
        }
    }

    @Test
    fun `a piece made in Studio keeps its composer whole, rows included`() {
        for (raw in listOf("Made in Studio", "made in studio", "  Made  in Studio ")) {
            assertEquals(raw, Name("Made in Studio", "Made in Studio", "made in studio"), n(raw))
        }
        assertEquals("a person called Studio is still a surname", "Studio", n("Anna Studio").short)
    }

    @Test
    fun `the 26 lowercase piano-midi de folder names`() {
        val expected = mapOf(
            "albeniz" to "Isaac Albéniz", "bach" to "Johann Sebastian Bach", "balakirew" to "Mily Balakirev",
            "beethoven" to "Ludwig van Beethoven", "borodin" to "Alexander Borodin", "brahms" to "Johannes Brahms",
            "burgmueller" to "Friedrich Burgmüller", "chopin" to "Frédéric Chopin", "clementi" to "Muzio Clementi",
            "debussy" to "Claude Debussy", "godowsky" to "Leopold Godowsky", "granados" to "Enrique Granados",
            "grieg" to "Edvard Grieg", "haydn" to "Joseph Haydn", "liszt" to "Franz Liszt",
            "mendelssohn" to "Felix Mendelssohn", "moszkowski" to "Moritz Moszkowski", "mozart" to "Wolfgang Amadeus Mozart",
            "mussorgsky" to "Modest Mussorgsky", "rachmaninow" to "Sergei Rachmaninoff", "ravel" to "Maurice Ravel",
            "schubert" to "Franz Schubert", "schumann" to "Robert Schumann", "sinding" to "Christian Sinding",
            "tchaikovsky" to "Pyotr Ilyich Tchaikovsky", "xmas" to "Traditional",
        )
        assertEquals(26, expected.size)
        expected.forEach { (folder, full) ->
            assertEquals(folder, full, n(folder).display)
            assertEquals(folder, n(full).key, n(folder).key)
        }
        assertEquals("rachmaninoff", n("Rachmaninow").key)
        assertEquals("burgmuller", n("Burgmueller").key)
        assertEquals(Name("Traditional", "Traditional", "traditional"), n("Xmas"))
    }

    @Test
    fun `initials that fit take the full name, others make a separate composer`() {
        assertEquals(Name("Johann Sebastian Bach", "Bach", "bach"), n("Bach JS"))
        assertEquals(Name("Bach CPE", "Bach", "bach cpe"), n("Bach CPE"))
        assertEquals("Isaac Albéniz", n("Albeniz IMF").display)
        assertEquals("Scott Joplin", n("Joplin, S").display)
        assertEquals("Charles-Valentin Alkan", n("Alkan CV").display)
    }

    @Test
    fun `other composers keep their name and group by surname`() {
        assertEquals(Name("Abt, F", "Abt", "abt"), n("Abt, F"))
        assertEquals(Name("Anonymous", "Anonymous", "anonymous"), n("Anonymous"))
        assertEquals(Name("Leoš Janáček", "Janáček", "janacek"), n("Leoš Janáček"))
        assertEquals("saint-saens", n("Camille Saint-Saëns").key)
        assertEquals(Name("Johann Strauss II", "Strauss", "strauss"), n("Johann Strauss II"))
        assertEquals(Name("Alban Berg", "Berg", "berg"), n("alban berg"))
        assertEquals(Name("Some Body", "Body", "body"), n("some body"))
    }

    @Test
    fun `MAESTRO composer-arranger pairs group under the composer`() {
        assertEquals(Name("Franz Schubert Franz Liszt", "Schubert", "schubert"), n("Franz Schubert Franz Liszt"))
        assertEquals("bizet", n("Georges Bizet Vladimir Horowitz").key)
        assertEquals("fischer", n("Johann Christian Fischer Wolfgang Amadeus Mozart").key)
        assertEquals("rachmaninoff", n("Sergei Rachmaninoff György Cziffra").key)
        assertEquals("glinka", n("Mikhail Glinka Mily Balakirev").key)
        assertEquals("albeniz", n("Isaac Albéniz Leopold Godowsky").key)
        assertEquals("strauss", n("Johann Strauss Alfred Grünfeld").key)
        assertEquals(Name("Wolfgang Amadeus Mozart", "Mozart", "mozart"), n("Wolfgang Amadeus Mozart"))
    }

    @Test
    fun `empty composers stay unknown, mojibake is repaired first`() {
        assertEquals(Name.Unknown, n(""))
        assertEquals(Name.Unknown, n("   "))
        assertEquals("Frédéric Chopin", n("FrÃ©dÃ©ric Chopin").display)
    }

    @Test
    fun `canonical gives the full name artwork asks Wikipedia for`() {
        assertEquals("Claude Debussy", ComposerNames.canonical("debussy"))
        assertEquals("Sergei Rachmaninoff", ComposerNames.canonical("rachmaninow"))
        assertEquals("Friedrich Burgmüller", ComposerNames.canonical("burgmueller"))
        assertEquals("Antonín Dvořák", ComposerNames.canonical("dvorak"))
        assertEquals("Traditional", ComposerNames.canonical("xmas"))
        assertEquals("Frédéric Chopin", ComposerNames.canonical(n("Chopin, F.").key))
        assertEquals(null, ComposerNames.canonical("bach cpe"))
        assertEquals(null, ComposerNames.canonical("zimmer"))
        assertEquals(null, ComposerNames.canonical(""))
        for (folder in listOf("albeniz", "balakirew", "mussorgsky", "tchaikovsky", "saint-saens", "janacek")) {
            assertEquals(folder, n(folder).display, ComposerNames.canonical(n(folder).key))
        }
    }

    @Test
    fun `folding flattens accents and ligatures`() {
        assertEquals("frederic chopin", TextKeys.fold("Frédéric Chopin"))
        assertEquals("strasse", TextKeys.fold("Straße"))
        assertEquals("dvorak", TextKeys.fold("Dvořák"))
        assertEquals("lodz", TextKeys.fold("Łódź"))
        assertEquals("nocturne frederic chopin", TextKeys.searchText("Nocturne", "Frédéric Chopin"))
    }

    // v1.10.1 — M28, D3: artists' names.

    private fun a(raw: String) = ComposerNames.artist(raw)

    @Test
    fun `an artist who is a canonical composer joins that composer's group`() {
        assertEquals(Name("Claude Debussy", "Debussy", "debussy"), a("Claude Debussy"))
        assertEquals(Name("Erik Satie", "Satie", "satie"), a("Erik Satie"))
        assertEquals("a short form, as normalize reads it", Name("Claude Debussy", "Debussy", "debussy"), a("Debussy"))
        assertEquals(Name("Johann Sebastian Bach", "Bach", "bach"), a("Bach JS"))
        assertEquals("rachmaninoff", a("Rachmaninow").key)
        assertEquals("the surname after given names of the composer's own", Name("Pyotr Ilyich Tchaikovsky", "Tchaikovsky", "tchaikovsky"), a("Pyotr Tchaikovsky"))
        assertEquals("or their initials", Name("Wolfgang Amadeus Mozart", "Mozart", "mozart"), a("W. A. Mozart"))
        assertEquals("another spelling of the surname", "Sergei Rachmaninoff", a("Sergei Rachmaninov").display)
        assertEquals(Name("Traditional", "Traditional", "traditional"), a("Traditional"))
        // Namesakes are not the composer, though normalize groups them by surname.
        assertEquals(Name("Andrew Berg", "Andrew Berg", "andrew berg"), a("Andrew Berg"))
        assertEquals("berg", n("Andrew Berg").key)
        assertEquals(Name("Janis Joplin", "Janis Joplin", "janis joplin"), a("Janis Joplin"))
        assertEquals("C. P. E. Bach is not J. S. Bach", "bach cpe", a("Bach CPE").key)
        assertEquals(null, ComposerNames.canonicalOf("Franz Schubert Franz Liszt"))
        assertEquals(null, ComposerNames.canonicalOf("Johann Strauss II"))
        assertEquals(null, ComposerNames.canonicalOf(""))
    }

    @Test
    fun `anyone else keeps the name as written, keyed and shown by the whole name`() {
        assertEquals(Name("Ed Sheeran", "Ed Sheeran", "ed sheeran"), a("Ed Sheeran"))
        assertEquals("normalize would group by the surname", "sheeran", n("Ed Sheeran").key)
        assertEquals(Name("Hans Zimmer", "Hans Zimmer", "hans zimmer"), a("Hans Zimmer"))
        assertEquals(Name("The Weeknd", "The Weeknd", "the weeknd"), a("The Weeknd"))
        assertEquals(Name("Twenty One Pilots", "Twenty One Pilots", "twenty one pilots"), a("  Twenty  One   Pilots "))
        assertEquals(Name("d4vd", "d4vd", "d4vd"), a("d4vd"))
        assertEquals("as written, never title-cased", "coldplay", a("coldplay").display)
        // A name that arrives decomposed (macOS writes zip names in NFD) is composed; the key folds the accent away.
        assertEquals(Name("Beyoncé", "Beyoncé", "beyonce"), a("Beyoncé"))
        assertEquals(Name("Yann Tiersen", "Yann Tiersen", "yann tiersen"), a("Yann Tiersen"))
        assertEquals("Made in Studio stays the app's own", Name("Made in Studio", "Made in Studio", "made in studio"), a("made in studio"))
        assertEquals(Name.Unknown, a("   "))
        assertEquals("a name of punctuation alone still has a key", "!!!", a("!!!").key)
    }

    @Test
    fun `the two Armstrongs are two artists`() {
        val louis = a("Louis Armstrong")
        val craig = a("Craig Armstrong")
        assertEquals("louis armstrong", louis.key)
        assertEquals("craig armstrong", craig.key)
        assertEquals("Louis Armstrong", louis.short)
        assertEquals("normalize would have made them one", n("Louis Armstrong").key, n("Craig Armstrong").key)
    }

    @Test
    fun `one word is the whole name, key and short name alike`() {
        assertEquals(Name("Coldplay", "Coldplay", "coldplay"), a("Coldplay"))
        assertEquals(Name("Adele", "Adele", "adele"), a("Adele"))
        assertEquals(Name("C418", "C418", "c418"), a("C418"))
        assertEquals(Name("SZA", "SZA", "sza"), a("SZA"))
    }

    @Test
    fun `a joint name stays whole and apart from either artist`() {
        val joint = a("Lady Gaga & Bradley Cooper")
        assertEquals(Name("Lady Gaga & Bradley Cooper", "Lady Gaga & Bradley Cooper", "lady gaga bradley cooper"), joint)
        assertEquals("lady gaga", a("Lady Gaga").key)
        assertEquals("guns n roses", ComposerNames.artistKey("Guns N' Roses"))
    }

    @Test
    fun `a 1_10 library's composers keep the keys, names and short names they had`() {
        // Every composer of Steven's library as 1.10 imported it (its INDEX.csv, ALL-SONGS.zip's file names, the
        // Epic zip), with what normalize gave each one then: normalize is unchanged, so no key moves and no
        // artwork row is orphaned (composer_keys_1_10.csv was written by 1.10's code, at 5d4fc80).
        val rows = LibraryFixture.rows("composer_keys_1_10.csv")
        assertEquals(125, rows.size)
        for (row in rows) {
            val composer = row["composer"]!!
            assertEquals(composer, Name(row["display"]!!, row["short"]!!, row["key"]!!), n(composer))
        }
    }
}
