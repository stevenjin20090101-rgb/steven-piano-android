// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus
import dev.stevenjin.stevenpiano.net.AppleBusyException
import dev.stevenjin.stevenpiano.net.AppleCatalogApi
import dev.stevenjin.stevenpiano.net.CatalogTrack
import dev.stevenjin.stevenpiano.net.WikipediaClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/** The web panel's cover picker (v1.18 — M48) against a stand-in for Apple's catalogue and for the artwork store. */
class CoverPickerTest {
    private var now = 5_000_000L
    private val catalogue = FakeCatalogue()
    private val store = FakeStore()
    private var stoppedUntil: Long? = null
    private var ids = 0
    private val picker = CoverPicker(
        api = catalogue,
        store = store,
        now = { now },
        blockedFor = { stoppedUntil?.let { it - now }?.takeIf { it > 0 } },
        stop = { stoppedUntil = now + CoverFetcher.BLOCK_MS },
        newId = { "search-${++ids}" },
    )

    /** Apple's catalogue: [results] for any search, and every download asked for; a picture's bytes are its URL's, after a JPEG's mark. */
    private class FakeCatalogue : AppleCatalogApi {
        var results: List<CatalogTrack> = emptyList()
        var busy = false
        val searches: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val downloads: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())

        /** Downloads that throw, and those that answer with no picture at all. */
        val failing = mutableSetOf<String>()
        val notPictures = mutableSetOf<String>()

        override suspend fun search(term: String, limit: Int): List<CatalogTrack> {
            searches += "$term|$limit"
            if (busy) throw AppleBusyException(429)
            return results
        }

