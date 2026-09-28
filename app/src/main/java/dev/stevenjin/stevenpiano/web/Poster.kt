// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor

/**
 * A QR code's modules: [size] × [size], row by row, true where a module is dark. The quiet zone
 * (four modules of light round it) is not part of it; whoever draws it leaves that room.
 */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    fun isDark(row: Int, column: Int): Boolean = dark[row * size + column]

    companion object {
        /** The light margin a scanner needs round the code, in modules. */
        const val QUIET_ZONE = 4
    }
}

/**
 * The request page's QR code and poster (DESIGN.md › v1.5.1 — M18). The QR is encoded with
 * qrcode-kotlin's encoder alone (its raw modules; none of its drawing), at error correction M
 * (15 % of the code may be lost to a crease or a smudge), and drawn by the app: as SVG for the
 * poster (`/poster`, printed through the tablet's own print service) and on a Compose canvas for
 * the Remote page.
 */
object Poster {
    /** [text]'s QR code. */
    fun qr(text: String): QrMatrix {
        val modules = QRCodeProcessor(text, ErrorCorrectionLevel.MEDIUM).encode()
        val size = modules.size
        val dark = BooleanArray(size * size)
        for (row in 0 until size) for (column in 0 until size) dark[row * size + column] = modules[row][column].dark
        return QrMatrix(size, dark)
    }

    /**
     * The QR as one SVG path over a viewBox that includes the quiet zone: one rectangle per run of
     * dark modules in a row, in `currentColor` (the page sets the ink). No `style` attribute, so it
     * passes the panel's content security policy; [label] names it for screen readers.
     */
    fun svg(matrix: QrMatrix, label: String): String {
        val quiet = QrMatrix.QUIET_ZONE
        val side = matrix.size + 2 * quiet
        val path = StringBuilder()
        for (row in 0 until matrix.size) {
            var column = 0
            while (column < matrix.size) {
                if (!matrix.isDark(row, column)) {
                    column++
                    continue
                }
                val start = column
                while (column < matrix.size && matrix.isDark(row, column)) column++
                path.append('M').append(start + quiet).append(' ').append(row + quiet).append('h').append(column - start).append("v1h-").append(column - start).append('z')
            }
        }
        return "<svg class=\"qr\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $side $side\" role=\"img\" aria-label=\"${escape(label)}\" " +
            "shape-rendering=\"crispEdges\"><path fill=\"currentColor\" d=\"$path\"/></svg>"
    }

    /**
     * The poster: [template] (`assets/web/poster.html`) with `{{URL}}` replaced by [url] as text and
     * `{{QR}}` by its QR code. Null without a template.
     */
    fun page(template: ByteArray?, url: String): ByteArray? {
        val text = template?.toString(Charsets.UTF_8) ?: return null
        return text
            .replace(QR_PLACEHOLDER, svg(qr(url), "QR code for $url"))
            .replace(URL_PLACEHOLDER, escape(url))
            .toByteArray(Charsets.UTF_8)
    }

    /** Text safe inside HTML, attribute values included. */
    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    const val QR_PLACEHOLDER = "{{QR}}"
    const val URL_PLACEHOLDER = "{{URL}}"
}
