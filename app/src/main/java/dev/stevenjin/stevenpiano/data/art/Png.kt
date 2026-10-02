// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * A small PNG encoder (v1.12 — M30) for the drawn covers, pure Java so it runs in the tests as on the tablet: 8-bit
 * RGB, each row filtered with Sub (the covers are washes and discs, which Sub packs well), one zlib stream.
 */
object Png {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** [argb] (row-major, [width] × [height], alpha ignored) as a PNG file. */
    fun encode(argb: IntArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && argb.size == width * height) { "an image of $width × $height" }
        val raw = ByteArrayOutputStream(height * (1 + width * 3))
        val row = ByteArray(1 + width * 3)
        for (y in 0 until height) {
            row[0] = 1   // Sub
            var left = 0
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lr = (left shr 16) and 0xFF
                val lg = (left shr 8) and 0xFF
                val lb = left and 0xFF
                row[1 + x * 3] = (r - lr).toByte()
                row[2 + x * 3] = (g - lg).toByte()
                row[3 + x * 3] = (b - lb).toByte()
                left = c
            }
            raw.write(row)
        }
        val packed = ByteArrayOutputStream()
        val deflater = Deflater(6)
        try {
            DeflaterOutputStream(packed, deflater).use { it.write(raw.toByteArray()) }
        } finally {
            deflater.end()
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream().also { h ->
            DataOutputStream(h).apply {
                writeInt(width)
                writeInt(height)
                writeByte(8)   // bit depth
                writeByte(2)   // RGB
                writeByte(0)
                writeByte(0)
                writeByte(0)
            }
        }.toByteArray()
        chunk(out, "IHDR", header)
        chunk(out, "IDAT", packed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val data32 = DataOutputStream(out)
        data32.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        data32.writeInt(crc.value.toInt())
    }
}