        override suspend fun download(url: String, maxBytes: Int): ByteArray? {
            downloads += url to maxBytes
            if (url in failing) throw IOException("unreachable")
            if (url in notPictures) return "<html>a page, not a picture</html>".toByteArray()
            return JPEG_MARK + url.toByteArray()
        }
    }

    /** The artwork store: piece 1 from the library, piece 2 made here; what was kept or taken away. */
    private class FakeStore : PickedCovers {
        val madeHere = mapOf(1L to false, 2L to true)
        val kept = mutableListOf<String>()

        override suspend fun madeHere(pieceId: Long): Boolean? = madeHere[pieceId]

        override suspend fun keepChosen(pieceId: Long, image: ByteArray, sourceUrl: String?, sourceTitle: String): Boolean {
            kept += "keep $pieceId $sourceTitle $sourceUrl ${String(image.copyOfRange(JPEG_MARK.size, image.size))}"
            return true
        }

        override suspend fun takeAway(pieceId: Long): Boolean {
            kept += "take away $pieceId"
            return true
        }
    }

    private fun track(n: Int, album: String?, artist: String = "Hans Zimmer", host: String = "is1-ssl.mzstatic.com", artwork: Boolean = true) = CatalogTrack(
        trackName = "Track $n",
        artistName = artist,
        collectionName = album,
        artworkUrl100 = if (artwork) "https://$host/image/thumb/Music/$n/100x100bb.jpg" else null,
        trackViewUrl = "https://music.apple.com/us/album/$n",
        kind = "song",
    )

    private fun small(n: Int, host: String = "is1-ssl.mzstatic.com") = "https://$host/image/thumb/Music/$n/100x100bb.jpg"

    @Test
    fun `a search keeps one result an album with artwork, twelve at most, and leaves out a picture that fails`() = runTest {
        // Two songs an album over fifteen albums, Apple's order, after two songs without artwork.
        catalogue.results = listOf(track(90, "No Art", artwork = false), track(91, "No Art Either", artwork = false)) +
            (1..15).flatMap { a -> listOf(track(a * 2 - 1, "Album $a"), track(a * 2, if (a == 3) "ALBUM 3" else "Album $a")) }
        catalogue.failing += small(3)           // album 2's picture can't be had
        catalogue.notPictures += small(7)       // album 4's is a page, not a picture
        val found = picker.search("  interstellar zimmer  ") as CoverSearch.Found
        assertEquals("asked once, trimmed, for the lookup's 25", listOf("interstellar zimmer|25"), catalogue.searches.toList())
        val asked = (1..12).map { small(it * 2 - 1) }
        assertEquals("the first song of each of the first twelve albums, and their pictures only", asked.toSet(), catalogue.downloads.map { it.first }.toSet())
        assertTrue("each picture capped at 64 KB", catalogue.downloads.all { it.second == CoverPicker.PICTURE_CAP })
        assertEquals(
            "albums 2 and 4 left out; the rest in Apple's order, indexed from 0",
            (1..12).filter { it != 2 && it != 4 }.map { "Album $it" },
            found.picks.map { it.album },
        )
        assertEquals((0 until 10).toList(), found.picks.map { it.index })
        assertTrue(found.picks.all { it.artist == "Hans Zimmer" && it.type == CoverPicker.JPEG })
        assertEquals(small(1), String(found.picks[0].picture.copyOfRange(JPEG_MARK.size, found.picks[0].picture.size)))

        // An index is the result shown there: the third shown is album 5, whose 600 px cover is what is kept.
        assertEquals(CoverChange.Done, picker.choose(1, found.searchId, 2))
        assertEquals(
            listOf("keep 1 Album 5 · Hans Zimmer https://music.apple.com/us/album/9 https://is1-ssl.mzstatic.com/image/thumb/Music/9/600x600bb.jpg"),
            store.kept,
        )
        assertEquals("the cover as the lookup takes it", WikipediaClient.IMAGE_CAP, catalogue.downloads.last().second)
    }

    @Test
    fun `a lookalike picture host is refused by the catalogue's own rule, never asked for and never shown`() = runTest {
        catalogue.results = listOf(
            track(1, "Lookalike", host = "is1-ssl.mzstatic.com.evil.example"),
            track(2, "Bare", host = "evilmzstatic.com"),
            track(3, "Plain HTTP").copy(artworkUrl100 = "http://is1-ssl.mzstatic.com/image/thumb/Music/3/100x100bb.jpg"),
            track(4, "Apple's own"),
        )
        val found = picker.search("stay interstellar") as CoverSearch.Found
        assertEquals(listOf("Apple's own"), found.picks.map { it.album })
        assertEquals(listOf(small(4)), catalogue.downloads.map { it.first })
    }

    @Test
    fun `one search in 4 s whichever panel asks, none while Apple's stop is on, and Apple's 429 starts it`() = runTest {
        catalogue.results = listOf(track(1, "Interstellar"))
        assertTrue(picker.search("interstellar") is CoverSearch.Found)
        now += 3_000
        assertEquals("a second within 4 s waits, and asks nothing", CoverSearch.Wait(1_000), picker.search("interstellar zimmer"))
        assertEquals(1, catalogue.searches.size)
        now += 1_000
        assertTrue("4 s on", picker.search("interstellar zimmer") is CoverSearch.Found)
        assertEquals(CoverSearch.BadText, picker.search(" a "))
        assertEquals(CoverSearch.BadText, picker.search("x".repeat(CoverPicker.MAX_TEXT + 1)))
        assertEquals("80 is the most", "x".repeat(80), CoverPicker.term("x".repeat(80)))

        // Apple's 429: its hour-long stop starts, shared with the lookup, and nothing more is asked until it lifts.
        now += 4_000
        catalogue.busy = true
        assertEquals(CoverSearch.Busy(CoverFetcher.BLOCK_MS), picker.search("interstellar"))
        catalogue.busy = false
        now += 60_000
        assertEquals(CoverSearch.Busy(CoverFetcher.BLOCK_MS - 60_000), picker.search("interstellar"))
        assertEquals("nothing asked while it stands", 3, catalogue.searches.size)
        now += CoverFetcher.BLOCK_MS
        assertTrue(picker.search("interstellar") is CoverSearch.Found)
    }

    @Test
    fun `a search is remembered ten minutes and only the last four`() = runTest {
        catalogue.results = listOf(track(1, "Interstellar"), track(2, "Dunkirk"))
        val first = (picker.search("zimmer") as CoverSearch.Found).searchId
        now += CoverPicker.KEPT_MS - 1
        assertEquals("just under ten minutes", CoverChange.Done, picker.choose(1, first, 1))
        now += 1
        assertEquals("ten minutes on", CoverChange.NotFound, picker.choose(1, first, 1))

        val searches = (1..5).map {
            now += CoverPicker.FLOOR_MS
            (picker.search("zimmer $it") as CoverSearch.Found).searchId
        }
        assertEquals("the oldest of five is forgotten", CoverChange.NotFound, picker.choose(1, searches[0], 0))
        assertEquals(CoverChange.Done, picker.choose(1, searches[1], 0))
        assertEquals("an index that isn't there", CoverChange.NotFound, picker.choose(1, searches[4], 2))
        assertEquals(CoverChange.NotFound, picker.choose(1, "search-999", 0))
        assertEquals("a piece that isn't there", CoverChange.NotFound, picker.choose(7, searches[4], 0))
    }

    @Test
    fun `a piece made here is refused, and a cover taken away is never looked for again`() = runTest {
        catalogue.results = listOf(track(1, "Interstellar"))
        val search = (picker.search("interstellar") as CoverSearch.Found).searchId
        val downloads = catalogue.downloads.size
        assertEquals(CoverChange.MadeHere, picker.choose(2, search, 0))
        assertEquals(CoverChange.MadeHere, picker.remove(2))
        assertEquals(CoverChange.NotFound, picker.remove(7))
        assertEquals("nothing downloaded for them", downloads, catalogue.downloads.size)
        assertEquals(CoverChange.Done, picker.remove(1))
        assertEquals(listOf("take away 1"), store.kept)

        // What the repository records for it: not found, chosen in the panel. The lookup reads it as settled, forced or not.
        val removed = ArtworkEntity(ArtworkEntity.forCover(1), description = ArtworkEntity.CHOSEN_IN_PANEL, fetchedAt = 1_000, status = ArtworkStatus.NOT_FOUND)
        val key = ArtKey.Cover(1, "Interstellar", "Zimmer", classical = false)
        assertTrue(ArtworkPolicy.chosenByHand(removed))
        for (force in listOf(false, true)) {
            assertFalse("forced: $force", ArtworkPolicy.shouldFetch(ArtworkPolicy.recordOf(key, removed), 2_000, force, coverRuleSince = 5_000))
        }
        val plain = removed.copy(description = null)
        assertTrue("a plain not-found lookup is still asked again when forced", ArtworkPolicy.shouldFetch(ArtworkPolicy.recordOf(key, plain), 2_000, force = true))
    }

    private companion object {
        val JPEG_MARK = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }
}
