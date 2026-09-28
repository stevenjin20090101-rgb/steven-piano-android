// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

import dev.stevenjin.stevenpiano.web.LoginGuard
import org.junit.Assert.assertEquals
import org.junit.Test

/** The kiosk PIN's wrong tries (plan › M20): 0, 0, 0, 5 s, 10 s, 20 s… up to 5 min, and kept across a restart. */
class PinGuardTest {
    private var now = 1_000_000L
    private val wrong: () -> Boolean = { false }
    private val right: () -> Boolean = { true }

    @Test
    fun `three wrong PINs are free, then 5 s, 10 s, 20 s, doubling to five minutes`() {
        val guard = KioskPinGuard({ now })
        val waits = (1..11).map {
            now += guard.waitMs()   // the person waits out whatever wait is in force, then tries
            (guard.attempt(wrong) as LoginGuard.Attempt.Wrong).waitMs
        }
        assertEquals(listOf(0L, 0L, 0L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), waits)
        assertEquals(KioskPinGuard.Strikes(11, now + 300_000L), guard.strikes)
    }

    @Test
    fun `a try during a wait is refused and not counted, and the wait counts down`() {
        val guard = KioskPinGuard({ now })
        repeat(4) { guard.attempt(wrong) }
        assertEquals(5_000L, guard.waitMs())
        assertEquals(LoginGuard.Attempt.Wait(5_000L), guard.attempt { error("never weighed during a wait") })
        now += 2_000
        assertEquals(3_000L, guard.waitMs())
        assertEquals(4, guard.strikes.count)
        now += 3_000
        assertEquals("the next wrong try doubles the wait", LoginGuard.Attempt.Wrong(10_000L), guard.attempt(wrong))
    }

    @Test
    fun `the right PIN starts the count again, and so does a new PIN`() {
        val guard = KioskPinGuard({ now })
        repeat(4) { guard.attempt(wrong) }
        now += guard.waitMs()
        assertEquals(LoginGuard.Attempt.Right, guard.attempt(right))
        assertEquals(KioskPinGuard.Strikes.NONE, guard.strikes)
        repeat(3) { assertEquals(LoginGuard.Attempt.Wrong(0L), guard.attempt(wrong)) }
        guard.clear()
        assertEquals(KioskPinGuard.Strikes.NONE, guard.strikes)
        repeat(3) { assertEquals("three free again after a new PIN", LoginGuard.Attempt.Wrong(0L), guard.attempt(wrong)) }
    }

    @Test
    fun `a restart gives nobody the free tries back`() {
        val before = KioskPinGuard({ now })
        repeat(6) {
            now += before.waitMs()
            before.attempt(wrong)
        }
        val kept = before.strikes
        assertEquals(KioskPinGuard.Strikes(6, now + 20_000L), kept)
        now += 7_000   // the tablet restarts meanwhile
        val after = KioskPinGuard({ now }, restored = kept)
        assertEquals("the same wait, where it had got to", 13_000L, after.waitMs())
        assertEquals(kept, after.strikes)
        assertEquals(LoginGuard.Attempt.Wait(13_000L), after.attempt(wrong))
        now += 13_000
        assertEquals("and the next wrong try doubles on", LoginGuard.Attempt.Wrong(40_000L), after.attempt(wrong))
        assertEquals(KioskPinGuard.Strikes(7, now + 40_000L), after.strikes)
    }

    @Test
    fun `a wait that ended while the app was away is over, but the count stands`() {
        val kept = KioskPinGuard.Strikes(5, lockedUntil = now - 60_000L)
        val guard = KioskPinGuard({ now }, restored = kept)
        assertEquals(0L, guard.waitMs())
        assertEquals(LoginGuard.Attempt.Wrong(20_000L), guard.attempt(wrong))
        val underThree = KioskPinGuard({ now }, restored = KioskPinGuard.Strikes(2, 0L))
        assertEquals(0L, underThree.waitMs())
        assertEquals("the third is still free", LoginGuard.Attempt.Wrong(0L), underThree.attempt(wrong))
        assertEquals(LoginGuard.Attempt.Wrong(5_000L), underThree.attempt(wrong))
    }

    @Test
    fun `whatever the settings kept, the wait is never longer than five minutes`() {
        val guard = KioskPinGuard({ now }, restored = KioskPinGuard.Strikes(Int.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(300_000L, guard.waitMs())
        val nonsense = KioskPinGuard({ now }, restored = KioskPinGuard.Strikes(-4, -1L))
        assertEquals(KioskPinGuard.Strikes.NONE, nonsense.strikes)
        assertEquals(0L, nonsense.waitMs())
    }
}
