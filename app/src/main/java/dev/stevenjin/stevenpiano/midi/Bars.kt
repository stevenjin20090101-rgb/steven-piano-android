// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.midi

/**
 * Where a piece's bars start. Bar 1 starts at the beginning; each time signature marks off bars of
 * its own length from where it takes over, and a change of signature always starts a new bar,
 * even where it cuts the bar before it short (a pickup, a cadenza). Signatures the score can't use
 * are ignored, and so is one that only repeats the metre already in force. Bars run until the
 * piece ends (the last one holds the last event), and never number more than [MAX_BARS].
 */
object Bars {
    const val MAX_BARS = 100_000

    /** Bar starts in microseconds, through [tempo]: bar 1 at 0. [durationMicros] is the piece's end. */
    fun starts(tempo: TempoMap, signatures: List<TimeSignature>, durationMicros: Long): LongArray {
        val ticks = startTicks(signatures, tempo.ppq, tempo.microsToTicks(durationMicros))
        return LongArray(ticks.size) { tempo.tickToMicros(ticks[it]) }
    }

    /** Bar starts in ticks: bar 1 at 0, then every bar that starts before [durationTicks]. */
    fun startTicks(signatures: List<TimeSignature>, ppq: Int, durationTicks: Long): LongArray {
        val changes = changes(signatures)
        var out = LongArray(64)
        var count = 0
        fun add(tick: Long) {
            if (count == out.size) out = out.copyOf(count * 2)
            out[count++] = tick
        }
        add(0L)
        var metre = TimeSignature.Common
        var metreStart = 0L   // where the metre in force took over: its bars count from there
        var barsInMetre = 0L  // whole bars of it since then
        var next = 0          // the next change to look at
        var barStart = 0L
        while (count < MAX_BARS) {
            // Changes at or before the current bar's start take over there (the last one wins).
            while (next < changes.size && changes[next].tick <= barStart) {
                val change = changes[next++]
                if (!change.sameAs(metre)) {
                    metre = change
                    metreStart = barStart
                    barsInMetre = 0L
                }
            }
            val lineAhead = metreStart + metre.ticksIn(barsInMetre + 1, ppq)
            val nextBar = maxOf(barStart + 1, minOf(lineAhead, changes.getOrNull(next)?.tick ?: Long.MAX_VALUE))
            if (nextBar >= durationTicks) break
            if (nextBar == lineAhead) barsInMetre++
            barStart = nextBar
            add(barStart)
        }
        return out.copyOf(count)
    }

    /**
     * The real changes of metre in time order: usable signatures only, the last of any at one tick,
     * and none that repeats the metre in force (4/4 before the first).
     */
    private fun changes(signatures: List<TimeSignature>): List<TimeSignature> {
        val out = ArrayList<TimeSignature>()
        for (s in signatures.filter { it.valid }.sortedBy { it.tick }) {
            if (out.isNotEmpty() && out.last().tick == s.tick) out.removeAt(out.size - 1)
            if (!s.sameAs(out.lastOrNull() ?: TimeSignature.Common)) out += s
        }
        return out
    }
}
