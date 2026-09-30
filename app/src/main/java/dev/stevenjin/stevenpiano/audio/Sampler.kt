// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import java.util.Arrays
import kotlin.math.log10
import kotlin.math.pow

/**
 * The tablet's piano sound (BUILD_SPEC › v1.8 — M25): a [SoundFont]'s recorded samples played back,
 * never synthesised. A key struck plays every region of the font that holds its key and velocity, each
 * as a voice: the sample read with linear interpolation and moved from its root key to the key played
 * (and from the font's rate to [outputRate]), looped as the region says, shaped by its volume envelope
 * (delay, attack linear in amplitude, hold, then decay, sustain and release linear in decibels, a
 * full change being 100 dB, as SF2 2.04 § 8.1.2 has them), and scaled by the region's attenuation and
 * SF2's default velocity curve (the concave note-on velocity to attenuation modulator, 960 cB: an
 * amplitude of (velocity / 127)²). A stereo pair plays as one voice, its two channels mixed to mono
 * (pan is ignored; the output copies mono to both sides). A key let go releases its voices unless the
 * sustain pedal is down, which holds them until it comes up; a key struck again releases what it
 * still sounded.
 *
 * [polyphony] voices at most sound together; one more steals the oldest, preferring a voice already
 * released, and the stolen voice fades out over [FADE_MS] in one of [FADE_SLOTS] extra slots rather
 * than clicking off. [allOff] releases everything (MIDI's All Notes Off, as the piano's stop sequence
 * ends with); [silence] fades everything out over [FADE_MS] (the tablet sound switched off, or audio
 * focus lost).
 *
 * [render] mixes the voices into mono floats a block at a time ([BLOCK] frames at most), the envelope
 * worked out at each block's end and ramped across it; then the master gain ([volume]: (percent / 100)²
 * of [HEADROOM], ramped over a block when it changes) and the [Limiter], which keeps every sample under
 * [ceiling] (−1 dBFS). Not thread-safe, and allocation-free once made: one thread (the audio thread, or a test) owns
 * it; [PianoVoice] is its thread-safe face.
 */
class Sampler(private val font: SoundFont, val outputRate: Int, val polyphony: Int = POLYPHONY, ceiling: Float = Limiter.CEILING) {
    private val capacity = polyphony + FADE_SLOTS
    private val data = font.data

    // Each voice's state, by slot.
    private val region = arrayOfNulls<Region>(capacity)
    private val keyOf = IntArray(capacity) { -1 }
    private val state = IntArray(capacity)
    private val started = LongArray(capacity)
    private val position = DoubleArray(capacity)
    private val step = DoubleArray(capacity)
    private val noteGain = FloatArray(capacity)
    private val stage = IntArray(capacity)
    private val stageLeft = IntArray(capacity)
    private val env = FloatArray(capacity)
    private val envRate = FloatArray(capacity)
    private val sustainLevel = FloatArray(capacity)
    private val holdFrames = IntArray(capacity)
    private val decayRate = FloatArray(capacity)
    private val releaseRate = FloatArray(capacity)
    private val amp = FloatArray(capacity)
    private val fadeStep = FloatArray(capacity)

    private val mix = FloatArray(BLOCK)
    private val limiter = Limiter(outputRate, ceiling)
    private val fadeFrames = maxOf(1, outputRate * FADE_MS / 1000)
    private val minReleaseFrames = maxOf(1, outputRate * MIN_RELEASE_MS / 1000)
    private var clock = 0L
    private var pedal = false
    private var masterTarget = gainFor(DEFAULT_VOLUME)
    private var master = masterTarget

    /** Voices sounding, fading ones included. */
    val activeVoices: Int
        get() {
            var n = 0
            for (v in 0 until capacity) if (state[v] != FREE) n++
            return n
        }

    /** Voices sounding that count toward [polyphony] (fading ones don't). */
    val liveVoices: Int
        get() {
            var n = 0
            for (v in 0 until capacity) if (state[v] != FREE && state[v] != FADING) n++
            return n
        }

    /** Whether the sustain pedal is down. */
    val sustainDown: Boolean get() = pedal

    /** The limiter's lowest gain since it was last asked (1: it never had to act), for the log; then it starts again. */
    fun takeLowestLimiterGain(): Float = limiter.lowest.also { limiter.resetLowest() }

