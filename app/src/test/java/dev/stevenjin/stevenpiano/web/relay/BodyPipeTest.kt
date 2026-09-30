// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A relayed body: its chunks in order under the window, credit given back as it is read, its end, its failures. */
class BodyPipeTest {
    private val credits: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    @Test
    fun `chunks are read back in order across their edges, then the end`() {
        val pipe = BodyPipe(capacity = 1_000, onCredit = { credits += it }, creditStep = 100)
        assertTrue(pipe.offer(byteArrayOf(1, 2, 3)))
        assertTrue(pipe.offer(byteArrayOf(4)))
        assertTrue(pipe.offer(ByteArray(0)))
        assertTrue(pipe.offer(byteArrayOf(5, 6, 7, 8, 9)))
        pipe.end()
        assertFalse("nothing after the end", pipe.offer(byteArrayOf(10)))
        val two = ByteArray(2)
        assertEquals(2, pipe.read(two, 0, 2))
        assertArrayEquals(byteArrayOf(1, 2), two)
        assertEquals(3, pipe.read())
        val rest = ByteArray(10)
        assertEquals("a read takes what is queued, across chunks", 6, pipe.read(rest, 0, 10))
        assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8, 9), rest.copyOf(6))
        assertEquals(-1, pipe.read(rest, 0, 10))
        assertEquals(-1, pipe.read())
        assertEquals(9L, pipe.received)
        assertTrue(pipe.drained)
    }

    @Test
    fun `what is read goes back as credit, in steps, and all of it before a read waits`() {
        val pipe = BodyPipe(capacity = 1_000, onCredit = { credits += it }, creditStep = 100)
        repeat(3) { assertTrue(pipe.offer(ByteArray(90) { 1 })) }
        val buffer = ByteArray(60)
        assertEquals(60, pipe.read(buffer, 0, 60))
        assertEquals("under a step: nothing yet", emptyList<Int>(), credits.toList())
        assertEquals(60, pipe.read(buffer, 0, 60))
        assertEquals(listOf(120), credits.toList())
        assertEquals(60, pipe.read(buffer, 0, 60))
        assertEquals(60, pipe.read(buffer, 0, 60))
        assertEquals(30, pipe.read(buffer, 0, 60))
        assertEquals(listOf(120, 120), credits.toList())
        // The queue is empty and 30 bytes are owed: they go before the read waits for more.
        val reader = Thread { runCatching { pipe.read(buffer, 0, 60) } }.apply { start() }
        waitFor { credits.sum() == 270 }
        assertEquals(listOf(120, 120, 30), credits.toList())
        pipe.end()
        reader.join(2_000)
        assertFalse(reader.isAlive)
    }

    @Test
    fun `the window holds, a chunk past it is refused and nothing of it kept`() {
        val pipe = BodyPipe(capacity = 100, onCredit = { credits += it })
        assertTrue(pipe.offer(ByteArray(60)))
        assertFalse(pipe.offer(ByteArray(41)))
        assertTrue(pipe.offer(ByteArray(40)))
        assertEquals(100, pipe.available())
        assertEquals(100L, pipe.received)
        assertEquals(100, pipe.read(ByteArray(200), 0, 200))
        assertTrue("room again once read", pipe.offer(ByteArray(100)))
    }

    @Test
    fun `a read waits for the next chunk, and fails once the body stalls`() {
        val pipe = BodyPipe(capacity = 100, onCredit = { credits += it }, idleMs = 300)
        val got = AtomicReference<Any?>(null)
        val started = CountDownLatch(1)
        val reader = Thread {
            started.countDown()
            got.set(runCatching { pipe.read() }.getOrElse { it })
        }.apply { start() }
        started.await()
        Thread.sleep(100)
        pipe.offer(byteArrayOf(42))
        reader.join(2_000)
        assertEquals(42, got.get())
        val began = System.nanoTime()
        val stalled = runCatching { pipe.read() }.exceptionOrNull()
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        assertTrue("stalled: $stalled", stalled is SocketTimeoutException)
        assertTrue("after the idle limit: $tookMs ms", tookMs in 250..2_000)
    }

    @Test
    fun `an abort ends a waiting read and every later one, and a closed pipe takes nothing more`() {
        val pipe = BodyPipe(capacity = 100, onCredit = { credits += it })
        val failure = AtomicReference<Throwable?>(null)
        val reader = Thread { failure.set(runCatching { pipe.read() }.exceptionOrNull()) }.apply { start() }
        Thread.sleep(100)
        pipe.abort()
        reader.join(2_000)
        assertTrue(failure.get() is IOException)
        assertTrue(runCatching { pipe.read() }.exceptionOrNull() is IOException)
        assertFalse(pipe.offer(byteArrayOf(1)))

        val closed = BodyPipe(capacity = 100, onCredit = { credits += it })
        closed.offer(byteArrayOf(1, 2))
        closed.close()
        assertEquals(0, closed.available())
        assertFalse(closed.offer(byteArrayOf(3)))
        assertTrue(runCatching { closed.read() }.exceptionOrNull() is IOException)
    }

    @Test
    fun `a body larger than the window streams through as the reader takes it`() {
        val window = 64 * 1024
        val credit = java.util.concurrent.Semaphore(window)
        val pipe = BodyPipe(capacity = window, onCredit = { credit.release(it) }, creditStep = 16 * 1024)
        val body = ByteArray(1_000_000) { (it % 251).toByte() }
        val sender = Thread {
            var at = 0
            while (at < body.size) {
                val n = minOf(8_192, body.size - at)
                credit.acquire(n)   // the room sends only what it was granted
                check(pipe.offer(body.copyOfRange(at, at + n))) { "over the window at $at" }
                at += n
            }
            pipe.end()
        }.apply { start() }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(5_000)
        while (true) {
            val n = pipe.read(buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        sender.join(5_000)
        assertArrayEquals(body, out.toByteArray())
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 2_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out")
            Thread.sleep(10)
        }
    }
}
