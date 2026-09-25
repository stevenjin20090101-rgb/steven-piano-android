// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import java.io.FileNotFoundException

/**
 * Files another app hands over by "Open with" or Share go to the import service, which needs
 * the read access the sender gave this activity passed on to it. A sender that gave none (a
 * shell, a file manager that grants nothing) makes Android refuse that hand-over with
 * SecurityException, and a file that is gone reads as FileNotFoundException. Neither may crash
 * the app: the Library says the file couldn't be read instead. Pure, so it is unit-tested.
 */
object SharedFiles {
    enum class Outcome {
        /** The intent carried no files. */
        None,

        /** The import service has them. */
        Importing,

        /** The app may not read them: the Library says so and suggests Add files. */
        Unreadable,
    }

    /** Files one share may hand over at most; the rest are left out. */
    const val MAX_SHARED = 500

    /**
     * What an intent's files may be: content URIs only (a file:// path from another app could name
     * this app's own files, a FIFO or /proc; the manifest cannot filter a share's EXTRA_STREAM, nor an
     * explicit intent), at most [MAX_SHARED] of them. [scheme] reads a file's URI scheme.
     */
    fun <T> accepted(files: List<T>, scheme: (T) -> String?): List<T> = files.filter { scheme(it) == "content" }.take(MAX_SHARED)

    /** Hands [files] to [start] (the import service), and says how that went. Anything else [start] throws is a bug, and still throws. */
    fun <T> hand(files: List<T>, start: (List<T>) -> Unit): Outcome {
        if (files.isEmpty()) return Outcome.None
        return try {
            start(files)
            Outcome.Importing
        } catch (_: SecurityException) {
            Outcome.Unreadable
        } catch (_: FileNotFoundException) {
            Outcome.Unreadable
        }
    }
}
