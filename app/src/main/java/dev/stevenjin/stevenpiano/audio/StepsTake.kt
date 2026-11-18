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
 * What the loudness steps' calibration hears (M83), measured as it comes: frames in from the microphone's thread ([add]),
 * strikes in from the main thread ([strike], when each `calstrike` was sent, on `System.nanoTime`'s clock, which the frames
 * carry too), and as soon as a strike's window has been heard its level is taken ([levels]), and the frames no strike
 * still needs are let go: about [KEPT_MS] of the room at a time, none once it is over. Thread-safe.
 *
 * - **A strike's level**: the loudest [BLOCK_MS] of new sound from [FROM_MS] to [TO_MS] after the line went (the coil
 *   energizes as the piano reads it, a few ms later; the next strike comes 300 ms after), in dB of full scale: the
 *   block's power less the power of the [BEFORE_MS] just before the strike, so a ring still dying away (the top keys have
 *   no dampers) isn't taken for the strike.
 * - **The room** ([room]): the 95th percentile of the blocks' levels over its stretch: its usual noise, a cough or a
 *   door left aside.
 */
class StepsTake(private val rate: Int = KeyCheck.RATE) {
    /** One strike's level, for [key] at [pwm]. */
    data class Heard(val key: Int, val pwm: Int, val db: Double)

    private class Waiting(val key: Int, val pwm: Int, val atNanos: Long)

    private val frames = ArrayDeque<MicFrame>()
    private val waiting = ArrayDeque<Waiting>()
    private val heard = ArrayList<Heard>()
    private var lastFrameNanos: Long? = null

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

    /** A `calstrike` for [key] at [pwm] went at [atNanos]. */
    @Synchronized
    fun strike(key: Int, pwm: Int, atNanos: Long) {
        waiting.addLast(Waiting(key, pwm, atNanos))
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
     * The room's level over [fromNanos]..[toNanos], dB of full scale (the 95th percentile of its blocks); null until all of
     * it has been heard, or when none of it was kept.
     */
    @Synchronized
    fun room(fromNanos: Long, toNanos: Long): Double? {
        val heardTo = lastFrameNanos ?: return null
        if (heardTo < toNanos) return null
        val from = indexOf(fromNanos) ?: return null
        val to = indexOf(toNanos) ?: return null
        val samples = window(from, to)
        if (samples.size < block) return null
        val levels = blocks(samples, 0, samples.size).map(::db).sorted()
        if (levels.isEmpty()) return null
        return levels[((levels.size - 1) * ROOM_PERCENTILE).toInt()]
    }

    /** Each waiting strike whose window has been heard, measured in turn. */
    private fun measureReady() {
        val heardTo = lastFrameNanos ?: return
        while (waiting.isNotEmpty()) {
            val next = waiting.first()
            if (heardTo < next.atNanos + TO_MS * NANOS_PER_MS) return
            waiting.removeFirst()
            heard += Heard(next.key, next.pwm, level(next.atNanos))
        }
    }

    /** The new sound's level after a strike sent at [atNanos]: see the class's notes. */
    private fun level(atNanos: Long): Double {
        val at = indexOf(atNanos) ?: return SILENT_DB
        val before = (BEFORE_MS * rate / 1000).toInt()
        val from = (FROM_MS * rate / 1000).toInt()
        val to = (TO_MS * rate / 1000).toInt()
        val samples = window(at - before, at + to)
        val base = power(samples, 0, before)   // the last 30 ms before it: a ring dying away is at its quietest there
        val loudest = blocks(samples, before + from, samples.size).maxOrNull() ?: return SILENT_DB
        return db(maxOf(loudest - base, LEAST_POWER))
    }

    /** The power of each [block]-sample block of [samples] from [from] to [to], one every [hop]. */
    private fun blocks(samples: ShortArray, from: Int, to: Int): List<Double> {
        val out = ArrayList<Double>()
        var start = from
        while (start + block <= to) {
            out += power(samples, start, start + block)
            start += hop
        }
        return out
    }

    /** The mean power of [samples] from [from] to [to], its own mean taken out, full scale 1. */
    private fun power(samples: ShortArray, from: Int, to: Int): Double {
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
            waiting.firstOrNull()?.let { it.atNanos - BEFORE_MS * NANOS_PER_MS } ?: Long.MAX_VALUE,
        )
        while (frames.size > 1) {
            val first = frames.first()
            val ends = first.startNanos + first.samples.size * NANOS_PER_SECOND / rate
            if (ends > keepFromNanos) break
            frames.removeFirst()
        }
    }

    companion object {
        /** A strike's window: from 10 ms to 250 ms after its line went; the baseline the 30 ms before. */
        const val FROM_MS = 10L
        const val TO_MS = 250L
        const val BEFORE_MS = 30L

        /** The level's block: 30 ms (a click a few ms long counts for little in it), one every 10 ms. */
        const val BLOCK_MS = 30L
        const val HOP_MS = 10L

        /** The room's level: this percentile of its blocks. */
        const val ROOM_PERCENTILE = 0.95

        /** What is kept: the last 3 s. */
        const val KEPT_MS = 3_000L

        /** No sound at all, dB: what a strike's level is when nothing new was heard. */
        const val SILENT_DB = -120.0
        private const val LEAST_POWER = 1e-12
        private const val FULL_SCALE = 32_768.0
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MS = 1_000_000L
    }
}
