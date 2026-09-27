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

/** What the updater says (DESIGN.md › v1.4 › Updates): the row's lines, the progress in megabytes, the line under Check now. */
class UpdateCopyTest {
    @Test
    fun `the row reads as the design writes it`() {
        assertEquals("Steven Piano 1.4 is available", UpdateCopy.available("1.4"))
        assertEquals("Downloading 1.4 · 1.2 of 2.3 MB", UpdateCopy.downloading("1.4", 1_200_000, 2_300_000, Locale.US))
        assertEquals("Updated to 1.4; restart to use it", UpdateCopy.installed("1.4"))
        assertEquals("Updated to 1.4", UpdateCopy.installed("1.4", restartNeeded = false))
        assertEquals("0.0 of 2.4 MB", UpdateCopy.megabytes(0, 2_400_301, Locale.US))
        assertEquals("1,2 of 2,4 MB", UpdateCopy.megabytes(1_240_000, 2_400_301, Locale.GERMANY))
        assertEquals("Downloading Steven Piano 1.4", UpdateCopy.notificationTitle("1.4"))
    }

    @Test
    fun `the line under Check now says what the last check found`() {
        assertNull(UpdateCopy.checkLine(UpdateState.Idle))
        assertEquals("Checking for updates…", UpdateCopy.checkLine(UpdateState.Checking))
        assertEquals("Steven Piano is up to date.", UpdateCopy.checkLine(UpdateState.UpToDate))
        assertEquals("Couldn't reach the update server.", UpdateCopy.checkLine(UpdateState.Failed(UpdateFailures.UNREACHABLE)))
        assertEquals("Version 1.4 is available.", UpdateCopy.checkLine(UpdateState.Available(Manifests.manifest())))
        assertEquals("Version 1.4 is available.", UpdateCopy.checkLine(UpdateState.Failed(UpdateFailures.MISMATCH, Manifests.manifest())))
        assertNull(UpdateCopy.checkLine(UpdateState.Installed("1.4")))
    }
}
