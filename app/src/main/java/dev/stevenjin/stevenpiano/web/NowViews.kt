// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.KeySignature
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature
import dev.stevenjin.stevenpiano.score.ChordTrack
import dev.stevenjin.stevenpiano.score.ScoreDisplayList
import dev.stevenjin.stevenpiano.score.ScoreLayout
import dev.stevenjin.stevenpiano.score.ScoreLayoutEngine
import dev.stevenjin.stevenpiano.score.ScoreMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * What the panel's views are drawn from (v1.13 — M32): the piece playing as the tablet shows it now, its notes at
 * the [transpose] and [fold] the piano plays, the [hands], the [fingers] only while Fingering is on (and worked out
 * for these keys), the [chords] only while Chord names is on.
 */
class NowSource(
    val notes: NoteList,
    val tempo: TempoMap,
    val bars: LongArray,
    val keySignatures: List<KeySignature>,
    val timeSignatures: List<TimeSignature>,
    val durationMicros: Long,
    val transpose: Int,
    val fold: Boolean,
    val hands: ByteArray?,
    val fingers: ByteArray?,
    val chords: ChordTrack?,
)

/** How a request for the views went: the bytes, a layout still running, a stale request, nothing playing, too large, too many layouts. */
sealed interface NowAnswer {
    class Ready(val bytes: ByteArray) : NowAnswer

    data class Working(val retryAfterMs: Long) : NowAnswer

    data object Stale : NowAnswer

    data object NoPiece : NowAnswer

    data object TooLarge : NowAnswer

    data class Busy(val retryAfterMs: Long) : NowAnswer
}

/**
 * The panel's notes and score for the piece playing (BUILD_SPEC.md › v1.13 — M32), bounded so that a signed-in
 * browser can never make the tablet work without end:
 *
 * - **A revision** ([revision]) names what the views show: it moves on whenever the notes, the hands, the fingering
 *   or the chords (their references), the transpose or folding change, and every cached thing goes with it.
 * - **Notes** ([notes]) are encoded once per revision, up to [MAX_NOTES] notes (else too large).
 * - **The score** ([score]) is laid out for a browser panel's size, floored to a [GRID] px grid and held to
 *   280–2000 × 160–2000, on one low-priority thread, one layout at a time; requests for the same size share it.
 *   A request waits at most [waitMs] (then [NowAnswer.Working]: the layout runs on for the retry); a layout past
 *   [deadlineMs] stops and that size is remembered as too large; at most [MAX_FRESH] fresh layouts every
 *   [RATE_WINDOW_MS] (then [NowAnswer.Busy]); at most [MAX_SCORE_NOTES] notes and [MAX_SCORE_BARS] bars. Two
 *   sizes stay laid out (the last used), and their pages, encoded as asked, up to [PAGE_CACHE_BYTES].
 * - **A page** ([page]) comes only from a layout already made; it never starts one.
 *
 * A change of piece cancels the layout running (its checkpoint asks whether the piece is still the one playing)
 * and clears everything; so does [clear] (the panel turned off).
 */
