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

    // v1.10.1 — M28, D5: artists.

    private val photo = "https://upload.wikimedia.org/wikipedia/commons/thumb/q/qq/Queen.jpg/960px-Queen.jpg"

    @Test
    fun `a band whose name is a disambiguation page is found as the band`() = runTest {
        wiki.page("Queen", "Queen or QUEEN may refer to:", type = "disambiguation")
        wiki.page("Queen (band)", "Queen are a British rock band formed in London in 1970.", description = "British rock band", image = photo)
        val found = fetcher.fetch(ArtKey.Composer("queen", "Queen")) as Fetched.Found
        assertEquals("Queen (band)", found.sourceTitle)
        assertArrayEquals(wiki.files[photo], found.image)
        assertEquals(listOf("summary Queen", "summary Queen (band)", "download $photo"), wiki.kinds)
    }

    @Test
    fun `a name whose page is about something else is tried as a band, then a singer`() = runTest {
        wiki.page("Passenger", "A passenger is a person who travels in a vehicle without operating it.", description = "person who travels in a vehicle")
        wiki.page("Passenger (singer)", "Michael David Rosenberg, known as Passenger, is an English singer-songwriter.", description = "English singer-songwriter")
        assertEquals("Passenger (singer)", (fetcher.fetch(ArtKey.Composer("passenger", "Passenger")) as Fetched.Found).sourceTitle)
        assertEquals(listOf("summary Passenger", "summary Passenger (band)", "summary Passenger (singer)"), wiki.kinds)
    }

    @Test
    fun `performers are recognised from the description, else the extract's first sentence`() = runTest {
        wiki.page("C418", "Daniel Rosenfeld, known as C418, is an American musician and record producer. He made Minecraft's music.")
        assertTrue(fetcher.fetch(ArtKey.Composer("c418", "C418")) is Fetched.Found)
        wiki.page("Laufey", "Laufey is an Icelandic singer-songwriter.", description = null)
        assertTrue(fetcher.fetch(ArtKey.Composer("laufey", "Laufey")) is Fetched.Found)
        wiki.page("Twenty One Pilots", "Twenty One Pilots is an American musical duo from Columbus, Ohio.", description = "American musical duo")
        assertTrue(fetcher.fetch(ArtKey.Composer("twenty one pilots", "Twenty One Pilots")) is Fetched.Found)
        wiki.page("Yoko Shimomura", "Yoko Shimomura is a Japanese video game composer and pianist.", description = "Japanese video game composer")
        assertTrue("a performer named beside a work", fetcher.fetch(ArtKey.Composer("yoko shimomura", "Yoko Shimomura")) is Fetched.Found)
    }

    @Test
    fun `a company is not found and costs at most five lookups`() = runTest {
        wiki.page("Nintendo", "Nintendo Co., Ltd. is a Japanese multinational video game company headquartered in Kyoto.", description = "Japanese video game company")
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Composer("nintendo", "Nintendo")))
        assertEquals(
            listOf("summary Nintendo", "summary Nintendo (band)", "summary Nintendo (singer)", "summary Nintendo (musician)", "summary Nintendo (composer)"),
            wiki.kinds,
        )
    }

    @Test
    fun `a work's page never stands for the singer its extract names`() = runTest {
        wiki.page("A Star Is Born", "A Star Is Born is the soundtrack album to the 2018 film, recorded by American singer Lady Gaga.", description = "2018 soundtrack album")
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Composer("a star is born", "A Star Is Born")))
    }

    @Test
    fun `a joint name that finds nothing is looked up as its first name`() = runTest {
        wiki.page("Lady Gaga", "Stefani Joanne Angelina Germanotta, known as Lady Gaga, is an American singer, songwriter and actress.", description = "American singer (born 1986)", image = photo)
        val found = fetcher.fetch(ArtKey.Composer("lady gaga bradley cooper", "Lady Gaga & Bradley Cooper")) as Fetched.Found
        assertEquals("Lady Gaga", found.sourceTitle)
        assertEquals(listOf("summary Lady Gaga & Bradley Cooper", "summary Lady Gaga", "download $photo"), wiki.kinds)
        assertEquals("Lady Gaga", ArtworkFetcher.firstOfJoint("Lady Gaga and Bradley Cooper"))
        assertEquals("Calvin Harris", ArtworkFetcher.firstOfJoint("Calvin Harris feat. Rihanna"))
        assertEquals("Calvin Harris", ArtworkFetcher.firstOfJoint("Calvin Harris ft. Rihanna"))
        assertEquals("Earth", ArtworkFetcher.firstOfJoint("Earth, Wind & Fire"))
        assertEquals(null, ArtworkFetcher.firstOfJoint("Coldplay"))
        assertEquals("a word that only begins like one", null, ArtworkFetcher.firstOfJoint("Featherstone"))
        assertEquals(null, ArtworkFetcher.firstOfJoint("Brandon"))
    }

    @Test
    fun `however many names an artist has to try, five lookups at most`() = runTest {
        for (title in listOf("Mitski & Friends", "Mitski", "Mitski (band)", "Mitski (singer)", "Mitski (musician)", "Mitski (composer)")) {
            wiki.page(title, "$title may refer to:", type = "disambiguation")
        }
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Composer("mitski friends", "Mitski & Friends")))
        assertEquals(ArtworkFetcher.MAX_LOOKUPS, wiki.calls.size)
        assertEquals(
            listOf("summary Mitski & Friends", "summary Mitski", "summary Mitski (band)", "summary Mitski (singer)", "summary Mitski (musician)"),
            wiki.kinds,
        )
    }

    @Test
    fun `a canonical composer's path is as it was`() = runTest {
        wiki.page("Claude Debussy", "Achille Claude Debussy was a French composer.", description = "French composer (1862–1918)", image = portrait)
        assertTrue(fetcher.fetch(ArtKey.Composer("debussy", "Claude Debussy")) is Fetched.Found)
        assertEquals(listOf("summary Claude Debussy", "download $portrait"), wiki.kinds)
        // A canonical composer's page is taken whatever it says, and a disambiguation tries "(composer)" alone.
        wiki.page("Erik Satie", "Eric Alfred Leslie Satie was a French pianist.", description = "French composer and pianist")
        assertTrue(fetcher.fetch(ArtKey.Composer("satie", "Erik Satie")) is Fetched.Found)
        wiki.page("Edvard Grieg", "Grieg may refer to:", type = "disambiguation")
        assertEquals(Fetched.NotFound, fetcher.fetch(ArtKey.Composer("grieg", "Edvard Grieg")))
        assertEquals(listOf("summary Edvard Grieg", "summary Edvard Grieg (composer)"), wiki.kinds.takeLast(2))
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
