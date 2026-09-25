// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.PianoLink

/**
 * The process-wide handler for a crash nothing else caught: before Android ends the process, the
 * piano gets the stop sequence straight away (CC64 = 0 then CC123, [PianoLink.emergencySilence],
 * at most [TIMEOUT_MS]), so no key or pedal waits for the piano to notice the lost connection or
 * for its hold watchdog. Then the crash goes on to [previous], Android's own handler, exactly as it
 * would have. [link] is the piano link if one exists (the handler never creates one); it is
 * silenced only while connected. Nothing here may throw.
 */
class CrashSilencer(
    private val link: () -> PianoLink?,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val piano = link()
            if (piano != null && piano.state.value is LinkState.Connected) piano.emergencySilence(TIMEOUT_MS)
        } catch (t: Throwable) {
            // The crash itself matters more: it still reaches Android's handler below.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        /** The most the stop may hold up a crash: a busy Bluetooth stack gets this long to take it. */
        const val TIMEOUT_MS = 200L

        /** Becomes the default handler, in front of the one there now. */
        fun install(link: () -> PianoLink?) {
            Thread.setDefaultUncaughtExceptionHandler(CrashSilencer(link, Thread.getDefaultUncaughtExceptionHandler()))
        }
    }
}
