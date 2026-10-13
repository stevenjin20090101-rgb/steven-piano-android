// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.update.Manifests
import dev.stevenjin.stevenpiano.update.UpdateFailures
import dev.stevenjin.stevenpiano.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The updater in words (DESIGN.md › v1.4 › Updates): the progress, the restart, and which state has a line under Check now. */
class UpdateCopyTest {
    @Test
    fun `the progress counts megabytes, and the restart is asked for only when needed`() {
        assertEquals("Downloading 1.4 · 1 of 14 MB", UpdateCopy.downloading("1.4", 1_200_000, 13_700_000, Locale.US))
        assertEquals("Updated to 1.4; restart to use it", UpdateCopy.installed("1.4"))
        assertEquals("Updated to 1.4", UpdateCopy.installed("1.4", restartNeeded = false))
    }

    @Test
    fun `the line under Check now says what the last check found`() {
        assertNull(UpdateCopy.checkLine(UpdateState.Idle))
        assertEquals(UpdateFailures.UNREACHABLE, UpdateCopy.checkLine(UpdateState.Failed(UpdateFailures.UNREACHABLE)))
        assertEquals("Version 1.4 is available.", UpdateCopy.checkLine(UpdateState.Available(Manifests.manifest())))
        assertEquals("Version 1.4 is available.", UpdateCopy.checkLine(UpdateState.Failed(UpdateFailures.MISMATCH, Manifests.manifest())))
        assertNull(UpdateCopy.checkLine(UpdateState.Installed("1.4")))
    }
}
