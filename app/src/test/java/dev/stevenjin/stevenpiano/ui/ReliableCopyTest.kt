// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.diag.AppReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The System pages' "Runs reliably" (v1.39 — M77). */
class ReliableCopyTest {
    @Test
    fun `runs reliably is amber when any part is missing, with the first one's fix`() {
        val all = Reliability(deviceOwner = true, batteryUnrestricted = true, powerWatch = true)
        assertTrue(all.ok)
        assertNull(ReliableCopy.fix(all))
        assertEquals("Yes · device owner · battery unrestricted · watching the plug", ReliableCopy.value(all))
        assertTrue("no plug paired: nothing to watch", all.copy(powerWatch = null).ok)
        for (missing in listOf(all.copy(deviceOwner = false), all.copy(batteryUnrestricted = false), all.copy(powerWatch = false))) {
            assertFalse(missing.toString(), missing.ok)
        }
        assertEquals(ReliableCopy.FIX_OWNER, ReliableCopy.fix(Reliability(deviceOwner = false, batteryUnrestricted = false, powerWatch = false)))
        assertEquals(ReliableCopy.FIX_BATTERY, ReliableCopy.fix(all.copy(batteryUnrestricted = false, powerWatch = false)))
        assertEquals(ReliableCopy.FIX_WATCH, ReliableCopy.fix(all.copy(powerWatch = false)))
        assertEquals("No · not device owner · battery restricted", ReliableCopy.value(Reliability(deviceOwner = false, batteryUnrestricted = false, powerWatch = null)))
        assertNull("nothing until the app says", Reliability.of(AppReading()))
        assertEquals(all, Reliability.of(AppReading(deviceOwner = true, batteryUnrestricted = true, powerWatch = true)))
    }
}
