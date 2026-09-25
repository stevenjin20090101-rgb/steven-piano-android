// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.midi.MidiBatch

/**
 * The piano plays about 1000 messages a second and its Bluetooth task blocks when its
 * 64-byte queue fills, so sends go through a token bucket: bursts of up to 20 messages,
 * then one message per millisecond. Messages keep their order. Thread-safe: the scheduler
 * enqueues while the Bluetooth thread drains.
 *
 * A file denser than the piano can play would grow the queue without end, and the piano would
 * play on for minutes after the player stopped. So past [maxBacklog] messages (two seconds'
 * worth) the Note Ons still waiting are dropped: late notes are no music anyway. Note Offs and
 * controllers are always kept, in order, so nothing stays down and the pedal ends where the file
 * left it.
 */
class PacedWriter(
    private val burst: Int = BleMidiFramer.MAX_MESSAGES,
    private val nanosPerMessage: Long = 1_000_000L,
    private val maxBacklog: Int = MAX_BACKLOG,
) {
    private var queue = IntArray(256)
    private var head = 0
    private var count = 0
    private var budgetNanos = burst * nanosPerMessage
    private var lastRefillNanos = 0L
    private var started = false
    private val scratch = IntArray(BleMidiFramer.MAX_MESSAGES)

    val pending: Int
        @Synchronized get() = count

    /**
     * Queues [batch] after what is pending, or instead of it when [dropPending] (the stop sequence).
     * Returns how many waiting Note Ons were dropped to keep the backlog under [maxBacklog].
     */
    @Synchronized
    fun enqueue(batch: MidiBatch, dropPending: Boolean = false): Int {
        if (dropPending) clearQueue()
        for (i in 0 until batch.size) push(batch.packedAt(i))
        return if (count > maxBacklog) dropNoteOns() else 0
    }

    @Synchronized
    fun clear() = clearQueue()

    /** The next packet, when a message may go at [nowNanos]; null when idle or out of tokens. */
    @Synchronized
    fun nextPacket(nowNanos: Long, mtu: Int, timestampMs: Long): ByteArray? {
        if (count == 0) return null
        refill(nowNanos)
        val n = minOf(count, (budgetNanos / nanosPerMessage).toInt(), BleMidiFramer.capacity(mtu))
        if (n == 0) return null
        for (i in 0 until n) {
            scratch[i] = queue[head]
            head = (head + 1) and (queue.size - 1)
        }
        count -= n
        budgetNanos -= n * nanosPerMessage
        return BleMidiFramer.frame(scratch, 0, n, timestampMs)
    }

    /** Nanoseconds until [nextPacket] can return a packet: 0 now, [Long.MAX_VALUE] when idle. */
    @Synchronized
    fun nanosUntilReady(nowNanos: Long): Long {
        if (count == 0) return Long.MAX_VALUE
        refill(nowNanos)
        return (nanosPerMessage - budgetNanos).coerceAtLeast(0L)
    }

    private fun refill(nowNanos: Long) {
        if (started) {
            budgetNanos = minOf(burst * nanosPerMessage, budgetNanos + (nowNanos - lastRefillNanos))
        }
        started = true
        lastRefillNanos = nowNanos
    }

    private fun push(message: Int) {
        if (count == queue.size) {
            val grown = IntArray(queue.size * 2)
            for (i in 0 until count) grown[i] = queue[(head + i) and (queue.size - 1)]
            queue = grown
            head = 0
        }
        queue[(head + count) and (queue.size - 1)] = message
        count++
    }

    private fun clearQueue() {
        head = 0
        count = 0
    }

    /** Removes every waiting Note On, keeping the rest in order; returns how many went. */
    private fun dropNoteOns(): Int {
        val mask = queue.size - 1
        var kept = 0
        for (i in 0 until count) {
            val message = queue[(head + i) and mask]
            if (isNoteOn(message)) continue
            queue[(head + kept) and mask] = message   // kept <= i: never ahead of what is still to read
            kept++
        }
        val dropped = count - kept
        count = kept
        return dropped
    }

    private fun isNoteOn(message: Int): Boolean = ((message ushr 16) and 0xF0) == 0x90 && (message and 0x7F) != 0

    companion object {
        /** Two seconds of the piano's pace. */
        const val MAX_BACKLOG = 2_000
    }
}
