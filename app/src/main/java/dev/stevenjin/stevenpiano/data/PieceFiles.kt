// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

import java.io.File
import java.io.IOException

/** The library's MIDI files, stored once each as `filesDir/pieces/<sha256>.mid`. Blocking I/O. */
class PieceFiles(filesDir: File) {
    private val dir = File(filesDir, "pieces")

    /** Saves [bytes] under their hash, atomically; a file already there is kept. Returns the file name. */
    fun write(sha256: String, bytes: ByteArray): String {
        val name = "$sha256.mid"
        val target = File(dir, name)
        if (target.length() == bytes.size.toLong()) return name
        dir.mkdirs()
        val partial = File(dir, "$name.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Couldn't save $name")
        }
        return name
    }

    fun read(fileName: String): ByteArray = File(dir, fileName).readBytes()

    fun delete(fileName: String) {
        File(dir, fileName).delete()
    }
}
