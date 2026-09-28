// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Studio's memory gates (v1.7 — M23), from the spike's measurements: 2.5 GiB to offer it, 900 MiB free to start. */
class MemoryGateTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    @Test
    fun `Studio is offered from 2_5 GiB of memory, so a 3 GB tablet passes and a 2 GB one does not`() {
        assertEquals(2_684_354_560L, MemoryGate.OFFER_TOTAL_BYTES)
        assertTrue(MemoryGate.offered(MemoryGate.OFFER_TOTAL_BYTES))
        assertFalse(MemoryGate.offered(MemoryGate.OFFER_TOTAL_BYTES - 1))
        assertTrue("a device sold with 3 GB reports about 2.8 GB", MemoryGate.offered(2_900_000_000L))
        assertFalse("the emulator's 2 GB profile", MemoryGate.offered(2 * gib - 100 * mib))
        assertTrue("the emulator booted with -memory 4096", MemoryGate.offered(4 * gib - 200 * mib))
    }

    @Test
    fun `a transcription starts only with 900 MiB free above Android's threshold, and never while memory is low`() {
        assertEquals(900 * mib, MemoryGate.TRANSCRIPTION_FREE_BYTES)
        val threshold = 216 * mib
        assertTrue(MemoryGate.canStart(MemorySnapshot(4 * gib, threshold + 900 * mib, threshold, lowMemory = false)))
        assertFalse(MemoryGate.canStart(MemorySnapshot(4 * gib, threshold + 900 * mib - 1, threshold, lowMemory = false)))
        assertFalse("low memory refuses however much is free", MemoryGate.canStart(MemorySnapshot(4 * gib, 3 * gib, threshold, lowMemory = true)))
        assertFalse("a threshold above what is free", MemoryGate.canStart(MemorySnapshot(4 * gib, 100 * mib, threshold, lowMemory = false)))
    }

    @Test
    fun `a composition starts only with 700 MiB free above Android's threshold, and never while memory is low`() {
        assertEquals(700 * mib, MemoryGate.COMPOSING_FREE_BYTES)
        assertEquals(734_003_200L, MemoryGate.COMPOSING_FREE_BYTES)
        val threshold = 216 * mib
        assertTrue(MemoryGate.canStartComposing(MemorySnapshot(4 * gib, threshold + 700 * mib, threshold, lowMemory = false)))
        assertFalse(MemoryGate.canStartComposing(MemorySnapshot(4 * gib, threshold + 700 * mib - 1, threshold, lowMemory = false)))
        assertFalse(MemoryGate.canStartComposing(MemorySnapshot(4 * gib, 3 * gib, threshold, lowMemory = true)))
        assertFalse("700 MiB is not enough to transcribe", MemoryGate.canStart(MemorySnapshot(4 * gib, threshold + 700 * mib, threshold, lowMemory = false)))
    }

    @Test
    fun `a transcription under way stops only when the memory is nearly gone`() {
        val threshold = 216 * mib
        assertTrue(MemoryGate.canContinue(MemorySnapshot(4 * gib, threshold + 128 * mib, threshold, lowMemory = false)))
        assertFalse(MemoryGate.canContinue(MemorySnapshot(4 * gib, threshold + 128 * mib - 1, threshold, lowMemory = false)))
        assertFalse(MemoryGate.canContinue(MemorySnapshot(4 * gib, 2 * gib, threshold, lowMemory = true)))
    }

    @Test
    fun `support asks the runtime only when the memory would do, and the emulator can play the other devices`() {
        var asked = 0
        val loads = { asked++; true }
        val fails = { asked++; false }
        assertEquals(StudioSupport.Available, MemoryGate.support(4 * gib, loads))
        assertEquals(1, asked)
        assertEquals(StudioSupport.NoRuntime, MemoryGate.support(4 * gib, fails))
        assertEquals(2, asked)
        assertEquals(StudioSupport.TooLittleMemory, MemoryGate.support(2 * gib, loads))
        assertEquals("a small device never loads the 28 MB library", 2, asked)
        assertEquals(StudioSupport.NoRuntime, MemoryGate.support(4 * gib, loads, MemoryGate.OVERRIDE_NO_RUNTIME))
        assertEquals(StudioSupport.TooLittleMemory, MemoryGate.support(4 * gib, loads, MemoryGate.OVERRIDE_LOW_MEMORY))
        assertEquals(2, asked)
        assertEquals("an unknown override is no override", StudioSupport.Available, MemoryGate.support(4 * gib, loads, "something"))
    }
}
