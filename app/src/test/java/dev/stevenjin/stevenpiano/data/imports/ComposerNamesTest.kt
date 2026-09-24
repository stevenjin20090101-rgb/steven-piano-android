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
    fun `folding flattens accents and ligatures`() {
        assertEquals("frederic chopin", TextKeys.fold("Frédéric Chopin"))
        assertEquals("strasse", TextKeys.fold("Straße"))
        assertEquals("dvorak", TextKeys.fold("Dvořák"))
        assertEquals("lodz", TextKeys.fold("Łódź"))
        assertEquals("nocturne frederic chopin", TextKeys.searchText("Nocturne", "Frédéric Chopin"))
    }
}
