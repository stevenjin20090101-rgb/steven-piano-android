// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * What one import may cost, whatever it is handed: a zip, a folder from any documents provider,
 * an INDEX.csv. Generous for the real library (1,727 pieces in one 8 MB zip; the folder tree three
 * levels deep) and small enough that a crafted source ends in a plain failure, not a hang or a
 * crash. A MIDI file itself keeps its 8 MB cap in [Importer].
 */
object ImportLimits {
    /** An INDEX.csv larger than this is ignored (the files still import, named from their file names). */
    const val INDEX_BYTES = 2 * 1024 * 1024

    /** A zip larger than this is refused. */
    const val ZIP_BYTES = 512L * 1024 * 1024

    /** A zip with more entries than this is refused, before any are listed. */
    const val ZIP_ENTRIES = 20_000

    /** Free space the cache must keep beyond a zip's copy. */
    const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    /** Folders deeper than this under the chosen one are not read. */
    const val TREE_DEPTH = 16

    /** MIDI files, and folders, taken from a folder tree at most. */
    const val TREE_FILES = 20_000
    const val TREE_FOLDERS = 5_000

    /** Documents looked at in a tree at most, of any kind: bounds the time a huge or endless listing takes. */
    const val TREE_ENTRIES = 100_000

    /** The whole of [input], or null once it passes [maxBytes]; never more than that is buffered. Blocking. */
    fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > maxBytes) return null
            out.write(buffer, 0, n)
        }
    }

    /**
     * Files a crashed import or save left behind: `import-*.zip` copies in [cacheDir], `*.part`
     * files in [filesDir]'s pieces and art and the cache's roll cards, and whatever the web panel's
     * uploads left in `cacheDir/web` (an `upload-*.zip` read when the app stopped, a server's
     * temporary file). Only files last written before [before] (when the app started), so an import
     * that has just begun keeps its copy. Returns how many were deleted. Blocking.
     */
    fun sweepStale(cacheDir: File, filesDir: File, before: Long): Int {
        val candidates = cacheDir.listFiles { f -> f.name.startsWith("import-") && f.name.endsWith(".zip") }.orEmpty().asList() +
            listOf(File(filesDir, "pieces"), File(filesDir, "art"), File(cacheDir, "rollcards"))
                .flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".part") }.orEmpty().asList() } +
            File(cacheDir, WEB_DIR).listFiles().orEmpty().asList()
        return candidates.count { it.isFile && it.lastModified() < before && it.delete() }
    }

    /** The web panel's uploads and temporary files: `cacheDir/web`. */
    const val WEB_DIR = "web"
}
