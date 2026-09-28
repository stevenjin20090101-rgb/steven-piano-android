// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The request page's QR code and poster: it scans back to its address, and the page carries it safely. */
class PosterTest {
    /** [matrix] drawn [scale] pixels a module with its quiet zone, then read by ZXing as a phone's camera would. */
    private fun scan(matrix: QrMatrix, scale: Int = 4): String {
        val quiet = QrMatrix.QUIET_ZONE
        val side = (matrix.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { PAPER }
        for (row in 0 until matrix.size) for (column in 0 until matrix.size) {
            if (!matrix.isDark(row, column)) continue
            for (y in 0 until scale) for (x in 0 until scale) pixels[((row + quiet) * scale + y) * side + (column + quiet) * scale + x] = INK
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))).text
    }

    @Test
    fun `the QR scans back to exactly the address it was made for`() {
        for (url in listOf("http://100.101.2.3:8737/request", "http://192.168.1.20:8737/request", "http://10.0.2.16:8737", "http://100.64.0.1:8737/request")) {
            assertEquals(url, scan(Poster.qr(url)))
        }
    }

    @Test
    fun `the code is the smallest version for the address, with its three finder squares`() {
        val qr = Poster.qr("http://100.101.2.3:8737/request")
        assertEquals("31 bytes at level M: version 3", 29, qr.size)
        for ((top, left) in listOf(0 to 0, 0 to qr.size - 7, qr.size - 7 to 0)) {
            for (i in 0 until 7) {
                assertTrue(qr.isDark(top, left + i) && qr.isDark(top + 6, left + i) && qr.isDark(top + i, left) && qr.isDark(top + i, left + 6))
            }
            assertFalse(qr.isDark(top + 1, left + 1))
            assertTrue(qr.isDark(top + 3, left + 3))
        }
    }

    @Test
    fun `the SVG draws exactly the dark modules, inside a quiet zone of four`() {
        val qr = Poster.qr("http://192.168.1.20:8737/request")
        val svg = Poster.svg(qr, "QR code")
        val side = qr.size + 8
        assertTrue(svg.contains("viewBox=\"0 0 $side $side\""))
        val drawn = HashSet<Pair<Int, Int>>()
        Regex("M(\\d+) (\\d+)h(\\d+)v1h-(\\d+)z").findAll(svg).forEach { m ->
            val (x, y, w) = m.destructured
            for (dx in 0 until w.toInt()) drawn += y.toInt() to x.toInt() + dx
        }
        val dark = HashSet<Pair<Int, Int>>()
        for (row in 0 until qr.size) for (column in 0 until qr.size) if (qr.isDark(row, column)) dark += row + 4 to column + 4
        assertEquals(dark, drawn)
        assertFalse("no style attribute: the panel's policy allows none", svg.contains("style="))
        assertTrue(svg.contains("fill=\"currentColor\""))
    }

    @Test
    fun `the poster page carries the address as text and as the QR, escaped`() {
        val template = "<p>{{URL}}</p><div>{{QR}}</div><a>{{URL}}</a>".toByteArray()
        val page = Poster.page(template, "http://a.b/?x=1&y=\"<2>'")!!.toString(Charsets.UTF_8)
        assertFalse(page.contains("{{"))
        assertTrue(page.contains("<p>http://a.b/?x=1&amp;y=&quot;&lt;2&gt;&#39;</p>"))
        assertTrue(page.contains("<svg class=\"qr\""))
        assertTrue(page.contains("aria-label=\"QR code for http://a.b/?x=1&amp;y=&quot;&lt;2&gt;&#39;\""))
        assertEquals(null, Poster.page(null, "http://a.b"))
    }

    @Test
    fun `the poster template has its two places, no script and no inline style`() {
        val template = File("src/main/assets/web/poster.html").readText()
        assertTrue(template.contains(Poster.QR_PLACEHOLDER))
        assertTrue(template.contains(Poster.URL_PLACEHOLDER))
        assertTrue(template.contains("Ask the piano"))
        assertTrue(template.contains("Scan to pick a piece for the piano"))
        assertFalse(template.contains("<script"))
        assertFalse(template.contains("style="))
    }

    private companion object {
        const val PAPER = -1                 // opaque white, for the reader only
        const val INK = -0x1000000           // opaque black, for the reader only
    }
}
