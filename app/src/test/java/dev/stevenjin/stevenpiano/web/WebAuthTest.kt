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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.concurrent.thread

/** The panel's PIN, sessions and login guard (the audit's point 3); the sessions' file (v1.15 — M42). */
class WebAuthTest {
    @get:Rule
    val tmp = TemporaryFolder()

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
    fun `sessions are random 32-byte tokens, at most sixteen, forgotten after a year unused`() {
        var now = 1_000_000L
        val sessions = Sessions(clock = { now })
        val first = sessions.open()
        assertTrue(Sessions.TOKEN.matches(first))
        assertTrue(sessions.isValid(first))
        assertFalse(sessions.isValid(null))
        assertFalse(sessions.isValid(first.dropLast(1) + if (first.last() == 'A') "B" else "A"))
        assertFalse("not a token at all", sessions.isValid("../../etc/passwd"))

        val more = (1..15).map { sessions.open() }
        assertEquals(16, sessions.count())
        now += 1_000
        sessions.isValid(more[0])   // used: the least recently used is now `first`
        val seventeenth = sessions.open()
        assertEquals(16, sessions.count())
        assertFalse("the least recently used went", sessions.isValid(first))
        assertTrue(sessions.isValid(more[0]))
        assertTrue(sessions.isValid(seventeenth))

        assertEquals(365 * 24 * 60 * 60 * 1000L, Sessions.IDLE_MS)
        now += Sessions.IDLE_MS - 1
        assertTrue("used a moment ago", sessions.isValid(seventeenth))
        now += Sessions.IDLE_MS
        assertFalse("a year without a request", sessions.isValid(seventeenth))
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
    fun `a session outlives a restart of the app, and one a year unused does not (M42)`() {
        val start = 1_700_000_000_000L
        var now = start
        val file = File(tmp.root, "web/sessions.json")
        val before = Sessions(FileSessionStore(file), clock = { now })
        val kept = before.open()
        now += 1_000
        val idle = before.open()
        val text = file.readText()
        assertTrue("the file holds the tokens' digests…", sha256(kept) in text && sha256(idle) in text)
        assertFalse("…and never a token", kept in text || idle in text)

        now += 30_000
        assertTrue(before.isValid(kept))
        assertEquals("a use within the minute is not written", start, FileSessionStore(file).load()[sha256(kept)])
        now += 31_000
        assertTrue(before.isValid(kept))
        assertEquals("past the minute it is", now, FileSessionStore(file).load()[sha256(kept)])

        val restarted = Sessions(FileSessionStore(file), clock = { now })   // the app starts again
        assertEquals(2, restarted.count())
        assertTrue("still signed in", restarted.isValid(kept))

        now = start + 1_000 + Sessions.IDLE_MS   // a year after `idle` was last used
        val yearOn = Sessions(FileSessionStore(file), clock = { now })
        assertFalse("a year unused", yearOn.isValid(idle))
        assertTrue("used since, so still remembered", yearOn.isValid(kept))
        assertEquals(1, yearOn.count())
    }

    @Test
    fun `the seventeenth session sends the least recently used away, from the file too (M42)`() {
        var now = 1_700_000_000_000L
        val file = File(tmp.root, "web/sessions.json")
        val sessions = Sessions(FileSessionStore(file), clock = { now })
        val tokens = (1..16).map {
            now += 1_000
            sessions.open()
        }
        now += 1_000
        assertTrue(sessions.isValid(tokens[0]))   // used: tokens[1] is now the least recently used
        val seventeenth = sessions.open()
        val saved = FileSessionStore(file).load()
        assertEquals(16, saved.size)
        assertFalse("the least recently used went from the file", sha256(tokens[1]) in saved)
        assertTrue(sha256(tokens[0]) in saved && sha256(seventeenth) in saved)

        val restarted = Sessions(FileSessionStore(file), clock = { now })
        assertEquals(16, restarted.count())
        assertFalse(restarted.isValid(tokens[1]))
        assertTrue(restarted.isValid(tokens[0]))
        assertTrue(restarted.isValid(seventeenth))
        restarted.open()
        assertFalse("after a restart too, the least recently used goes first", restarted.isValid(tokens[2]))
        assertTrue(restarted.isValid(tokens[3]))
    }

    @Test
    fun `logging out takes a session out of the file, and closing all empties it (M42)`() {
        val file = File(tmp.root, "web/sessions.json")
        val sessions = Sessions(FileSessionStore(file))
        val a = sessions.open()
        val b = sessions.open()
        sessions.close(a)
        assertEquals(setOf(sha256(b)), FileSessionStore(file).load().keys)
        sessions.closeAll()   // a new PIN, or the web panel turned off
        assertEquals("{}", file.readText())
        val restarted = Sessions(FileSessionStore(file))
        assertFalse("nobody is signed in at the next start", restarted.isValid(a))
        assertFalse(restarted.isValid(b))
        assertEquals(0, restarted.count())
    }

    @Test
    fun `a missing or corrupt file reads as no sessions (M42)`() {
        val file = File(tmp.root, "web/sessions.json")
        assertEquals("missing", emptyMap<String, Long>(), FileSessionStore(file).load())
        val digest = "ab".repeat(32)
        file.parentFile!!.mkdirs()
        val corrupt = listOf(
            "", "not json", "[]", "{", "{\"$digest\":\"1700000000000\"}", "{\"$digest\":1.5}", "{\"$digest\":null}",
            "{\"$digest\":{\"at\":1}}", "{\"${digest.uppercase()}\":1}", "{\"$digest\":1,\"token\":2}",
        )
        for (text in corrupt) {
            file.writeText(text)
            assertEquals(text, emptyMap<String, Long>(), FileSessionStore(file).load())
        }
        file.writeText("{\"$digest\":1700000000000}" + " ".repeat(FileSessionStore.MAX_BYTES.toInt()))
        assertEquals("larger than any table", emptyMap<String, Long>(), FileSessionStore(file).load())
        file.writeText("{\"$digest\":1700000000000}")
        assertEquals(mapOf(digest to 1_700_000_000_000L), FileSessionStore(file).load())

        file.writeText("not json")
        val sessions = Sessions(FileSessionStore(file))
        assertEquals(0, sessions.count())
        val token = sessions.open()
        assertTrue("a corrupt file is written over whole", Sessions(FileSessionStore(file)).isValid(token))
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

    /**
     * Audit delta 3: tries spread over rotating addresses, one as soon as the wait allows, for a day. The
     * listeners' gate (W1's, chosen behind the tailnet) weighs ~1,440 of them: half the million PINs in about
     * a year. The relay's, which the whole internet reaches, ~40 the first day and 24 a day from then on: half
     * the PINs in some 57 years.
     */
    @Test
    fun `the relay's gate lets a distributed brute force some 24 tries a day, the listeners' some 1,440 (audit delta 3)`() {
        fun triesInADay(guard: LoginGuard, clock: LongArray): Int {
            var tries = 0
            var fresh = 0
            val day = 24 * 60 * 60 * 1000L
            while (clock[0] < day) {
                val wait = guard.waitMs("10.200.0.1")
                if (wait > 0) {
                    clock[0] += wait
                    continue
                }
                guard.failed("10.${fresh / 65_000}.${fresh / 250 % 260}.${fresh % 250}")   // a new address each time: no per-address lock
                fresh++
                tries++
                clock[0] += 1_000   // a second a try, far faster than the gate lets any through
            }
            return tries
        }
        val lanClock = LongArray(1)
        val lan = triesInADay(LoginGuard(clock = { lanClock[0] }, maxKeys = 1_000_000), lanClock)
        val relayClock = LongArray(1)
        val relay = triesInADay(LoginGuard.forRelay(clock = { relayClock[0] }), relayClock)
        assertTrue("the listeners' gate: about 1,440 a day ($lan)", lan in 1_400..1_500)
        assertTrue("the relay's gate: its first tries, then 24 a day at its cap ($relay)", relay in 30..45)
        // Its schedule: ten wrong in a row, then a minute, doubling to an hour.
        var now = 0L
        val guard = LoginGuard.forRelay(clock = { now })
        repeat(LoginGuard.RELAY_GLOBAL_THRESHOLD - 1) { guard.failed("10.1.0.$it") }
        assertEquals(0L, guard.waitMs("10.9.9.9"))
        assertEquals(60_000L, guard.failed("10.1.1.1"))
        val waits = mutableListOf<Long>()
        repeat(8) {
            now += guard.waitMs("10.9.9.9")
            waits += guard.failed("10.2.0.$it")
        }
        assertEquals(listOf(120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L, 3_600_000L, 3_600_000L), waits)
        // The per-address gate is the listeners' own (five, then 30 s); a right PIN still clears everyone's count.
        val one = LoginGuard.forRelay(clock = { 0L })
        repeat(LoginGuard.THRESHOLD) { one.failed("10.3.3.3") }
        assertEquals(30_000L, one.waitMs("10.3.3.3"))
        assertEquals("under the global ten, nobody else waits", 0L, one.waitMs("10.4.4.4"))
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

    private fun sha256(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
}
