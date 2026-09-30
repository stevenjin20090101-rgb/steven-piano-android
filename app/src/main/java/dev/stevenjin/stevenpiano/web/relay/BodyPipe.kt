// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A relayed request's body as the server reads it (BUILD_SPEC.md › v1.10 — M26): the chunks the
 * room sends ([Frame.REQ_CHUNK]) queue here as they come, at most [capacity] bytes of them (the
 * credit window: the room never has more than that outstanding), and are read back in order, then
 * the end ([Frame.REQ_END]). What is read is given back as credit ([onCredit], in steps of at least
 * [creditStep] bytes, and whatever is owed before a read waits), so the room sends more as the body
 * is taken, and no faster.
 *
 * [offer] never waits: the connection's one reading thread calls it, and a chunk past the window is
 * the room breaking the protocol (false: the caller ends the request). A read waits at most [idleMs]
 * for the next chunk (then an IOException, as a socket's read timeout would give), and [abort] (the
 * room gave up, the browser went, the connection dropped) ends a waiting read, and every later one,
 * with an IOException. [close] is the reader giving up: what is queued goes, and nothing more is
 * taken. Safe between the reading thread and one request thread.
 */
class BodyPipe(
    private val capacity: Int,
    private val onCredit: (Int) -> Unit,
    private val idleMs: Long = IDLE_MS,
    private val creditStep: Int = CREDIT_STEP,
) : InputStream() {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var at = 0
    private var queued = 0
    private var owed = 0
    private var total = 0L
    private var ended = false
    private var aborted = false
    private var closed = false

    /** Bytes offered so far (the body's length once [end] has come). */
    val received: Long get() = lock.withLock { total }

    /** Whether the body has ended and every byte of it was read. */
    val drained: Boolean get() = lock.withLock { ended && queued == 0 }

    /**
     * A chunk from the room. False, and nothing kept, when it would take the queue past [capacity],
     * or after [end], [abort] or [close]: the room sent more than it was granted, or too late.
     */
    fun offer(bytes: ByteArray): Boolean = lock.withLock {
        if (ended || aborted || closed) return false
        if (bytes.isEmpty()) return true
        if (queued.toLong() + bytes.size > capacity) return false
        chunks.addLast(bytes)
        queued += bytes.size
        total += bytes.size
        arrived.signalAll()
        true
    }

    /** The body is complete: reads end (−1) once the queue is empty. */
    fun end() = lock.withLock {
        ended = true
        arrived.signalAll()
    }

    /** The body will not come: a waiting read, and every later one, fails. */
    fun abort() = lock.withLock {
        aborted = true
        chunks.clear()
        queued = 0
        arrived.signalAll()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        var credit = 0
        val n = lock.withLock {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idleMs)
            while (true) {
                if (aborted || closed) throw IOException("The body was given up")
                if (queued > 0) break
                if (ended) return -1
                // Nothing to read: whatever is owed goes first, so the room is never left without credit while this waits.
                if (owed > 0) {
                    credit = owed
                    owed = 0
                    lock.unlock()
                    try {
                        onCredit(credit)
                    } finally {
                        lock.lock()
                    }
                    credit = 0
                    continue
                }
                val left = deadline - System.nanoTime()
                if (left <= 0) throw SocketTimeoutException("The body stalled")
                arrived.awaitNanos(left)
            }
            var copied = 0
            while (copied < len && chunks.isNotEmpty()) {
                val chunk = chunks.first()
                val n = minOf(len - copied, chunk.size - at)
                chunk.copyInto(b, off + copied, at, at + n)
                copied += n
                at += n
                if (at == chunk.size) {
                    chunks.removeFirst()
                    at = 0
                }
            }
            queued -= copied
            owed += copied
            if (owed >= creditStep) {
                credit = owed
                owed = 0
            }
            copied
        }
        if (credit > 0) onCredit(credit)
        return n
    }

    override fun available(): Int = lock.withLock { if (aborted || closed) 0 else queued }

    override fun close() = lock.withLock {
        closed = true
        chunks.clear()
        queued = 0
        arrived.signalAll()
    }

    companion object {
        /** How long a read waits for the next chunk: the room's own idle limit for a body. */
        const val IDLE_MS = 30_000L

        /** Credit goes back in steps of a chunk at least, not after every small read. */
        const val CREDIT_STEP = 64 * 1024
    }
}