    /** The keys of the voices still held by a finger or the pedal (for tests and the log). */
    fun heldKeys(): Set<Int> = (0 until capacity).filter { state[it] == HELD || state[it] == SUSTAINED }.map { keyOf[it] }.toSet()

    /** Key [key] struck at [velocity] (1–127; 0 is a release, as MIDI has it). */
    fun noteOn(key: Int, velocity: Int) {
        if (key !in 0..127) return
        if (velocity <= 0) return noteOff(key)
        val vel = velocity.coerceAtMost(127)
        // A key struck again: what it still sounds (held by the pedal, or never let go) is released.
        for (v in 0 until capacity) if (keyOf[v] == key && (state[v] == HELD || state[v] == SUSTAINED)) release(v)
        val velocityGain = velocityGain(vel)
        for (r in font.regionsFor(key)) {
            if (r.matches(key, vel)) start(allocate(), r, key, velocityGain)
        }
    }

    /** Key [key] let go: its voices release, or wait for the pedal to come up while it is down. */
    fun noteOff(key: Int) {
        for (v in 0 until capacity) {
            if (keyOf[v] != key || state[v] != HELD) continue
            if (pedal) state[v] = SUSTAINED else release(v)
        }
    }

    /** The sustain pedal down or up; up releases every voice it held. */
    fun sustain(down: Boolean) {
        pedal = down
        if (down) return
        for (v in 0 until capacity) if (state[v] == SUSTAINED) release(v)
    }

    /** All notes off: the pedal comes up and every voice releases, as a key let go does. */
    fun allOff() {
        pedal = false
        for (v in 0 until capacity) if (state[v] == HELD || state[v] == SUSTAINED) release(v)
    }

    /** Silence within [FADE_MS]: every voice fades out, the pedal comes up. */
    fun silence() {
        pedal = false
        for (v in 0 until capacity) if (state[v] != FREE && state[v] != FADING) fade(v)
    }

    /** The master volume, 0–100 % (0 is silent); it moves over the next block. */
    fun volume(pct: Int) {
        masterTarget = gainFor(pct)
    }