class NowViews(
    private val source: () -> NowSource?,
    private val dispatcher: CoroutineDispatcher = defaultDispatcher(),
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val waitMs: Long = SCORE_WAIT_MS,
    private val deadlineMs: Long = LAYOUT_DEADLINE_MS,
    seed: Int = Random.nextInt(1, Int.MAX_VALUE / 2),
    private val layOut: (NowSource, ScoreMetrics, () -> Unit) -> ScoreLayout = ::layoutFor,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var rev = seed
    private var made: Inputs? = null
    private var notesBytes: ByteArray? = null
    private var notesRev = 0
    private val layouts = ArrayList<Laid>()
    private val running = HashMap<Key, Deferred<Laid?>>()
    private val tooLarge = HashSet<Key>()
    private val fresh = ArrayDeque<Long>()
    private val ids = AtomicInteger(Random.nextInt(1, 1 shl 20))

    /** One layout made: for its [key], with its [id], the [index] encoded, and its pages as asked. */
    private class Laid(val key: Key, val id: Int, val source: NowSource, val layout: ScoreLayout, val index: ByteArray) {
        val pages = HashMap<Int, ByteArray>()
        var pageBytes = 0
    }

    private data class Key(val rev: Int, val width: Int, val height: Int)

    /** What a revision is made of, compared by reference (the arrays) and by value. */
    private class Inputs(val source: NowSource) {
        fun same(other: NowSource): Boolean =
            source.notes === other.notes && source.hands === other.hands && source.fingers === other.fingers &&
                source.chords === other.chords && source.transpose == other.transpose && source.fold == other.fold
    }

    /** The revision of what the views show now; null with nothing playing. */
    fun revision(): Int? = source()?.let { revisionOf(it) }

    private fun revisionOf(now: NowSource): Int = synchronized(lock) {
        val last = made
        if (last == null || !last.same(now)) {
            if (last != null) rev = (rev + 1) and Int.MAX_VALUE
            made = Inputs(now)
            dropAll()
        }
        rev
    }

    /** The notes ([ScoreDisplayList.notes]) of revision [askedRev] (the one playing when null). */
    fun notes(askedRev: Int?): NowAnswer {
        val now = source() ?: return NowAnswer.NoPiece
        val current = revisionOf(now)
        if (askedRev != null && askedRev != current) return NowAnswer.Stale
        if (now.notes.size > MAX_NOTES) return NowAnswer.TooLarge
        synchronized(lock) { if (notesRev == current) notesBytes?.let { return NowAnswer.Ready(it) } }
        val bytes = ScoreDisplayList.notes(now.notes, now.durationMicros, current, now.transpose, now.fold, now.hands, now.fingers, now.chords)
        synchronized(lock) {
            if (rev == current) {
                notesBytes = bytes
                notesRev = current
            }
        }
        return NowAnswer.Ready(bytes)
    }

    /** The score's index ([ScoreDisplayList.index]) for a panel [width] × [height] CSS px, of revision [askedRev]. */
    suspend fun score(askedRev: Int?, width: Int, height: Int): NowAnswer {
        val now = source() ?: return NowAnswer.NoPiece
        val current = revisionOf(now)
        if (askedRev != null && askedRev != current) return NowAnswer.Stale
        if (now.notes.size > MAX_SCORE_NOTES || now.bars.size > MAX_SCORE_BARS) return NowAnswer.TooLarge
        val key = Key(current, grid(width, MIN_WIDTH, MAX_WIDTH), grid(height, MIN_HEIGHT, MAX_HEIGHT))
        val job: Deferred<Laid?> = synchronized(lock) {
            layouts.firstOrNull { it.key == key }?.let { laid ->
                layouts.remove(laid)
                layouts.add(laid)   // the last used stays
                return NowAnswer.Ready(laid.index)
            }
            if (key in tooLarge) return NowAnswer.TooLarge
            running[key] ?: run {
                val at = clock()
                while (fresh.isNotEmpty() && at - fresh.first() >= RATE_WINDOW_MS) fresh.removeFirst()
                if (fresh.size >= MAX_FRESH) return NowAnswer.Busy((RATE_WINDOW_MS - (at - fresh.first())).coerceAtLeast(1L))
                fresh.addLast(at)
                // One layout at a time: whatever else runs is for a size no one waits for any more.
                cancelRunning()
                val started = scope.async(start = CoroutineStart.LAZY) { compute(key, now) }
                running[key] = started
                started.invokeOnCompletion { synchronized(lock) { if (running[key] === started) running.remove(key) } }
                started.start()
                started
            }
        }
        val waited = try {
            withTimeoutOrNull(waitMs) { Waited(job.await()) }
        } catch (e: CancellationException) {
            coroutineContext.ensureActive()   // this request's own cancellation goes on up
            return NowAnswer.Stale             // the layout's: another piece, or another size took its place
        }
        val laid = (waited ?: return NowAnswer.Working(RETRY_MS)).laid ?: return NowAnswer.TooLarge
        return NowAnswer.Ready(laid.index)
    }

    private class Waited(val laid: Laid?)

    /** Page [page] ([ScoreDisplayList.page]) of layout [layoutId], made already; [NowAnswer.Stale] once it is gone. */
    fun page(layoutId: Int, page: Int): NowAnswer {
        val laid = synchronized(lock) { layouts.firstOrNull { it.id == layoutId } } ?: return NowAnswer.Stale
        synchronized(lock) { laid.pages[page]?.let { return NowAnswer.Ready(it) } }
        val names = laid.source.chords?.let { c -> Array(c.size) { c.name(it, laid.source.transpose) } }
        val bytes = ScoreDisplayList.page(laid.layout, laid.id, page, laid.source.chords, names) ?: return NowAnswer.Stale
        synchronized(lock) {
            if (laid.pageBytes + bytes.size <= PAGE_CACHE_BYTES) {
                laid.pages[page] = bytes
                laid.pageBytes += bytes.size
            }
        }
        return NowAnswer.Ready(bytes)
    }

    /** Forgets every layout and page, and stops the one running (the panel turned off). */
    fun clear() = synchronized(lock) {
        made = null
        dropAll()
    }

    /** Stops the layouts' thread for good (tests). */
    fun close() {
        clear()
        scope.cancel()
    }

    private fun cancelRunning() {
        val jobs = running.values.toList()
        running.clear()
        for (job in jobs) job.cancel()
    }

    private fun dropAll() {
        cancelRunning()
        layouts.clear()
        tooLarge.clear()
        notesBytes = null
    }

    /** Lays out [now] for [key]'s size on the layouts' thread: null when it is too large (past the deadline, or it failed). */
    private suspend fun compute(key: Key, now: NowSource): Laid? {
        val layout = try {
            withTimeoutOrNull(deadlineMs) {
                val context = coroutineContext
                val metrics = ScoreDisplayList.metrics(key.width, key.height, chords = now.chords != null && now.chords.size > 0)
                layOut(now, metrics) {
                    context.ensureActive()
                    if (source()?.notes !== now.notes) throw CancellationException("Another piece plays.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        synchronized(lock) {
            if (rev != key.rev) throw CancellationException("The piece changed.")
            if (layout == null) {
                tooLarge += key
                return null
            }
            val id = ids.incrementAndGet() and Int.MAX_VALUE
            val laid = Laid(key, id, now, layout, ScoreDisplayList.index(layout, key.rev, id))
            layouts.add(laid)
            while (layouts.size > MAX_LAYOUTS) layouts.removeAt(0)
            return laid
        }
    }

    companion object {
        /** How long a score request waits for its layout before answering 202. */
        const val SCORE_WAIT_MS = 1_500L

        /** A layout running longer stops, and its size is too large. */
        const val LAYOUT_DEADLINE_MS = 8_000L

        /** When a browser asks again after a 202. */
        const val RETRY_MS = 500L

        /** Fresh layouts in a rate window, at most. */
        const val MAX_FRESH = 6
        const val RATE_WINDOW_MS = 30_000L

        /** Layouts kept, and their encoded pages' bytes. */
        const val MAX_LAYOUTS = 2
        const val PAGE_CACHE_BYTES = 1024 * 1024

        /** The notes answer's cap; the score's (ScoreLayoutBudgetTest: crafted files cost seconds and up to 96 MB past them). */
        const val MAX_NOTES = 200_000
        const val MAX_SCORE_NOTES = 60_000
        const val MAX_SCORE_BARS = 20_000

        /** A panel's size: floored to this grid, held to these bounds (CSS px). */
        const val GRID = 16
        const val MIN_WIDTH = 280
        const val MAX_WIDTH = 2000
        const val MIN_HEIGHT = 160
        const val MAX_HEIGHT = 2000

        private const val NANOS_PER_MS = 1_000_000L

        /** [value] floored to the grid, then held to [min]..[max]. */
        fun grid(value: Int, min: Int, max: Int): Int = (value / GRID * GRID).coerceIn(min, max)

        /** One low-priority thread for the layouts, ending when idle. */
        fun defaultDispatcher(): CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "steven-piano-web-score").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.asCoroutineDispatcher()

        /** The tablet's own layout of [source] for [metrics], as Now playing's score makes it (`ScorePages`). */
        fun layoutFor(source: NowSource, metrics: ScoreMetrics, checkpoint: () -> Unit): ScoreLayout {
            val notes = source.notes
            val keys = IntArray(notes.size) { KeyMap.map(notes.note(it), source.transpose, source.fold) }
            val keysMoved = source.keySignatures.map { it.transposed(source.transpose) }
            val hands = source.hands?.takeIf { it.size == notes.size }
            val fingers = source.fingers?.takeIf { it.size == notes.size && hands != null }
            return ScoreLayoutEngine.layout(notes, keys, source.tempo, source.bars, keysMoved, metrics, source.timeSignatures, hands, fingers, checkpoint)
        }
    }
}
