// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.channels

import dev.stevenjin.stevenpiano.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** What a channel needs from the player (`Player`; a fake in tests). */
interface ChannelDeck {
    val state: StateFlow<PlayerState>

    /** Plays [pieceIds] from the top ([shuffle]: in random order); with [channel], as that channel's. */
    fun playAll(pieceIds: List<Long>, shuffle: Boolean, channel: String? = null)

    /** Queues [pieceIds] at the end; [channel] marks them as its top-up. True when they started playing (nothing was queued). */
    fun addToQueue(pieceIds: List<Long>, channel: String? = null): Boolean

    fun stop()

    /** The app's velocity percentage, 50-150 %: how hard every note is struck. */
    fun setVelocity(pct: Int)
}

/**
 * How loud the piano plays: its [volume] (0-100 %) and whether [fullPower] is on (every note at
 * full force). The firmware turns Full power off whenever the volume goes below 100, so the two
 * are put back together.
 */
data class PianoLoudness(val volume: Int, val fullPower: Boolean)

/** The piano's own volume, where its firmware offers it (`PianoSettingsRepository`; a fake in tests). */
interface PianoVolume {
    /** How loud the piano plays now, or null when it has no volume to offer (not connected, older firmware). */
    fun current(): PianoLoudness?

    /** Sets the piano's volume for now: never saved on the piano. */
    fun hold(pct: Int)

    /** Puts [previous] back after [hold], unless the person has set it since; never saved either. */
    fun release(previous: PianoLoudness)
}

/**
 * Endless play from a channel's pool (DESIGN.md › v1.5 — M17). [play] shuffles the pool into a
 * deck and starts the first [FIRST] pieces as the channel's queue; while it plays, whenever fewer
 * than [TOP_UP_BELOW] pieces are up next, the next [TOP_UP] are added. When the deck runs out the
 * pool is shuffled again without the last [RECENT] pieces dealt (at most half the pool, so a small
 * pool still shuffles), so nothing repeats until the pool is exhausted and nothing just heard comes
 * straight back. [stop] is the player's stop; the channel ends whenever [PlayerState.channel] stops
 * being its key.
 *
 * The channel's volume ([volumeOf], 70 % unless the person set it; a schedule may give its own)
 * goes to the piano's own volume when the piano offers one ([piano]), else to the app's velocity
 * (50 + volume / 2 %); when the channel ends, what it replaced comes back (the piano's volume and
 * Full power together; the velocity only if the person has not changed it meanwhile), and the
 * piano is never asked to save either. That is [loudness], which a schedule's playlist or piece
 * shares (DESIGN.md › v1.5.2 — M19): a channel that follows another, or follows a schedule's
 * volume, keeps the first one's "what to put back". A pool of fewer than
 * [ChannelSummary.MIN_POOL] pieces does not play. Call on [scope]'s thread (the main thread),
 * after [start].
 */
class ChannelPlayer(
    private val deck: ChannelDeck,
    private val pools: (String) -> List<Long>?,
    private val volumeOf: (String) -> Int,
    piano: PianoVolume,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    /** The loudness a channel holds while it plays, shared with the schedules. */
    val loudness: LoudnessHold = LoudnessHold(piano, deck),
) {
    private var session: Session? = null
    private var started = false

    /** A channel is being handed to the player: what the player says meanwhile is not about it yet. */
    private var starting = false

    /** Follows the player from now on: tops the queue up, and notices the channel ending. */
    fun start() {
        if (started) return
        started = true
        scope.launch { deck.state.collect(::onState) }
    }

    /**
     * Plays channel [key] from a fresh shuffle of its pool, at [volumePct] (a schedule's) or the
     * channel's own volume. False (and nothing changes) when its pool is unknown or too small.
     */
    fun play(key: String, volumePct: Int? = null): Boolean {
        val pool = pools(key)?.distinct() ?: return false
        if (pool.size < ChannelSummary.MIN_POOL) return false
        val next = Session(key, pool)
        starting = true
        try {
            loudness.hold(next, volumePct ?: volumeOf(key))
            session = next
            deck.playAll(next.deal(FIRST), shuffle = false, channel = key)
        } finally {
            starting = false
        }
        onState(deck.state.value)
        return true
    }

    /** The player's stop: the channel ends with it. */
    fun stop() = deck.stop()

    /** The channel playing, if any. */
    val playing: String? get() = session?.key

    /**
     * The person set channel [key]'s volume to [pct] (the Set volume sheet): heard at once when it
     * is the one playing. The setting itself is saved by the caller.
     */
    fun volumeChanged(key: String, pct: Int) {
        val current = session ?: return
        if (current.key == key) loudness.hold(current, pct)
    }

    private fun onState(state: PlayerState) {
        if (starting) return   // the player may speak before it has taken the channel (a watcher on the main thread runs at once)
        val current = session ?: return
        if (state.channel != current.key) {
            session = null
            if (state.channel == null) loudness.release(current)   // another channel took over: its session keeps the first "put back"
            return
        }
        if (state.queue.upNextIds.size < TOP_UP_BELOW) deck.addToQueue(current.deal(TOP_UP), channel = current.key)
    }

    /** One channel's run: its pool, the shuffled deck still to deal, and the pieces dealt last. */
    private inner class Session(val key: String, private val pool: List<Long>) {
        private val remaining = ArrayDeque(pool.shuffled(random))
        private val recent = ArrayDeque<Long>()

        /** The next [count] pieces; the deck is shuffled again as it runs out. */
        fun deal(count: Int): List<Long> = List(count) {
            if (remaining.isEmpty()) reshuffle()
            remaining.removeFirst().also { dealt ->
                recent.addLast(dealt)
                if (recent.size > RECENT) recent.removeFirst()
            }
        }

        private fun reshuffle() {
            val keepOut = recent.toList().takeLast(minOf(RECENT, pool.size / 2)).toHashSet()
            remaining.addAll(pool.filter { it !in keepOut }.shuffled(random))
        }
    }

    companion object {
        /** Pieces a channel starts with. */
        const val FIRST = 25

        /** Up next shorter than this tops the queue up… */
        const val TOP_UP_BELOW = 5

        /** …by this many. */
        const val TOP_UP = 10

        /** A reshuffled deck leaves out this many pieces dealt last. */
        const val RECENT = 20

        /** A channel's volume unless the person sets it. */
        const val DEFAULT_VOLUME = 70

        const val MAX_VOLUME = LoudnessHold.MAX_VOLUME

        /** The app's velocity for a channel volume, where the piano has no volume of its own: 50 % to 100 %. */
        fun velocityFor(volume: Int): Int = LoudnessHold.velocityFor(volume)
    }
}
