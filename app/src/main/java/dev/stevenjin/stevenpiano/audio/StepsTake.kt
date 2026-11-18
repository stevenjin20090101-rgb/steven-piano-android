// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import kotlin.math.log10

/**
 * What the loudness steps' calibration hears (M83; M83b: in each key's own bands, [KeyBands]), measured as it comes: frames
 * in from the microphone's thread ([add]), strikes in from the main thread ([strike], when each `calstrike`'s reply came,
 * on `System.nanoTime`'s clock, which the frames carry too), and as soon as a strike's window has been heard its level is
 * taken ([levels]), and the frames nothing still needs are let go: about [KEPT_MS] of the room at a time. Thread-safe.
 *
 * - **A strike's level**, dB of full scale: its key's in-band power from [FROM_MS] to [TO_MS] after the reply (the window
 *   ends before the release and the damper), but no more than twice its last half's ([LATE_MS] on): a string lasts, a
 *   solenoid's click is over in a few ms, so a click alone, with no in-band sound that lasts, reads as the room.
 * - **The room** ([room]): broadband, the 95th percentile of its [BLOCK_MS] blocks, for the outer sanity stop; and per key
 *   ([rooms]), each key's in-band level at rest, the 95th percentile of the same windows a strike is heard in.
 * - **A key now** ([bandNow]): its in-band power over the last [NOW_MS] heard, for an undamped key's ring to die away.
 */
class StepsTake(private val rate: Int = KeyCheck.RATE) {
    /** One strike's level, for [key] at [pwm]. */
    data class Heard(val key: Int, val pwm: Int, val db: Double)

    private class Waiting(val key: Int, val pwm: Int, val repliedNanos: Long)

    private val frames = ArrayDeque<MicFrame>()
    private val waiting = ArrayDeque<Waiting>()
    private val heard = ArrayList<Heard>()
    private var lastFrameNanos: Long? = null
    private val power = KeyBands.Power(rate)

    private val block = (BLOCK_MS * rate / 1000).toInt()
    private val hop = (HOP_MS * rate / 1000).toInt()

    /** A frame heard (the microphone's thread). */
    @Synchronized
    fun add(frame: MicFrame) {
        frames.addLast(frame)
        lastFrameNanos = frame.startNanos + frame.samples.size * NANOS_PER_SECOND / rate
        measureReady()
        forget()
    }

    /** A `calstrike` for [key] at [pwm] was answered at [repliedNanos]. */
    @Synchronized
    fun strike(key: Int, pwm: Int, repliedNanos: Long) {
        waiting.addLast(Waiting(key, pwm, repliedNanos))
        measureReady()
    }

    /** Every strike measured so far, in the order struck. */
    @Synchronized
    fun levels(): List<Heard> = heard.toList()

    /** The strikes struck and not measured yet. */
    @Synchronized
    fun unmeasured(): Int = waiting.size

    /** When the last sample heard was heard (`System.nanoTime`); null before any. */
    @Synchronized
    fun heardTo(): Long? = lastFrameNanos

    /**
     * The room's broadband level over [fromNanos]..[toNanos], dB of full scale (the 95th percentile of its blocks); null until
     * all of it has been heard, or when none of it was kept.
     */
    @Synchronized
    fun room(fromNanos: Long, toNanos: Long): Double? {
        val samples = heardSpan(fromNanos, toNanos) ?: return null
        if (samples.size < block) return null
        val levels = ArrayList<Double>()
        var start = 0
        while (start + block <= samples.size) {
            levels += db(meanSquare(samples, start, start + block))
            start += hop
        }
        return percentile(levels)
    }

    /**
     * Each of [keys]'s in-band level at rest over [fromNanos]..[toNanos], dB: the 95th percentile of the strike's windows
     * (every [ROOM_HOP_MS]) laid over it; null until all of it has been heard.
     */
    @Synchronized
    fun rooms(fromNanos: Long, toNanos: Long, keys: IntRange): Map<Int, Double>? {
        val samples = heardSpan(fromNanos, toNanos) ?: return null
        val window = samplesOf(TO_MS - FROM_MS)
        val step = samplesOf(ROOM_HOP_MS)
        if (samples.size < window) return null
        val each = keys.associateWith { ArrayList<Double>() }
        var start = 0
        while (start + window <= samples.size) {
            for ((key, inBand) in power.ofEach(samples, start, window, keys)) each.getValue(key) += db(inBand)
            start += step
        }
        return each.mapValues { (_, levels) -> percentile(levels) ?: SILENT_DB }
    }

    /** Key [key]'s in-band level over the last [NOW_MS] heard, dB, and when that stretch ended; null before enough. */
    @Synchronized
    fun bandNow(key: Int): Pair<Double, Long>? {
        val to = lastFrameNanos ?: return null
        val from = to - NOW_MS * NANOS_PER_MS
        val samples = heardSpan(from, to) ?: return null
        return db(power.of(samples, 0, samples.size, key)) to to
    }

