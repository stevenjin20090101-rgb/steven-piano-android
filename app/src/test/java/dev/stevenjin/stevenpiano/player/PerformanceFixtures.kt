// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser

/** A note as a piece sounds it (v1.16 — M44's tests): its key, when it starts and ends (µs), its velocity, its channel. */
data class Sounded(val key: Int, val on: Long, val off: Long, val velocity: Int, val channel: Int = 0)

/** What a test piece holds, in any order: notes and controllers, at whole milliseconds. */
class Score {
    private class Event(val atMs: Long, val rank: Int, val write: SmfBuilder.Track.() -> Unit)

    private val events = ArrayList<Event>()

    /** A note of [lengthMs] from [atMs]. */
    fun note(atMs: Long, key: Int, lengthMs: Long, velocity: Int = 64, channel: Int = 0) {
        events += Event(atMs, 2) { noteOn(atMs, key, velocity, channel) }
        events += Event(atMs + lengthMs, 1) { noteOff(atMs + lengthMs, key, channel) }
    }

    fun cc(atMs: Long, controller: Int, value: Int) {
        events += Event(atMs, 0) { cc(atMs, controller, value) }
    }

    fun writeTo(track: SmfBuilder.Track) = events.sortedWith(compareBy({ it.atMs }, { it.rank })).forEach { it.write(track) }
}

/** One track at 120 BPM in 4/4, a tick a millisecond (500 to the quarter). */
fun piece(block: Score.() -> Unit): MidiPiece {
    val score = Score().apply(block)
    return SmfParser.parse(SmfBuilder(format = 0, division = 500).track { tempo(0, 500_000); timeSignature(0, 4, 4); score.writeTo(this) }.build())
}

/** The notes [this] sounds, paired per channel and key as the router hears them, in the order they start. */
fun MidiPiece.sounded(): List<Sounded> {
    val open = HashMap<Int, Pair<Long, Int>>()
    val out = ArrayList<Sounded>()
    for (e in events) {
        val source = e.channel * 128 + e.data1
        when (e.command) {
            0x90 -> {
                open.remove(source)?.let { (on, v) -> out += Sounded(e.data1, on, e.atMicros, v, e.channel) }
                open[source] = e.atMicros to e.data2
            }
            0x80 -> open.remove(source)?.let { (on, v) -> out += Sounded(e.data1, on, e.atMicros, v, e.channel) }
        }
    }
    for ((source, held) in open) out += Sounded(source % 128, held.first, events.lastMicros, held.second, source / 128)
    return out.sortedWith(compareBy({ it.on }, { it.key }, { it.channel }))
}

/** The controller events (the pedal) of [this]: time, controller, value. */
fun MidiPiece.controllers(): List<Triple<Long, Int, Int>> = events.filter { it.command == 0xB0 }.map { Triple(it.atMicros, it.data1, it.data2) }

/** Only Repeats at work: no expression, the range natural, the floor as low as it goes. */
val RepeatsOnly = PerformanceSettings(dynamicRange = DynamicRange.NATURAL, velocityFloor = 1, expression = ExpressionLevel.OFF)

/** A MIDI piano: repeats stay as the file has them. */
val FreeRepeats = PianoFacts(freeRepeats = true)

const val MS = 1_000L
