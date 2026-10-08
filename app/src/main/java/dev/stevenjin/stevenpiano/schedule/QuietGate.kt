// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The quiet at a moment, as the tablet, the state and the panel read it: [now] a block is on, [until] when the quiet
 * ends (epoch ms, while [now]), [overridden] a person chose Play anyway (it holds until the block ends), [next] the next
 * block's start (epoch ms), or null with none.
 */
data class QuietNow(val now: Boolean = false, val until: Long? = null, val overridden: Boolean = false, val next: Long? = null) {
    /** Nothing may start: a block is on and nobody lifted it. */
    val holds: Boolean get() = now && !overridden
}

/**
 * The gate of quiet times (DESIGN.md › v1.20 — M54), one in the app's graph: [now] works the quiet out from the quiet
 * rows ([rows], as last read; none until then) and the clock, every time it is asked, from any thread, so a play is
 * weighed against the moment it is asked at. [override] (Play anyway) lifts the quiet until its block ends, in memory
 * only: a block that begins after it is quiet again, and the lift goes with the quiet.
 *
 * From [start] it follows the rows and the clock on the main thread: [state] is worked out again whenever the rows
 * change, at every block's start and end (and at least once a minute, for a clock changed by hand), and as a block
 * begins without a lift [hush] runs, once a block (the player's stop sequence: at a block's start anything playing
 * stops). The alarm planner's alarm at a block's start calls [check] too, so a sleeping tablet stops on the minute; and
 * a block on as the app starts stops it at once.
 */
class QuietGate(
    private val rows: StateFlow<List<ScheduleEntity>?>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** Play anyway: made at [madeAt], the quiet then ending at [until]; it holds while no block has begun since. */
    private class Lift(val madeAt: Long, val until: Long)

    private val lock = Any()
    private var lift: Lift? = null

    /** The block a [hush] last ran for (its start, epoch ms); main thread. */
    private var hushedFor: Long? = null
    private var hush: () -> Unit = {}
    private var started = false

    private val _state = MutableStateFlow(QuietNow())

    /** The quiet as last worked out, for the screens and the web panel's state. */
    val state: StateFlow<QuietNow> = _state.asStateFlow()

    /** The quiet at [at] (now by default). */
    fun now(at: Long = clock()): QuietNow = weigh(at).quiet

    /** Whether a quiet time holds now: the player's start paths start nothing while it does. */
    fun holds(): Boolean = now().holds

    /**
     * Play anyway: while a block is on, the quiet lifts until it ends, or until another block begins, whichever comes
     * first. False (nothing changes) when no block is on. Any thread.
     */
    fun override(at: Long = clock()): Boolean {
        val spell = QuietTimes.at(rows.value.orEmpty(), QuietCopy.at(at, zone())) ?: return false
        synchronized(lock) { lift = Lift(at, spell.until.toInstant().toEpochMilli()) }
        _state.value = now(at)
        return true
    }

    /**
     * From the app's start, on [scope]'s thread (the main thread): [onHush] (the player's stop) runs as each block begins
     * without a lift, and [state] follows the rows and the clock.
     */
    fun start(scope: CoroutineScope, onHush: () -> Unit) {
        if (started) return
        started = true
        hush = onHush
        scope.launch {
            rows.filterNotNull().collectLatest {
                while (true) {
                    check()
                    delay(untilChange())
                }
            }
        }
    }

    /**
     * Works the quiet out at [at] (now; an alarm passes its own minute, never earlier than now) and keeps it in [state];
     * a block on without a lift whose start it has not stopped for yet stops the player ([start]'s hush). Main thread.
     */
    fun check(at: Long = clock()) {
        val weighed = weigh(maxOf(at, clock()))
        _state.value = weighed.quiet
        val since = weighed.since ?: return
        if (weighed.quiet.holds && since != hushedFor) {
            hushedFor = since
            hush()
        }
    }

    /** What [check] works out: the quiet, and the start of the block that began last (null with none on). */
    private class Weighed(val quiet: QuietNow, val since: Long?)

    private fun weigh(at: Long): Weighed {
        val list = rows.value.orEmpty()
        val time = QuietCopy.at(at, zone())
        val next = QuietTimes.next(list, time)?.toInstant()?.toEpochMilli()
        val spell = QuietTimes.at(list, time)
        if (spell == null) {
            synchronized(lock) { lift = null }
            return Weighed(QuietNow(next = next), since = null)
        }
        val until = spell.until.toInstant().toEpochMilli()
        val held = synchronized(lock) { lift }
        // A lift holds until the quiet it was made in ends, and until a block begins after it was made.
        val lifted = held != null && at < held.until &&
            QuietTimes.next(list, QuietCopy.at(held.madeAt, time.zone))?.let { it.toInstant().toEpochMilli() > at } != false
        if (held != null && !lifted) synchronized(lock) { if (lift === held) lift = null }
        return Weighed(QuietNow(now = true, until = until, overridden = lifted, next = next), since = spell.since.toInstant().toEpochMilli())
    }

    /** How long until the quiet may change: the quiet's end, the next block's start or a lift's end; a minute at most. */
    private fun untilChange(): Long {
        val now = clock()
        val quiet = _state.value
        val lifted = synchronized(lock) { lift?.until }
        val soonest = listOfNotNull(quiet.until, quiet.next, lifted).filter { it > now }.minOrNull() ?: return MINUTE_MS
        return (soonest - now + SLACK_MS).coerceIn(MIN_WAIT_MS, MINUTE_MS)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val MIN_WAIT_MS = 250L

        /** Past an edge by this much, so the edge has been reached when the quiet is worked out again. */
        const val SLACK_MS = 20L
    }
}