    /** Each waiting strike whose window has been heard, measured in turn. */
    private fun measureReady() {
        val heardTo = lastFrameNanos ?: return
        while (waiting.isNotEmpty()) {
            val next = waiting.first()
            if (heardTo < next.repliedNanos + TO_MS * NANOS_PER_MS) return
            waiting.removeFirst()
            heard += Heard(next.key, next.pwm, level(next.key, next.repliedNanos))
        }
    }

    /** The strike answered at [repliedNanos], in [key]'s bands: see the class's notes. */
    private fun level(key: Int, repliedNanos: Long): Double {
        val from = indexOf(repliedNanos + FROM_MS * NANOS_PER_MS) ?: return SILENT_DB
        val count = samplesOf(TO_MS - FROM_MS)
        val late = samplesOf(TO_MS - LATE_MS)
        val samples = window(from, from + count)
        val whole = power.of(samples, 0, count, key)
        val last = power.of(samples, count - late, late, key)
        return db(minOf(whole, LASTS * last))
    }

    /** The samples heard over [fromNanos]..[toNanos]; null until all of it has been heard. */
    private fun heardSpan(fromNanos: Long, toNanos: Long): ShortArray? {
        val heardTo = lastFrameNanos ?: return null
        if (heardTo < toNanos) return null
        val from = indexOf(fromNanos) ?: return null
        val to = indexOf(toNanos) ?: return null
        return window(from, to)
    }

    private fun samplesOf(ms: Long): Int = (ms * rate / 1000).toInt()

    /** The mean square of [samples] from [from] to [to], its own mean taken out, full scale 1. */
    private fun meanSquare(samples: ShortArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) sum += samples[i]
        val mean = sum / (to - from)
        var squares = 0.0
        for (i in from until to) {
            val v = (samples[i] - mean) / FULL_SCALE
            squares += v * v
        }
        return squares / (to - from)
    }

    private fun percentile(levels: List<Double>): Double? {
        if (levels.isEmpty()) return null
        val sorted = levels.sorted()
        return sorted[((sorted.size - 1) * ROOM_PERCENTILE).toInt()]
    }

    private fun db(power: Double): Double = 10.0 * log10(maxOf(power, LEAST_POWER))

    /** The hearing's sample number at [nanos], from the latest frame's own time; null before the first. */
    private fun indexOf(nanos: Long): Long? {
        val frame = frames.lastOrNull() ?: return null
        return frame.index + (nanos - frame.startNanos) * rate / NANOS_PER_SECOND
    }

    /** The samples [from] to [to] of the hearing; silence where none was kept. */
    private fun window(from: Long, to: Long): ShortArray {
        val out = ShortArray((to - from).toInt().coerceAtLeast(0))
        for (frame in frames) {
            val start = maxOf(from, frame.index)
            val end = minOf(to, frame.index + frame.samples.size)
            if (start >= end) continue
            System.arraycopy(frame.samples, (start - frame.index).toInt(), out, (start - from).toInt(), (end - start).toInt())
        }
        return out
    }

    /** Lets go of the frames older than [KEPT_MS], keeping any a waiting strike still needs. */
    private fun forget() {
        val latest = frames.lastOrNull() ?: return
        val keepFromNanos = minOf(
            latest.startNanos - KEPT_MS * NANOS_PER_MS,
            waiting.firstOrNull()?.repliedNanos ?: Long.MAX_VALUE,
        )
        while (frames.size > 1) {
            val first = frames.first()
            val ends = first.startNanos + first.samples.size * NANOS_PER_SECOND / rate
            if (ends > keepFromNanos) break
            frames.removeFirst()
        }
    }

    companion object {
        /** A strike's window: 20 to 100 ms after its reply, before the release and the damper; its last half from 60 ms. */
        const val FROM_MS = 20L
        const val TO_MS = 100L
        const val LATE_MS = 60L

        /** A strike's level is at most this many times its last half's power: it must last. */
        const val LASTS = 2.0

        /** The broadband room's block: 30 ms, one every 10 ms. */
        const val BLOCK_MS = 30L
        const val HOP_MS = 10L

        /** The room per key: a strike's window laid every 20 ms over it. */
        const val ROOM_HOP_MS = 20L

        /** A key now: its last 80 ms. */
        const val NOW_MS = 80L

        /** The room's level: this percentile. */
        const val ROOM_PERCENTILE = 0.95

        /** What is kept: the last 3 s. */
        const val KEPT_MS = 3_000L

        /** No sound at all, dB. */
        const val SILENT_DB = -120.0
        private const val LEAST_POWER = 1e-12
        private const val FULL_SCALE = 32_768.0
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MS = 1_000_000L
    }
}
