// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.concurrent.thread

/** The panel's PIN, sessions and login guard (the audit's point 3). */
class WebAuthTest {
    @Test
    fun `a PIN is six digits and nothing else`() {
        assertTrue(PinHash.isValid("123456"))
        assertTrue(PinHash.isValid("000000"))
        for (bad in listOf("", "12345", "1234567", "12345a", " 23456", "１２３４５６", "12 456")) assertFalse(bad, PinHash.isValid(bad))
        assertThrows(IllegalArgumentException::class.java) { PinHash.create("12345") }
    }

    @Test
    fun `the hash is PBKDF2-HMAC-SHA256 with 100,000 rounds over a random 16-byte salt`() {
        val pin = PinHash.create("482913")
        val salt = Base64.getDecoder().decode(pin.saltText)
        val hash = Base64.getDecoder().decode(pin.hashText)
        assertEquals(16, salt.size)
        assertEquals(32, hash.size)
        assertEquals(100_000, PinHash.ITERATIONS)
        // Worked out independently with the JDK's own PBKDF2.
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("482913".toCharArray(), salt, 100_000, 256)).encoded
        assertTrue(expected.contentEquals(hash))
        assertTrue(pin.matches("482913"))
        assertFalse(pin.matches("482914"))
        assertFalse(pin.matches("48291"))
        assertFalse("nothing prints the hash", pin.toString().contains(pin.hashText))
    }

    @Test
    fun `every PIN gets its own salt, so the same digits never hash the same`() {
        val a = PinHash.create("111111")
        val b = PinHash.create("111111")
        assertNotEquals(a.saltText, b.saltText)
        assertNotEquals(a.hashText, b.hashText)
        assertTrue(a.matches("111111") && b.matches("111111"))
    }

    @Test
    fun `a kept hash comes back only whole`() {
        val pin = PinHash.create("246810", SecureRandom())
        val restored = PinHash.restore(pin.saltText, pin.hashText)
        assertTrue(restored!!.matches("246810"))
        assertNull(PinHash.restore(null, pin.hashText))
        assertNull(PinHash.restore(pin.saltText, null))
        assertNull(PinHash.restore("not base64!", pin.hashText))
        assertNull("a short salt", PinHash.restore(Base64.getEncoder().encodeToString(ByteArray(8)), pin.hashText))
        assertNull("a short hash", PinHash.restore(pin.saltText, Base64.getEncoder().encodeToString(ByteArray(16))))
    }

    @Test
    fun `comparisons run over every byte whatever the first difference`() {
        assertTrue(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(ConstantTime.equals(byteArrayOf(9, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2), byteArrayOf(1, 2, 0)))
        assertTrue(ConstantTime.equals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `sessions are random 32-byte tokens, at most ten, forgotten after a day unused`() {
        var now = 1_000_000L
        val sessions = Sessions(clock = { now })
        val first = sessions.open()
        assertTrue(Sessions.TOKEN.matches(first))
        assertTrue(sessions.isValid(first))
        assertFalse(sessions.isValid(null))
        assertFalse(sessions.isValid(first.dropLast(1) + if (first.last() == 'A') "B" else "A"))
        assertFalse("not a token at all", sessions.isValid("../../etc/passwd"))

        val more = (1..9).map { sessions.open() }
        assertEquals(10, sessions.count())
        now += 1_000
        sessions.isValid(more[0])   // used: the least recently used is now `first`
        val eleventh = sessions.open()
        assertEquals(10, sessions.count())
        assertFalse("the least recently used went", sessions.isValid(first))
        assertTrue(sessions.isValid(more[0]))
        assertTrue(sessions.isValid(eleventh))

        now += Sessions.IDLE_MS - 1
        assertTrue("used a moment ago", sessions.isValid(eleventh))
        now += Sessions.IDLE_MS
        assertFalse("a day without a request", sessions.isValid(eleventh))
        assertEquals(0, sessions.count())
    }

    @Test
    fun `logging out ends one session, and closing all ends every one`() {
        val sessions = Sessions()
        val a = sessions.open()
        val b = sessions.open()
        sessions.close(a)
        assertFalse(sessions.isValid(a))
        assertTrue(sessions.isValid(b))
        sessions.closeAll()
        assertFalse(sessions.isValid(b))
        assertNotEquals("tokens never repeat", sessions.open(), sessions.open())
    }

    @Test
    fun `five wrong PINs lock the address for 30 s, then each doubles to ten minutes`() {
        var now = 0L
        val guard = LoginGuard(clock = { now })
        repeat(4) { assertEquals(0L, guard.failed("10.0.0.2")) }
        assertEquals(0L, guard.waitMs("10.0.0.2"))
        assertEquals(30_000L, guard.failed("10.0.0.2"))
        assertEquals(30_000L, guard.waitMs("10.0.0.2"))
        now += 10_000
        assertEquals(20_000L, guard.waitMs("10.0.0.2"))
        val waits = mutableListOf<Long>()
        repeat(6) {
            now += guard.waitMs("10.0.0.2")
            waits += guard.failed("10.0.0.2")
        }
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), waits)
    }

    @Test
    fun `one address hammering wrong PINs no longer locks the gate for everyone (audit W1)`() {
        val now = 0L
        val guard = LoginGuard(clock = { now })
        // Five wrong PINs lock the one address that sent them, for thirty seconds...
        repeat(5) { guard.failed("10.0.0.1") }
        assertEquals(30_000L, guard.waitMs("10.0.0.1"))
        // ...but a different address is still free: the old shared lock barred everyone here.
        assertEquals("a fresh address is not locked by another's failures", 0L, guard.waitMs("10.9.9.9"))
        // A whole burst from one address still cannot reach the (much higher) global threshold: once
        // that address is locked its further tries are refused uncounted, so nobody else is affected.
        repeat(50) { if (guard.waitMs("10.0.0.1") == 0L) guard.failed("10.0.0.1") }
        assertEquals("one address cannot lock everyone", 0L, guard.waitMs("10.9.9.9"))
    }

    @Test
    fun `the global gate still bounds a distributed brute force, but gently and capped at a minute`() {
        var now = 0L
        val guard = LoginGuard(clock = { now })
        // One under the global threshold, spread over addresses that each stay below their own
        // five-in-a-row lock (four each), and a fresh caller is still free.
        repeat(LoginGuard.GLOBAL_THRESHOLD - 1) { guard.failed("10.1.${it / 4}.${it % 4}") }
        assertEquals("under the global threshold nobody else is locked", 0L, guard.waitMs("10.9.9.9"))
        // The threshold'th wrong PIN trips the global gate — for five seconds, not the thirty the old shared lock gave after five.
        guard.failed("10.1.9.9")
        assertEquals(LoginGuard.GLOBAL_FIRST_LOCK_MS, guard.waitMs("10.9.9.9"))
        // However long the flood runs (rotating addresses dodge the per-address lock), the global wait doubles but never past a minute.
        val waits = mutableListOf<Long>()
        var fresh = 0
        repeat(8) {
            now += guard.waitMs("10.2.2.2")
            waits += guard.failed("10.3.${fresh / 200}.${fresh % 200}").also { fresh++ }
        }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L), waits)
        assertTrue("the global wait is capped at a minute, unlike the per-address ten", waits.all { it <= LoginGuard.GLOBAL_MAX_LOCK_MS })
        // A right PIN from anyone clears the global count, freeing every address at once.
        guard.succeeded("10.9.9.9")
        assertEquals(0L, guard.waitMs("10.9.9.9"))
    }

    @Test
    fun `tries sent at once are weighed one at a time, and only until the lock (audit W2)`() {
        val guard = LoginGuard(clock = { 0L })
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val weighed = AtomicInteger()
        val outcomes: MutableList<LoginGuard.Attempt> = Collections.synchronizedList(mutableListOf())
        val go = CountDownLatch(1)
        val tries = (1..12).map {
            thread {
                go.await()
                outcomes += guard.attempt("10.0.0.7") {
                    most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    weighed.incrementAndGet()
                    Thread.sleep(20)   // a derivation takes a while: the others arrive meanwhile
                    inside.decrementAndGet()
                    false
                }
            }
        }
        go.countDown()
        tries.forEach { it.join() }
        assertEquals("never two derivations at once", 1, most.get())
        assertEquals("exactly the five before the lock are weighed", LoginGuard.THRESHOLD, weighed.get())
        assertEquals(LoginGuard.THRESHOLD, outcomes.count { it is LoginGuard.Attempt.Wrong })
        assertEquals("the rest are refused uncounted", 12 - LoginGuard.THRESHOLD, outcomes.count { it is LoginGuard.Attempt.Wait })
        assertEquals("a locked key is refused before it waits on anyone's derivation", LoginGuard.Attempt.Wait(30_000L), guard.attempt("10.0.0.7") { error("not weighed") })
        assertEquals("a right answer from another key clears everyone", LoginGuard.Attempt.Right, guard.attempt("10.0.0.8") { true })
    }

    @Test
    fun `the guard remembers a bounded number of addresses`() {
        val guard = LoginGuard(clock = { 0L }, maxKeys = 3, threshold = 100)
        (1..10).forEach { guard.failed("10.0.0.$it") }
        assertEquals(3, guard.keyCount())
        assertEquals(0L, guard.waitMs("10.0.0.1"))
    }
}
