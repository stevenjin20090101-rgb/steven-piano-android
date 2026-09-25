// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaUrlsTest {
    @Test
    fun `titles take underscores for spaces, then UTF-8 percent-encoding`() {
        assertEquals("Anton%C3%ADn_Dvo%C5%99%C3%A1k", WikipediaUrls.encodeTitle("Antonín Dvořák"))
        assertEquals("Fr%C3%A9d%C3%A9ric_Chopin", WikipediaUrls.encodeTitle("  Frédéric Chopin "))
        assertEquals("AC%2FDC", WikipediaUrls.encodeTitle("AC/DC"))
        assertEquals("Moritz_Moszkowski_%28composer%29", WikipediaUrls.encodeTitle("Moritz Moszkowski (composer)"))
        assertEquals(
            "https://en.wikipedia.org/api/rest_v1/page/summary/Anton%C3%ADn_Dvo%C5%99%C3%A1k",
            WikipediaUrls.summary("Antonín Dvořák"),
        )
    }

    @Test
    fun `search asks for three articles, as JSON, format version 2`() {
        assertEquals(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=Clair%20de%20lune%20Claude%20Debussy" +
                "&srlimit=3&srnamespace=0&format=json&formatversion=2",
            WikipediaUrls.search("Clair de lune Claude Debussy"),
        )
        assertTrue(WikipediaUrls.search("Für Elise & Co?").contains("srsearch=F%C3%BCr%20Elise%20%26%20Co%3F&"))
    }

    @Test
    fun `a wide original takes the thumbnail rewritten to the 960 px step on the upload host, without the tracking query`() {
        // As the summary API answered for Johann Sebastian Bach in September 2026.
        val thumbnail = "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6a/Johann_Sebastian_Bach.jpg/330px-Johann_Sebastian_Bach.jpg" +
            "?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail"
        val original = "https://upload.wikimedia.org/wikipedia/commons/6/6a/Johann_Sebastian_Bach.jpg" +
            "?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail_unscaled"
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6a/Johann_Sebastian_Bach.jpg/960px-Johann_Sebastian_Bach.jpg",
            WikipediaUrls.image(original, 1376, thumbnail),
        )
        assertEquals(960, WikipediaUrls.THUMBNAIL_STEPS.last { it <= WikipediaUrls.MAX_IMAGE_WIDTH })
    }

    @Test
    fun `an original at most 1024 px wide is used as it is`() {
        val original = "https://upload.wikimedia.org/wikipedia/commons/a/ab/Claude_Debussy_ca_1908.jpg?utm_source=en.wikipedia.org"
        val thumbnail = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Claude_Debussy_ca_1908.jpg/330px-Claude_Debussy_ca_1908.jpg"
        val expected = "https://upload.wikimedia.org/wikipedia/commons/a/ab/Claude_Debussy_ca_1908.jpg"
        assertEquals(expected, WikipediaUrls.image(original, 800, thumbnail))
        assertEquals(expected, WikipediaUrls.image(original, 1024, thumbnail))
        assertEquals(expected, WikipediaUrls.image("//upload.wikimedia.org/wikipedia/commons/a/ab/Claude_Debussy_ca_1908.jpg", 1024, null))
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Claude_Debussy_ca_1908.jpg/960px-Claude_Debussy_ca_1908.jpg",
            WikipediaUrls.image(original, 1025, thumbnail),
        )
    }

    @Test
    fun `drawings and scans come as rendered thumbnails, never enlarged past a scan's own width`() {
        val svg = "https://upload.wikimedia.org/wikipedia/commons/1/1a/Signature.svg"
        val svgThumb = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1a/Signature.svg/330px-Signature.svg.png"
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1a/Signature.svg/960px-Signature.svg.png",
            WikipediaUrls.image(svg, 200, svgThumb),
        )
        val tif = "https://upload.wikimedia.org/wikipedia/commons/2/2b/Portrait.tif"
        val tifThumb = "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2b/Portrait.tif/lossy-page1-330px-Portrait.tif.jpg"
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2b/Portrait.tif/lossy-page1-500px-Portrait.tif.jpg",
            WikipediaUrls.image(tif, 800, tifThumb),
        )
    }

    @Test
    fun `no image, or an image anywhere else, is no image`() {
        assertNull(WikipediaUrls.image(null, 0, null))
        assertNull(WikipediaUrls.image("https://upload.wikimedia.org/wikipedia/commons/6/6a/Bach.jpg", 2000, null))
        assertNull(WikipediaUrls.image("https://example.com/bach.jpg", 500, null))
        assertNull(WikipediaUrls.image("ftp://upload.wikimedia.org/bach.jpg", 500, null))
    }

    @Test
    fun `requests go over HTTPS to the two hosts and nowhere else`() {
        assertEquals(setOf("en.wikipedia.org", "upload.wikimedia.org"), WikipediaUrls.HOSTS)
        assertTrue(WikipediaUrls.allowed("https://en.wikipedia.org/api/rest_v1/page/summary/Bach"))
        assertTrue(WikipediaUrls.allowed("https://upload.wikimedia.org/wikipedia/commons/6/6a/Bach.jpg"))
        assertTrue(WikipediaUrls.allowed("https://EN.Wikipedia.org/wiki/Bach"))
        assertFalse(WikipediaUrls.allowed("http://en.wikipedia.org/wiki/Bach"))
        assertFalse(WikipediaUrls.allowed("https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6a/Bach.jpg/330px-Bach.jpg"))
        assertFalse(WikipediaUrls.allowed("https://en.wikipedia.org.example.com/wiki/Bach"))
        assertFalse(WikipediaUrls.allowed("https://en.wikipedia.org@example.com/wiki/Bach"))
        assertFalse(WikipediaUrls.allowed("https://de.wikipedia.org/wiki/Bach"))
    }

    @Test
    fun `a From Wikipedia link is only ever an English Wikipedia article over HTTPS`() {
        val bach = "https://en.wikipedia.org/wiki/Johann_Sebastian_Bach"
        assertEquals(bach, WikipediaUrls.pageLink(bach))
        assertEquals("https://en.wikipedia.org/wiki/Clair_de_lune_(Debussy)", WikipediaUrls.pageLink("https://en.wikipedia.org/wiki/Clair_de_lune_(Debussy)"))
        assertEquals("https://en.wikipedia.org/wiki/Anton%C3%ADn_Dvo%C5%99%C3%A1k", WikipediaUrls.pageLink("https://en.wikipedia.org/wiki/Anton%C3%ADn_Dvo%C5%99%C3%A1k"))
        for (bad in listOf(
            null, "", "http://en.wikipedia.org/wiki/Bach", "javascript:alert(1)", "intent://en.wikipedia.org/wiki/Bach#Intent;end",
            "file:///sdcard/Bach.html", "content://en.wikipedia.org/wiki/Bach", "https://de.wikipedia.org/wiki/Bach",
            "https://en.wikipedia.org.evil.com/wiki/Bach", "https://evil.com/wiki/Bach?en.wikipedia.org", "https://en.wikipedia.org@evil.com/wiki/Bach",
            "https://user@en.wikipedia.org/wiki/Bach", "https://en.wikipedia.org:8443/wiki/Bach", "https://en.wikipedia.org:443/wiki/Bach",
            "https://en.wikipedia.org/w/index.php?title=Bach", "https://en.wikipedia.org/wiki/", "https://en.wikipedia.org",
            "https://evil.com\\@en.wikipedia.org/wiki/Bach", "https://en.wikipedia.org/wiki/Bach Air", "HTTPS://EN.WIKIPEDIA.ORG/wiki/Bach",
        )) {
            assertNull(bad, WikipediaUrls.pageLink(bad))
        }
    }

    @Test
    fun `Retry-After in seconds becomes milliseconds, a date or nothing is unknown`() {
        assertEquals(120_000L, WikipediaUrls.retryAfterMillis("120"))
        assertEquals(0L, WikipediaUrls.retryAfterMillis(" 0 "))
        assertNull(WikipediaUrls.retryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(WikipediaUrls.retryAfterMillis(null))
        assertNull(WikipediaUrls.retryAfterMillis("-5"))
    }
}
