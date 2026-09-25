// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import kotlin.random.Random

/** What follows the end of a piece. The transport's Repeat button cycles off, all, one. */
enum class RepeatMode {
    /** The queue plays once and stops. */
    OFF,

    /** After the last piece, the queue starts again from its top. */
    ALL,

    /** The same piece plays again. */
    ONE,
    ;

    /** The Repeat button's next state: off, all, one, then off again. */
    fun cycled(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

/** One place in the queue. [uid] is its own, so the same piece queued twice is still two entries. */
data class QueueEntry(val uid: Long, val pieceId: Long)

/**
 * The play queue, immutable. [entries] are in playing order and [index] is the entry playing
 * (-1 before anything has). While shuffled, [original] keeps the order the entries were shuffled
 * from, so turning shuffle off restores it: [playNext] puts pieces right after the current one in
 * both orders; pieces added with [addToQueue] while shuffled go right after the current piece when
 * the order is restored; [move] rearranges only the order that plays. [repeat] decides what
 * follows the end of a piece ([afterEnd]). [nextUid] numbers the entries still to be made; it
 * carries over from one queue to the next so a uid never names two entries.
 */
data class Queue(
    val entries: List<QueueEntry> = emptyList(),
    val index: Int = -1,
    val original: List<QueueEntry>? = null,
    val repeat: RepeatMode = RepeatMode.OFF,
    val nextUid: Long = 1L,
) {
    val shuffled: Boolean get() = original != null

    val current: QueueEntry? get() = entries.getOrNull(index)

    /** The entries after the current one, in playing order. */
    val upNext: List<QueueEntry> get() = entries.subList((index + 1).coerceIn(0, entries.size), entries.size)

    /** Next has somewhere to go: a piece follows, or Repeat all wraps to the top. */
    val hasNext: Boolean get() = index + 1 < entries.size || (repeat == RepeatMode.ALL && entries.isNotEmpty())

    /** Previous has somewhere to go: a piece came before, or Repeat all wraps to the end. */
    val hasPrevious: Boolean get() = index > 0 || (repeat == RepeatMode.ALL && entries.size > 1)

    fun next(): Queue = when {
        index + 1 < entries.size -> copy(index = index + 1)
        repeat == RepeatMode.ALL && entries.isNotEmpty() -> copy(index = 0)
        else -> this
    }

    fun previous(): Queue = when {
        index > 0 -> copy(index = index - 1)
        repeat == RepeatMode.ALL && entries.size > 1 -> copy(index = entries.lastIndex)
        else -> this
    }

    /** Previous restarts the piece when more than 3 s in, or when there is nothing before it. */
    fun previousRestarts(positionMicros: Long): Boolean = positionMicros > RESTART_AFTER_MICROS || !hasPrevious

    /**
     * What plays once the current piece has ended: the same entry again (Repeat one), the next
     * one (wrapping to the top with Repeat all), or nothing (null) at the end of the queue.
     */
    fun afterEnd(): Queue? = when {
        current == null -> null
        repeat == RepeatMode.ONE -> this
        index + 1 < entries.size -> copy(index = index + 1)
        repeat == RepeatMode.ALL -> copy(index = 0)
        else -> null
    }

    /** [pieceIds] right after the current piece, in that order; while shuffled, after it in the restored order too. */
    fun playNext(pieceIds: List<Long>): Queue {
        if (pieceIds.isEmpty()) return this
        val added = entriesFor(pieceIds)
        val shown = entries.toMutableList().apply { addAll(index + 1, added) }
        val restored = original?.let { base ->
            val at = current?.let { cur -> base.indexOfFirst { it.uid == cur.uid } } ?: -1
            // With the current piece outside the original order, the new entries count as added
            // while shuffled, which restoring places after the current piece anyway.
            if (at < 0) base else base.toMutableList().apply { addAll(at + 1, added) }
        }
        return copy(entries = shown, original = restored, nextUid = nextUid + added.size)
    }

    /** [pieceIds] at the end of the queue. While shuffled they stay out of [original], so restoring puts them after the current piece. */
    fun addToQueue(pieceIds: List<Long>): Queue {
        if (pieceIds.isEmpty()) return this
        val added = entriesFor(pieceIds)
        return copy(entries = entries + added, nextUid = nextUid + added.size)
    }

    /** Takes entry [uid] out of both orders. The current piece stays: it is playing. */
    fun remove(uid: Long): Queue {
        val at = entries.indexOfFirst { it.uid == uid }
        if (at < 0 || at == index) return this
        return copy(
            entries = entries.filterNot { it.uid == uid },
            index = if (at < index) index - 1 else index,
            original = original?.filterNot { it.uid == uid },
        )
    }

    /** Moves up-next entry [uid] to place [toUpNextIndex] among the pieces up next. The order it was shuffled from is not touched. */
    fun move(uid: Long, toUpNextIndex: Int): Queue {
        val from = entries.indexOfFirst { it.uid == uid }
        if (from <= index) return this
        val to = (index + 1 + toUpNextIndex).coerceIn(index + 1, entries.lastIndex)
        if (to == from) return this
        return copy(entries = entries.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Everything after the current piece goes, from both orders. */
    fun clearUpNext(): Queue {
        val gone = upNext.mapTo(HashSet()) { it.uid }
        if (gone.isEmpty()) return this
        return copy(entries = entries.filterNot { it.uid in gone }, original = original?.filterNot { it.uid in gone })
    }

    /** Entry [uid] becomes the current one; the ones skipped stay behind it. */
    fun skipTo(uid: Long): Queue {
        val at = entries.indexOfFirst { it.uid == uid }
        return if (at < 0 || at == index) this else copy(index = at)
    }

    /**
     * Shuffle on: the current piece stays first and plays on, every other entry follows in random
     * order, and the order they had is kept in [original]. Off: that order comes back, with the
     * entries added meanwhile right after the current piece, and the current piece plays on.
     */
    fun withShuffle(on: Boolean, random: Random): Queue {
        if (on == shuffled) return this
        val cur = current
        if (on) {
            val others = entries.filter { it.uid != cur?.uid }.shuffled(random)
            return copy(entries = listOfNotNull(cur) + others, index = if (cur == null) -1 else 0, original = entries)
        }
        val base = original ?: return this
        val present = entries.mapTo(HashSet()) { it.uid }
        val kept = base.filter { it.uid in present }
        val known = kept.mapTo(HashSet()) { it.uid }
        val added = entries.filter { it.uid !in known }
        val at = cur?.let { c -> kept.indexOfFirst { it.uid == c.uid } } ?: -1
        val restored = if (at < 0) kept + added else kept.subList(0, at + 1) + added + kept.subList(at + 1, kept.size)
        return copy(entries = restored, index = if (cur == null) -1 else restored.indexOfFirst { it.uid == cur.uid }, original = null)
    }

    fun withRepeat(mode: RepeatMode): Queue = if (mode == repeat) this else copy(repeat = mode)

    /** What the UI, the playback service and the media session read. */
    fun snapshot(): QueueSnapshot = QueueSnapshot(entries.map { it.pieceId }, entries.map { it.uid }, index, shuffled, repeat)

    private fun entriesFor(pieceIds: List<Long>): List<QueueEntry> = pieceIds.mapIndexed { i, id -> QueueEntry(nextUid + i, id) }

    companion object {
        const val RESTART_AFTER_MICROS = 3_000_000L

        val Empty = Queue()

        /**
         * [pieceIds] with [pieceId] playing (just that piece when the list does not hold it), in
         * [previous]'s modes: shuffled, the piece chosen plays first and the rest follow in random
         * order.
         */
        fun startingAt(pieceId: Long, pieceIds: List<Long>, previous: Queue = Empty, random: Random = Random.Default): Queue {
            val ids = if (pieceId in pieceIds) pieceIds else listOf(pieceId)
            val entries = ids.mapIndexed { i, id -> QueueEntry(previous.nextUid + i, id) }
            val plain = Queue(entries, ids.indexOf(pieceId), null, previous.repeat, previous.nextUid + entries.size)
            return if (previous.shuffled) plain.withShuffle(true, random) else plain
        }

        /** Every one of [pieceIds] from the top, or all in random order when [shuffle] (which then is the mode), keeping [previous]'s repeat. */
        fun all(pieceIds: List<Long>, shuffle: Boolean, previous: Queue = Empty, random: Random = Random.Default): Queue {
            val entries = pieceIds.mapIndexed { i, id -> QueueEntry(previous.nextUid + i, id) }
            val start = if (entries.isEmpty()) -1 else 0
            val next = previous.nextUid + entries.size
            return if (shuffle) {
                Queue(entries.shuffled(random), start, entries, previous.repeat, next)
            } else {
                Queue(entries, start, null, previous.repeat, next)
            }
        }
    }
}
