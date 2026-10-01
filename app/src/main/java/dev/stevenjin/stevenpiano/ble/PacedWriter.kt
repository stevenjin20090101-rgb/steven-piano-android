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
 *
 * Keys played live (v1.11 — M29: the Keys screen, a keyboard) go through [enqueueLive], a lane in
 * front of the backlog that shares its bucket: a live key never waits behind a dense piece's two
 * seconds. The lane has one guard. A live message whose key (or controller) still has a message
 * waiting in the backlog goes right behind the last of those instead, and one that concerns every
 * key (All Notes Off and the like, a program change) goes behind the whole backlog; so each key's
 * messages, and each controller's, reach the piano in the order they were queued, and a live Note
 * Off can never overtake a piece's Note On for the same key (which would leave it down). The
 * backlog's drop never touches the lane; the stop sequence ([enqueue] with `dropPending`) clears
 * both.
 */
class PacedWriter(
    private val burst: Int = BleMidiFramer.MAX_MESSAGES,
    private val nanosPerMessage: Long = 1_000_000L,
    private val maxBacklog: Int = MAX_BACKLOG,
) {
    /** The backlog: what [enqueue] queued, in order (and live messages the guard placed behind it). */
    private val backlog = Lane()

    /** Live messages ahead of the backlog ([enqueueLive]). */
    private val lane = Lane()

    /** Messages waiting in [backlog] per slot ([slotOf]): a key, a controller, or every key. */
    private val waiting = IntArray(SLOTS)
    private var budgetNanos = burst * nanosPerMessage
    private var lastRefillNanos = 0L
    private var started = false
    private val scratch = IntArray(BleMidiFramer.MAX_MESSAGES)

    val pending: Int
        @Synchronized get() = backlog.count + lane.count

    /** Messages waiting in the live lane (tests, and the link's flood check). */
    val pendingLive: Int
        @Synchronized get() = lane.count

    /**
     * Queues [batch] after what is pending, or instead of it when [dropPending] (the stop sequence).
     * Returns how many waiting Note Ons were dropped to keep the backlog under [maxBacklog].
     */
    @Synchronized
    fun enqueue(batch: MidiBatch, dropPending: Boolean = false): Int {
        if (dropPending) clearQueue()
        for (i in 0 until batch.size) pushBacklog(batch.packedAt(i))
        return if (backlog.count > maxBacklog) dropNoteOns() else 0
    }

    /**
     * Queues [batch], played live, in the lane ahead of the backlog; a message whose key or
     * controller still waits in the backlog goes right behind the last such message instead, and one
     * for every key behind the whole backlog (see the class). Returns how many waiting Note Ons were
     * dropped from the backlog to keep it under [maxBacklog].
     */
    @Synchronized
    fun enqueueLive(batch: MidiBatch): Int {
        for (i in 0 until batch.size) {
            val message = batch.packedAt(i)
            val slot = slotOf(message)
            when {
                backlog.count == 0 -> lane.push(message)
                slot == EVERY_KEY || waiting[EVERY_KEY] > 0 -> pushBacklog(message)   // never ahead of anything for every key
                waiting[slot] == 0 -> lane.push(message)
                else -> insertBehindLast(slot, message)
            }
        }
        return if (backlog.count > maxBacklog) dropNoteOns() else 0
    }

    @Synchronized
    fun clear() = clearQueue()

    /** The next packet, when a message may go at [nowNanos]: the live lane first, then the backlog; null when idle or out of tokens. */
    @Synchronized
    fun nextPacket(nowNanos: Long, mtu: Int, timestampMs: Long): ByteArray? {
        val count = lane.count + backlog.count
        if (count == 0) return null
        refill(nowNanos)
        val n = minOf(count, (budgetNanos / nanosPerMessage).toInt(), BleMidiFramer.capacity(mtu))
        if (n == 0) return null
        for (i in 0 until n) scratch[i] = take()
        budgetNanos -= n * nanosPerMessage
        return BleMidiFramer.frame(scratch, 0, n, timestampMs)
    }

    /** Nanoseconds until [nextPacket] can return a packet: 0 now, [Long.MAX_VALUE] when idle. */
    @Synchronized
    fun nanosUntilReady(nowNanos: Long): Long {
        if (lane.count + backlog.count == 0) return Long.MAX_VALUE
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

    /** The next message to go: the live lane's first, else the backlog's. */
    private fun take(): Int {
        if (lane.count > 0) return lane.removeFirst()
        val message = backlog.removeFirst()
        waiting[slotOf(message)]--
        return message
    }

    private fun pushBacklog(message: Int) {
        backlog.push(message)
        waiting[slotOf(message)]++
    }

    /** [message] right behind the backlog's last message for [slot] (which waits there). */
    private fun insertBehindLast(slot: Int, message: Int) {
        var at = backlog.count - 1
        while (at >= 0 && slotOf(backlog[at]) != slot) at--
        backlog.insert(at + 1, message)
        waiting[slot]++
    }

    private fun clearQueue() {
        backlog.clear()
        lane.clear()
        waiting.fill(0)
    }

    /** Removes every Note On waiting in the backlog, keeping the rest in order (the live lane untouched); returns how many went. */
    private fun dropNoteOns(): Int {
        val dropped = backlog.removeIf { isNoteOn(it) }
        if (dropped > 0) {
            waiting.fill(0)
            for (i in 0 until backlog.count) waiting[slotOf(backlog[i])]++
        }
        return dropped
    }

    private fun isNoteOn(message: Int): Boolean = ((message ushr 16) and 0xF0) == 0x90 && (message and 0x7F) != 0

    /** A growable ring of packed messages. */
    private class Lane {
        private var items = IntArray(256)
        private var head = 0
        var count = 0
            private set

        operator fun get(i: Int): Int = items[(head + i) and (items.size - 1)]

        fun push(message: Int) {
            if (count == items.size) grow()
            items[(head + count) and (items.size - 1)] = message
            count++
        }

        /** [message] at position [at] (0..[count]), the ones from there on moving back by one. */
        fun insert(at: Int, message: Int) {
            if (count == items.size) grow()
            val mask = items.size - 1
            for (i in count downTo at + 1) items[(head + i) and mask] = items[(head + i - 1) and mask]
            items[(head + at) and mask] = message
            count++
        }

        fun removeFirst(): Int {
            val message = items[head]
            head = (head + 1) and (items.size - 1)
            count--
            return message
        }

        /** Removes the messages [drop] picks, keeping the rest in order; returns how many went. */
        fun removeIf(drop: (Int) -> Boolean): Int {
            val mask = items.size - 1
            var kept = 0
            for (i in 0 until count) {
                val message = items[(head + i) and mask]
                if (drop(message)) continue
                items[(head + kept) and mask] = message   // kept <= i: never ahead of what is still to read
                kept++
            }
            val dropped = count - kept
            count = kept
            return dropped
        }

        fun clear() {
            head = 0
            count = 0
        }

        private fun grow() {
            val grown = IntArray(items.size * 2)
            for (i in 0 until count) grown[i] = items[(head + i) and (items.size - 1)]
            items = grown
            head = 0
        }
    }

    companion object {
        /** Two seconds of the piano's pace. */
        const val MAX_BACKLOG = 2_000

        /** Slots: 128 keys, then 120 controllers (0-119), then one for every key at once. */
        private const val CONTROLLERS = 128
        private const val EVERY_KEY = CONTROLLERS + 120
        private const val SLOTS = EVERY_KEY + 1

        /**
         * What a message concerns, for the lane's guard: its key (Note On, Note Off, a key's pressure;
         * any channel, as the piano listens in omni), its controller (0-119), or every key at once
         * (the channel-mode messages 120-127, a program change, pitch bend, channel pressure, anything else).
         */
        internal fun slotOf(message: Int): Int {
            val data1 = (message ushr 8) and 0x7F
            return when ((message ushr 16) and 0xF0) {
                0x80, 0x90, 0xA0 -> data1
                0xB0 -> if (data1 < 120) CONTROLLERS + data1 else EVERY_KEY
                else -> EVERY_KEY
            }
        }
    }
}
