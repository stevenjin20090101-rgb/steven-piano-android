// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.CRC32

/**
 * Artwork files, the way [dev.stevenjin.stevenpiano.data.PieceFiles] keeps pieces: one file per
 * artwork key under `filesDir/art/`, named from the key with everything but letters and digits
 * flattened and a checksum of the whole key, so no two keys share a file. Paths handed out are
 * relative to the files directory ("art/composer-debussy-1a2b3c4d.jpg"), as the artwork table
 * stores them. Blocking I/O.
 */
class ArtFiles(private val filesDir: File) {
    private val dir = File(filesDir, DIR)

    /** Saves [bytes] as [key]'s image, atomically, replacing any earlier one. Returns its relative path. */
    fun write(key: String, bytes: ByteArray): String {
        val name = nameFor(key, extensionOf(bytes))
        dir.mkdirs()
        val partial = File(dir, "$name.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(File(dir, name))) {
            partial.delete()
            throw IOException("Couldn't save $name")
        }
        return "$DIR/$name"
    }

    /** The file at a stored [path]. */
    fun file(path: String): File = File(filesDir, path)

    fun delete(path: String) {
        file(path).delete()
    }

    companion object {
        const val DIR = "art"
        private const val READABLE_LENGTH = 40

        /** "composer:saint-saens" as "composer-saint-saens-<crc32>.<extension>". */
        fun nameFor(key: String, extension: String): String {
            val readable = key.lowercase(Locale.ROOT)
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(READABLE_LENGTH)
                .ifEmpty { "art" }
            val crc = CRC32().apply { update(key.toByteArray(Charsets.UTF_8)) }.value
            return "$readable-%08x.$extension".format(Locale.ROOT, crc)
        }

        /** The extension for an image's bytes, from their signature: jpg, png, gif or webp; "img" otherwise. */
        fun extensionOf(bytes: ByteArray): String {
            fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
            return when {
                at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
                at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> "png"
                at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "gif"
                at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code && at(9) == 'E'.code -> "webp"
                else -> "img"
            }
        }
    }
}