    /** Mixes the next [frames] frames into [out] (mono, from index 0; overwritten, not added to). Every sample is within ±1. */
    fun render(out: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            Arrays.fill(mix, 0, n, 0f)
            for (v in 0 until capacity) if (state[v] != FREE) renderVoice(v, n)
            val from = master
            val slope = (masterTarget - from) / n
            for (i in 0 until n) out[done + i] = limiter.process(mix[i] * (from + slope * (i + 1)))
            master = masterTarget
            done += n
        }
    }

    private fun allocate(): Int {
        if (liveVoices >= polyphony) steal()
        for (v in 0 until capacity) if (state[v] == FREE) return v
        // Every slot busy, the fading ones too: the oldest fading voice is cut.
        var oldest = 0
        for (v in 0 until capacity) if (state[v] == FADING && started[v] < started[oldest]) oldest = v
        return oldest
    }

    /** The oldest voice fades out: a released one if there is one, else the oldest of all. */
    private fun steal() {
        var victim = -1
        for (v in 0 until capacity) if (state[v] == RELEASING && (victim < 0 || started[v] < started[victim])) victim = v
        if (victim < 0) {
            for (v in 0 until capacity) {
                if ((state[v] == HELD || state[v] == SUSTAINED) && (victim < 0 || started[v] < started[victim])) victim = v
            }
        }
        if (victim >= 0) fade(victim)
    }

    private fun start(v: Int, r: Region, key: Int, velocityGain: Float) {
        region[v] = r
        keyOf[v] = key
        state[v] = HELD
        started[v] = clock++
        position[v] = 0.0
        step[v] = pitchStep(r, key, outputRate)
        noteGain[v] = velocityGain * centibelsToGain(r.attenuationCb) * (if (r.partner != null) 0.5f else 1f)
        amp[v] = 0f
        env[v] = 0f
        sustainLevel[v] = (1f - r.sustainCb / 1000f).coerceIn(0f, 1f)
        val fromMiddleC = 60 - key
        holdFrames[v] = framesOf(r.holdTc + r.keyToHold * fromMiddleC)
        decayRate[v] = 1f / maxOf(1, framesOf(r.decayTc + r.keyToDecay * fromMiddleC))
        releaseRate[v] = 1f / maxOf(minReleaseFrames, framesOf(r.releaseTc))
        stage[v] = DELAY
        stageLeft[v] = framesOf(r.delayTc)
        if (stageLeft[v] == 0) enterAttack(v)
    }

    private fun enterAttack(v: Int) {
        val frames = maxOf(1, framesOf(region[v]!!.attackTc))
        stage[v] = ATTACK
        stageLeft[v] = frames
        envRate[v] = 1f / frames
    }

    private fun release(v: Int) {
        state[v] = RELEASING
        // The envelope carries on down from where it is: an attack's amplitude becomes its decibel-domain value.
        env[v] = when (stage[v]) {
            DELAY -> 0f
            ATTACK -> if (env[v] <= 0f) 0f else (1f + 200f * log10(env[v]) / PEAK_CB).coerceIn(0f, 1f)
            else -> env[v]
        }
        stage[v] = RELEASE
        envRate[v] = releaseRate[v]
    }

    private fun fade(v: Int) {
        state[v] = FADING
        fadeStep[v] = maxOf(amp[v], MIN_AUDIBLE) / fadeFrames
    }

    /** Advances voice [v]'s envelope by [n] frames; its amplitude at the block's end (0 once it has ended). */
    private fun envelopeAfter(v: Int, n: Int): Float {
        if (state[v] == FADING) return (amp[v] - fadeStep[v] * n).coerceAtLeast(0f)
        var left = n
        while (left > 0) {
            when (stage[v]) {
                DELAY -> {
                    val used = minOf(left, stageLeft[v])
                    stageLeft[v] -= used
                    left -= used
                    if (stageLeft[v] == 0) enterAttack(v)
                }
                ATTACK -> {
                    val used = minOf(left, stageLeft[v])
                    env[v] = (env[v] + envRate[v] * used).coerceAtMost(1f)
                    stageLeft[v] -= used
                    left -= used
                    if (stageLeft[v] == 0) {
                        env[v] = 1f
                        stage[v] = HOLD
                        stageLeft[v] = holdFrames[v]
                    }
                }
                HOLD -> {
                    val used = minOf(left, stageLeft[v])
                    stageLeft[v] -= used
                    left -= used
                    if (stageLeft[v] == 0) {
                        stage[v] = DECAY
                        envRate[v] = decayRate[v]
                    }
                }
                DECAY -> {
                    env[v] -= envRate[v] * left
                    left = 0
                    if (env[v] <= sustainLevel[v]) {
                        env[v] = sustainLevel[v]
                        stage[v] = SUSTAIN
                    }
                }
                RELEASE -> {
                    env[v] -= envRate[v] * left
                    left = 0
                    if (env[v] <= 0f) {
                        env[v] = 0f
                        stage[v] = DONE
                    }
                }
                else -> left = 0   // SUSTAIN holds; DONE is over
            }
        }
        return when (stage[v]) {
            DELAY, DONE -> 0f
            ATTACK -> env[v]
            else -> envelopeGain(env[v])
        }
    }

    private fun renderVoice(v: Int, n: Int) {
        val r = region[v] ?: return free(v)
        if (stage[v] == DELAY && state[v] != FADING) {
            envelopeAfter(v, n)   // the sample starts with the attack
            return
        }
        val from = amp[v]
        val to = envelopeAfter(v, n)
        var finished = (state[v] == FADING && to <= 0f) || stage[v] == DONE
        val gain = noteGain[v] * SAMPLE_SCALE
        val slope = (to - from) / n
        val base = r.start
        val length = r.end - r.start
        val partner = r.partner
        val partnerBase = partner?.start ?: 0
        val loopStartIndex = r.loopStart - base
        val loopEndIndex = r.loopEnd - base
        val loopEnd = loopEndIndex.toDouble()
        val loopLength = (loopEndIndex - loopStartIndex).toDouble()
        val looping = r.loops && (r.loopMode == Region.LOOP_CONTINUOUS || (state[v] != RELEASING && state[v] != FADING))
        var p = position[v]
        val s = step[v]
        for (i in 0 until n) {
            val index = p.toInt()
            if (index >= length - 1 && !looping) {
                finished = true
                break
            }
            var next = index + 1
            if (looping && next >= loopEndIndex) next = loopStartIndex + (next - loopEndIndex)
            val frac = (p - index).toFloat()
            val a = data.get(base + index).toFloat()
            val b = if (next < length) data.get(base + next).toFloat() else 0f
            var sample = a + (b - a) * frac
            if (partner != null) {
                val a2 = data.get(partnerBase + index).toFloat()
                val b2 = if (next < length) data.get(partnerBase + next).toFloat() else 0f
                sample += a2 + (b2 - a2) * frac
            }
            mix[i] += sample * gain * (from + slope * (i + 1))
            p += s
            if (looping && p >= loopEnd) p -= loopLength * (((p - loopEnd) / loopLength).toInt() + 1)
        }
        position[v] = p
        amp[v] = to
        if (finished) free(v)
    }

    private fun free(v: Int) {
        state[v] = FREE
        region[v] = null
        keyOf[v] = -1
        amp[v] = 0f
    }

    private fun framesOf(timecents: Int): Int {
        if (timecents <= Sf2Reader.INSTANT_TC) return 0
        return (2.0.pow(timecents.coerceAtMost(MAX_TC) / 1200.0) * outputRate).toInt()
    }

    companion object {
        /** Voices at once (a stereo pair is one). */
        const val POLYPHONY = 48

        /** Extra slots where stolen and silenced voices fade out. */
        const val FADE_SLOTS = 16

        /** How long a stolen or silenced voice takes to fade out. */
        const val FADE_MS = 12

        /** Frames between two envelope points. */
        const val BLOCK = 64

        /**
         * The master gain at 100 %, before the limiter (+6 dB): measured on the Upright Piano KW, Clair de lune
         * (most velocities 28–52) plays at −31.6 dBFS RMS at the default 60 % and Chopin's Revolutionary étude
         * at −19.3 dBFS with at most 3 dB of limiting; at 100 % the soft pieces reach −22 dBFS and only the
         * loudest are held down hard (BUILD_SPEC › v1.8 — M25 › Measured).
         */
        const val HEADROOM = 2f

        /** The volume at first (Piano › Playback › TABLET SOUND). */
        const val DEFAULT_VOLUME = 60

        /** A full change of the volume envelope: 100 dB (SF2 2.04 § 8.1.2, decay and release). */
        const val PEAK_CB = 1000f

        /** A release never shorter than this (a sample cut in one step clicks). */
        const val MIN_RELEASE_MS = 10

        /** An envelope time never longer than 100 s. */
        private const val MAX_TC = 7_973

        private const val SAMPLE_SCALE = 1f / 32_768f
        private const val MIN_AUDIBLE = 1e-5f

        // Voice states.
        private const val FREE = 0
        private const val HELD = 1
        private const val SUSTAINED = 2
        private const val RELEASING = 3
        private const val FADING = 4

        // Envelope stages.
        private const val DELAY = 0
        private const val ATTACK = 1
        private const val HOLD = 2
        private const val DECAY = 3
        private const val SUSTAIN = 4
        private const val RELEASE = 5
        private const val DONE = 6

        /** The master gain at [pct] %: (pct / 100)² of [HEADROOM]; 0 is silence. */
        fun gainFor(pct: Int): Float {
            val x = pct.coerceIn(0, 100) / 100f
            return x * x * HEADROOM
        }

        /**
         * SF2's default note-on velocity to initial attenuation modulator (SF2 2.04 § 8.4.1: concave,
         * negative, 960 cB) as a gain: −40·log10(velocity / 127) dB, which is (velocity / 127)².
         */
        fun velocityGain(velocity: Int): Float {
            val x = velocity.coerceIn(0, 127) / 127f
            return x * x
        }

        /** How far a voice moves through its sample for each output frame when [region] plays [key]. */
        fun pitchStep(region: Region, key: Int, outputRate: Int): Double {
            val cents = (key - region.rootKey) * region.scaleTuning + region.tuneCents
            return 2.0.pow(cents / 1200.0) * region.rate / outputRate
        }

        /** Centibels of attenuation as a gain: 10^(−cb / 200). */
        fun centibelsToGain(cb: Int): Float = 10.0.pow(-cb / 200.0).toFloat()

        /** The envelope's decibel-domain value (1 full scale, 0 at −100 dB) as a gain. */
        fun envelopeGain(value: Float): Float = if (value <= 0f) 0f else 10.0.pow(-(PEAK_CB * (1f - value)) / 200.0).toFloat()
    }
}
