// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.net.FakeWikipedia
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkFetcherTest {
    private val wiki = FakeWikipedia()
    private val waits = mutableListOf<Long>()
    private val fetcher = ArtworkFetcher(wiki) { waits += it }

    private val portrait = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Debussy.jpg/960px-Debussy.jpg"

    @Test
    fun `a known composer is asked for by full name, with the portrait downloaded`() = runTest {
        wiki.page("Claude Debussy", "Achille Claude Debussy was a French composer.", description = "French composer (1862–1918)", image = portrait)
        val found = fetcher.fetch(ArtKey.Composer("debussy", "debussy")) as Fetched.Found
        assertEquals("Achille Claude Debussy was a French composer.", found.description)
        assertEquals("https://en.wikipedia.org/wiki/Claude_Debussy", found.sourceUrl)
        assertEquals("Claude Debussy", found.sourceTitle)
        assertArrayEquals(wiki.files[portrait], found.image)
        assertEquals(listOf("summary Claude Debussy", "download $portrait"), wiki.kinds)
    }

    @Test
    fun `other spellings of a known composer find the same page`() = runTest {
        wiki.page("Sergei Rachmaninoff", "Sergei Rachmaninoff was a Russian composer.")
        assertTrue(fetcher.fetch(ArtKey.Composer("rachmaninoff", "rachmaninow")) is Fetched.Found)
        assertEquals(listOf("summary Sergei Rachmaninoff"), wiki.kinds)
    }

    @Test
    fun `anyone else is asked for by the name shown, and only a page about music is taken`() = runTest {
        wiki.page("Hans Zimmer", "Hans Florian Zimmer is a German film score composer.", description = "German film score composer")
        assertTrue(fetcher.fetch(ArtKey.Composer("zimmer", "Hans Zimmer")) is Fetched.Found)
        wiki.page("John Smith", "John Smith was an English explorer and colonial governor.", description = "English explorer (1580–1631)")
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Composer("smith", "John Smith")))
    }

    @Test
    fun `no name, Traditional and the like are not looked up`() = runTest {
        for (display in listOf("", "   ", "Traditional", "traditional", "Anonymous", "Unknown")) {
            assertEquals(display, Fetched.NotFound, fetcher.fetch(ArtKey.Composer(display.trim().lowercase(), display)))
        }
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Composer("traditional", "xmas")))
        assertTrue(wiki.calls.isEmpty())
    }

    @Test
    fun `a disambiguation page is tried once more as the composer`() = runTest {
        wiki.page("Moritz Moszkowski", "Moritz Moszkowski may refer to:", type = "disambiguation")
        wiki.page("Moritz Moszkowski (composer)", "Moritz Moszkowski was a German composer and pianist.")
        val found = fetcher.fetch(ArtKey.Composer("moszkowski", "Moritz Moszkowski")) as Fetched.Found
        assertEquals("Moritz Moszkowski (composer)", found.sourceTitle)
        assertNull(found.image)
        assertEquals(listOf("summary Moritz Moszkowski", "summary Moritz Moszkowski (composer)"), wiki.kinds)
    }

    @Test
    fun `a disambiguation twice, or no page at all, is not found`() = runTest {
        wiki.page("Franz Liszt", "Liszt may refer to:", type = "disambiguation")
        wiki.page("Franz Liszt (composer)", "Liszt may refer to:", type = "disambiguation")
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Composer("liszt", "Franz Liszt")))
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Composer("grieg", "Edvard Grieg")))
    }

    @Test
    fun `a request that fails is a failure`() = runTest {
        wiki.page("Claude Debussy")
        wiki.failing = true
        assertTrue(fetcher.fetch(ArtKey.Composer("debussy", "Claude Debussy")) is Fetched.Failed)
    }

    @Test
    fun `asked to wait, the fetch waits as asked once and goes on`() = runTest {
        wiki.page("Claude Debussy", "Claude Debussy was a French composer.")
        wiki.busy += 2_000L
        assertTrue(fetcher.fetch(ArtKey.Composer("debussy", "Claude Debussy")) is Fetched.Found)
        assertEquals(listOf(2_000L), waits)
        assertEquals(listOf("summary Claude Debussy", "summary Claude Debussy"), wiki.kinds)
    }

    @Test
    fun `asked to wait twice running, the key is left for later`() = runTest {
        wiki.page("Claude Debussy")
        wiki.busy += listOf(3_000L, 3_000L)
        assertEquals(Fetched.Busy(3_000L), fetcher.fetch(ArtKey.Composer("debussy", "Claude Debussy")))
        assertEquals(listOf(3_000L), waits)
    }

    @Test
    fun `a wait longer than a minute is not waited out, and no Retry-After waits five seconds`() = runTest {
        wiki.page("Claude Debussy")
        wiki.busy += 3_600_000L
        assertEquals(Fetched.Busy(3_600_000L), fetcher.fetch(ArtKey.Composer("debussy", "Claude Debussy")))
        assertTrue(waits.isEmpty())
        wiki.busy += listOf(null, null)
        assertEquals(Fetched.Busy(ArtworkFetcher.DEFAULT_WAIT_MS), fetcher.fetch(ArtKey.Composer("debussy", "Claude Debussy")))
        assertEquals(listOf(ArtworkFetcher.DEFAULT_WAIT_MS), waits)
    }

    @Test
    fun `a piece takes the first hit that is not the composer and names the composer, text only`() = runTest {
        wiki.searches["Clair de lune Claude Debussy"] = listOf("Claude Debussy", "Clair de lune (poem)", "Suite bergamasque")
        wiki.page("Claude Debussy", "Claude Debussy was a French composer.")
        wiki.page("Clair de lune (poem)", "\"Clair de lune\" is a poem written by Paul Verlaine in 1869.")
        wiki.page(
            "Suite bergamasque",
            "The Suite bergamasque is a piano suite by Claude Debussy. Its third movement, Clair de lune, is among his most famous.",
            image = "https://upload.wikimedia.org/wikipedia/commons/x/xy/Score.png",
        )
        val found = fetcher.fetch(ArtKey.Piece(7, "Clair de lune", "Claude Debussy")) as Fetched.Found
        assertEquals("Suite bergamasque", found.sourceTitle)
        assertEquals("https://en.wikipedia.org/wiki/Suite_bergamasque", found.sourceUrl)
        assertTrue(found.description!!.startsWith("The Suite bergamasque is a piano suite by Claude Debussy."))
        assertNull(found.image)
        assertEquals(
            listOf("search Clair de lune Claude Debussy", "summary Clair de lune (poem)", "summary Suite bergamasque"),
            wiki.kinds,
        )
    }

    @Test
    fun `a piece searches with the composer's full name and checks the surname without accents`() = runTest {
        wiki.searches["Nocturne Op. 9 No. 2 Frédéric Chopin"] = listOf("Nocturnes, Op. 9 (Chopin)")
        wiki.page("Nocturnes, Op. 9 (Chopin)", "The Nocturnes, Op. 9 are a set of three nocturnes written by Frederic Chopin.")
        assertTrue(fetcher.fetch(ArtKey.Piece(1, "Nocturne Op. 9 No. 2", "chopin")) is Fetched.Found)
    }

    @Test
    fun `a piece with no hit that names the composer, or no composer, is not found`() = runTest {
        wiki.searches["Etude Frédéric Chopin"] = listOf("Étude", "Etude (film)")
        wiki.page("Étude", "An étude is an instrumental composition.", type = "disambiguation")
        wiki.page("Etude (film)", "Etude is a 2019 film.")
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Piece(2, "Etude", "Frédéric Chopin")))
        val before = wiki.calls.size
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Piece(3, "Etude", "")))
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Piece(4, "Silent Night", "Traditional")))
        assertEquals(before, wiki.calls.size)
    }
}
