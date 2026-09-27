// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app's own crash reports, on the device only: `filesDir/diagnostics/crash-<epoch ms>.txt`,
 * the last [KEEP]. The uncaught-exception handler ([dev.stevenjin.stevenpiano.CrashSilencer])
 * writes one after the piano has been silenced: the time, [header] (the app's version and build,
 * the device and its Android), the thread, the stack trace, and the link's last lines
 * ([linkTail]), which would otherwise go with the process. Nothing is sent anywhere; the person
 * shares them, zipped, from the Piano tab. Exception messages are kept, with content and file
 * URIs, storage paths and web addresses' paths taken out ([scrub]), so a file or piece name that a
 * message quotes does not travel with it.
 */
class CrashReports(
    private val dir: File,
    private val header: () -> String,
    private val linkTail: () -> List<String> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Writes the report for [error] on [thread], then keeps the newest [KEEP]. Never throws: it runs while the app is going down. */
    fun write(thread: Thread, error: Throwable) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val at = clock()
            val text = buildString {
                append("Steven Piano crash report\n")
                append("Time: ").append(STAMP.format(Instant.ofEpochMilli(at).atZone(zone))).append('\n')
                append(safely(header)).append('\n')
                append("Thread: ").append(thread.name).append('\n')
                append('\n')
                append(stackOf(error))
                val tail = try {
                    linkTail()
                } catch (t: Throwable) {
                    emptyList()
                }
                if (tail.isNotEmpty()) {
                    append("\nPiano link, last ").append(tail.size).append(" lines:\n")
                    tail.forEach { append(it).append('\n') }
                }
            }
            var file = File(dir, "$PREFIX$at$SUFFIX")
            var n = 1
            while (file.exists()) file = File(dir, "$PREFIX$at-${n++}$SUFFIX")
            file.writeText(text)
            reports().drop(KEEP).forEach { it.delete() }
        } catch (t: Throwable) {
            // The crash itself matters more; the report is best effort.
        }
    }

    /** The reports kept, newest first. */
    fun reports(): List<File> {
        val files = dir.listFiles { file -> file.isFile && epochOf(file.name) != null } ?: return emptyList()
        return files.sortedWith(compareByDescending<File> { epochOf(it.name) }.thenByDescending { it.name })
    }

    /** When the newest report was written (epoch ms), or null when there is none. */
    fun latestAt(): Long? = reports().firstOrNull()?.let { epochOf(it.name) }

    companion object {
        const val KEEP = 5
        const val PREFIX = "crash-"
        const val SUFFIX = ".txt"

        /** A stack trace is cut here (a StackOverflowError's runs to a thousand frames). */
        const val MAX_STACK_CHARS = 64 * 1024

        /** Lines of the link's trail a report carries. */
        const val LINK_LINES = 50

        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx", Locale.ROOT)
        private val NAME = Regex("""crash-(\d+)(-\d+)?\.txt""")
        private val CONTENT = Regex("""\b(content|file)://\S+""")
        private val WEB = Regex("""\b(https?://[^/\s:]+)[^\s]*""")
        private val STORAGE = Regex("""/(storage|sdcard|mnt/media_rw)/\S+""")

        /** The epoch in a report's file name, or null for any other file. */
        fun epochOf(name: String): Long? = NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

        /** [text] without the places a message may name: content and file URIs, shared-storage paths, a web address's path. */
        fun scrub(text: String): String = text
            .replace(CONTENT) { "${it.groupValues[1]}://(removed)" }
            .replace(STORAGE) { "/${it.groupValues[1]}/(removed)" }
            .replace(WEB) { "${it.groupValues[1]}/(removed)" }

        private fun stackOf(error: Throwable): String {
            val out = StringWriter()
            try {
                error.printStackTrace(PrintWriter(out))
            } catch (t: Throwable) {
                out.write(error.javaClass.name)
            }
            val text = scrub(out.toString())
            return if (text.length <= MAX_STACK_CHARS) text else text.substring(0, MAX_STACK_CHARS) + "\n…(cut)\n"
        }

        private fun safely(block: () -> String): String = try {
            block()
        } catch (t: Throwable) {
            "(app and device not known)"
        }
    }
}
