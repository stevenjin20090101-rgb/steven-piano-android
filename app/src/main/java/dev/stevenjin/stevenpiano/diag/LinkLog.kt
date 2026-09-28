// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The last [capacity] lines of the piano link's trail, each stamped with its local time, kept in
 * memory only: `link.log` in the diagnostics zip, and the tail of a crash report. The link writes
 * through [warn], the same call that puts each line in Android's log (Log.w, tag PianoLink, kept
 * in release builds). The lines hold what that log holds: Bluetooth addresses, the names devices
 * advertise, GATT status codes, and each playback run's timing (v1.7: "Timing: 3059 events, the latest
 * 6 ms after its time, at 1:15.5"); never a file name, title or setting. A line is cut at [MAX_LINE]
 * characters and kept on one line. Safe from any thread.
 */
class LinkLog(
    private val capacity: Int = CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val lines = ArrayDeque<String>(capacity)

    @Synchronized
    fun add(line: String) {
        val flat = line.take(MAX_LINE).replace('\n', ' ').replace('\r', ' ')
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast("${STAMP.format(Instant.ofEpochMilli(clock()).atZone(zone))} $flat")
    }

    /** Oldest first. */
    @Synchronized
    fun lines(): List<String> = lines.toList()

    /** The last [count] lines, oldest first. */
    @Synchronized
    fun tail(count: Int): List<String> = lines.toList().takeLast(count)

    /** The lines as a file's text, one a line. */
    fun text(): String = lines().joinToString("") { "$it\n" }

    companion object {
        const val CAPACITY = 500
        const val MAX_LINE = 400
        private const val TAG = "PianoLink"
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

        /** The process's one trail: the link writes it, diagnostics and crash reports read it. */
        val shared = LinkLog()

        /** A line of the link's trail: Android's log (kept in release builds) and [shared]. */
        fun warn(line: String) {
            Log.w(TAG, line)
            shared.add(line)
        }
    }
}
