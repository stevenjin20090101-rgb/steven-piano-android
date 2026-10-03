// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Apple's catalogue for album covers (v1.15 — M40): the search's exact address, its answer read and capped, the artwork's size and hosts. */
class AppleCatalogTest {
    private val transport = FakeTransport()
    private val catalog = AppleCatalog(country = "US", io = Dispatchers.Unconfined, log = {}, transport = transport)
    private val search = "https://itunes.apple.com/search?term=someone+you+loved+lewis+capaldi&media=music&entity=song&limit=10&country=US"
    private val art100 = "https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/a1/b2/c3/a1b2c3d4/19UMGIM04443.rgb.jpg/100x100bb.jpg"
    private val page = "https://music.apple.com/us/album/someone-you-loved/1445869498?i=1445869512&uo=4"

    private suspend fun failsWith(code: Int?, request: suspend () -> Unit) {
        try {
            request()
            fail("expected a failure")
        } catch (e: AppleBusyException) {
            assertEquals(code, e.code)
        } catch (e: IOException) {
            assertNull("not Apple asking to stop: $e", code)
        }
    }

    @Test
    fun `the search is exactly Apple's, for ten songs, in the device's store or else the US`() {
        assertEquals(search, AppleUrls.search("someone you loved lewis capaldi", 10, "US"))
        assertEquals(
            "https://itunes.apple.com/search?term=f%C3%BCr+elise+%26+co%3F&media=music&entity=song&limit=10&country=FR",
            AppleUrls.search(" für elise & co? ", 10, AppleUrls.country("fr")),
        )
        assertEquals("GB", AppleUrls.country("GB"))
        assertEquals("US", AppleUrls.country(""))
        assertEquals("US", AppleUrls.country("419"))   // a region, not a country
        assertEquals("US", AppleUrls.country(null))
        assertTrue(AppleUrls.allowed(search))
    }

    @Test
    fun `the answer is read as JSON, songs without a name skipped, capped at 256 KB, and a 429 or 403 told apart`() = runBlocking {
        val json = """
            {"resultCount": 3, "results": [
              {"wrapperType": "track", "kind": "song", "artistName": "Lewis Capaldi", "collectionName": "Divinely Uninspired to a Hellish Extent",
               "trackName": "Someone You Loved", "artworkUrl100": "$art100", "trackViewUrl": "$page"},
              {"wrapperType": "track", "kind": "song", "artistName": "Lewis Capaldi", "trackName": ""},
              {"wrapperType": "track", "kind": "song", "artistName": "Piano Dreamers", "trackName": "Someone You Loved", "collectionName": null}
            ]}
        """.trimIndent()
        transport.ok(search, json.toByteArray())
        assertEquals(
            listOf(
                CatalogTrack("Someone You Loved", "Lewis Capaldi", "Divinely Uninspired to a Hellish Extent", art100, page, "song"),
                CatalogTrack("Someone You Loved", "Piano Dreamers", null, null, null, "song"),
            ),
            catalog.search("someone you loved lewis capaldi", 10),
        )
        assertEquals(listOf(search), transport.opened.map { it.first })

        transport.ok(search, ByteArray(AppleCatalog.JSON_CAP + 1) { ' '.code.toByte() })
        failsWith(null) { catalog.search("someone you loved lewis capaldi", 10) }
        transport.ok(search, "{}".toByteArray(), declared = AppleCatalog.JSON_CAP + 1L)   // refused by its declared length
        failsWith(null) { catalog.search("someone you loved lewis capaldi", 10) }
        transport.ok(search, "not JSON".toByteArray())
        failsWith(null) { catalog.search("someone you loved lewis capaldi", 10) }
        transport.answers[search] = FakeTransport.Answer(429)
        failsWith(429) { catalog.search("someone you loved lewis capaldi", 10) }
        transport.answers[search] = FakeTransport.Answer(403)
        failsWith(403) { catalog.search("someone you loved lewis capaldi", 10) }
    }

    @Test
    fun `the artwork is asked for at 600 px, its last segment's size rewritten`() {
        val track = CatalogTrack("Someone You Loved", "Lewis Capaldi", null, art100, page, "song")
        assertEquals(art100.replace("/100x100bb.jpg", "/600x600bb.jpg"), track.coverUrl)
        assertTrue(AppleUrls.allowed(track.coverUrl!!))
        assertEquals(
            "https://is2-ssl.mzstatic.com/image/thumb/x/source/600x600bb.png",
            AppleUrls.cover("https://IS2-SSL.mzstatic.com/image/thumb/x/source/100x100bb.png?utm=1#top"),
        )
        assertEquals("https://is1-ssl.mzstatic.com/100x100bb/a.jpg/600x600bb.jpg", AppleUrls.cover("https://is1-ssl.mzstatic.com/100x100bb/a.jpg/100x100bb.jpg"))
        assertNull(AppleUrls.cover(null))
    }

    @Test
    fun `a lookalike image host is refused before anything is sent, and the cover's link is only ever Apple Music's`() = runBlocking {
        for (bad in listOf(
            "https://is1-ssl.mzstatic.com.evil.example/x/100x100bb.jpg",
            "https://evilmzstatic.com/x/100x100bb.jpg",
            "https://mzstatic.com/x/100x100bb.jpg",
            "https://is1-ssl.mzstatic.com@evil.example/x/100x100bb.jpg",
            "https://evil.example\\@is1-ssl.mzstatic.com/x/100x100bb.jpg",
            "http://is1-ssl.mzstatic.com/x/100x100bb.jpg",
            "https://is1-ssl.mzstatic.com:8443/x/100x100bb.jpg",
            "https://is1-ssl.mzstatic.com./x/100x100bb.jpg",
            "javascript:alert(1)",
        )) {
            assertNull(bad, AppleUrls.cover(bad))
            assertFalse(bad, AppleUrls.allowed(bad))
        }
        try {
            catalog.download("https://is1-ssl.mzstatic.com.evil.example/x/600x600bb.jpg", 1_000)
            fail("refused")
        } catch (e: RefusedRequestException) {
            assertTrue(transport.opened.isEmpty())
        }
        assertEquals(page, AppleUrls.pageLink(page))
        for (bad in listOf(
            null, "http://music.apple.com/us/album/x/1", "https://music.apple.com.evil.example/x", "https://evil.example/?music.apple.com",
            "https://user@music.apple.com/x", "https://music.apple.com:8443/x", "intent://music.apple.com/x#Intent;end", "https://music.apple.com",
        )) {
            assertNull(bad, AppleUrls.pageLink(bad))
        }
    }
}
