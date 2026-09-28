// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

/**
 * A note the model heard: MIDI [pitch] (21 = A0 … 108 = C8), [onset] and [offset] in seconds from the
 * recording's start, and [velocity] as the package computes it, `int(v × 128)`, which may read 0 or
 * 128 (the MIDI writer clamps it to 1–127).
 */
data class TranscribedNote(val onset: Float, val offset: Float, val pitch: Int, val velocity: Int)

/** The sustain pedal down at [onset] and up at [offset], in seconds. */
data class PedalEvent(val onset: Float, val offset: Float)

/** What a recording turned into: its notes, in the package's order (by key, A0 up, then onset), and its pedal. */
class Transcription(val notes: List<TranscribedNote>, val pedals: List<PedalEvent>)

/**
 * The transcription model's frames into notes and pedal (v1.7 — M23): a port of
 * `piano_transcription_inference`'s `RegressionPostProcessor` with `piano_vad`'s
 * `note_detection_with_onset_offset_regress` and `pedal_detection_with_onset_offset_regress`
 * (0.0.6, MIT; docs/STUDIO_SPIKE.md › The contract for M23), the package's thresholds by default
 * (onset 0.3, offset 0.3, frame 0.1, pedal offset 0.2, pedal frame 0.5). Pure, and it must give the
 * package's own answer: `NotePostProcessorTest` reproduces `tools/studio/fixtures/transcription_window.json`.
 *
 * - **Onsets and offsets**: a frame is a peak where the regression output exceeds its threshold and
 *   falls away monotonically for 2 frames each side (onsets) or 4 (offsets); frames closer than that
 *   to either end are never peaks. The peak's shift, in frames, is `(x[n+1] − x[n−1]) / (x[n] − x[n±1]) / 2`
 *   against the larger neighbour, in float32 as numpy computes it.
 * - **Notes**, per key: an onset opens a note (closing one still open at the frame before, with no
 *   offset shift); then the first frame whose `frame` output is at most 0.1 and the first offset peak
 *   are watched for, and when the frame falls the note ends at the offset peak if that came later than
 *   halfway, else at the fall; a note that has not ended after 600 frames, or at the last frame, ends
 *   there. Times are `(frame + shift) / 100` s (computed in float64, kept as float32, as the package
 *   does); velocity `int(velocity_output[onset] × 128)`.
 * - **Pedal**: down at a frame where `pedal_frame` is at least 0.5 and rising (no shift); up at the
 *   first offset peak after it, or where `pedal_frame` fell to 0.5 or below if no peak comes within 10
 *   frames of that. A pedal still down at the end is not reported (the package's rule).
 *   `reg_pedal_onset_output` is not used.
 *
 * The package's Python truth tests (`if bgn:`) treat frame 0 as "none", and so does this port. It
 * runs as the frames come ([append], a window's frames at a time, in order) and needs only the last
 * few, so a long recording never holds all its outputs: a frame is decided once the four after it
 * are there, and the last ones at [finish]. The answer is the same whatever the pieces.
 */
class NotePostProcessor(
    private val onsetThreshold: Float = ONSET_THRESHOLD,
    private val offsetThreshold: Float = OFFSET_THRESHOLD,
    private val frameThreshold: Float = FRAME_THRESHOLD,
    private val pedalOffsetThreshold: Float = PEDAL_OFFSET_THRESHOLD,
    private val pedalFrameThreshold: Float = PEDAL_FRAME_THRESHOLD,
) {
    // The last RING frames of each output ([frame % RING] × CLASSES + key).
    private val onsetRing = FloatArray(RING * CLASSES)
    private val offsetRing = FloatArray(RING * CLASSES)
    private val frameRing = FloatArray(RING * CLASSES)
    private val velocityRing = FloatArray(RING * CLASSES)
    private val pedalOffsetRing = FloatArray(RING)
    private val pedalFrameRing = FloatArray(RING)

    /** Frames received, and the next frame to decide. */
    private var received = 0
    private var next = 0

    // Each key's open note: its onset frame (NONE: none), where the frame fell and the first offset
    // peak after it (NONE: not yet), and the onset's shift and velocity and those frames' offset shifts.
    private val bgn = IntArray(CLASSES) { NONE }
    private val frameDisappear = IntArray(CLASSES) { NONE }
    private val offsetOccur = IntArray(CLASSES) { NONE }
    private val onsetShift = FloatArray(CLASSES)
    private val onsetVelocity = FloatArray(CLASSES)
    private val disappearShift = FloatArray(CLASSES)
    private val occurShift = FloatArray(CLASSES)
    private val closed = Array(CLASSES) { ArrayList<TranscribedNote>() }

    private var pedalBgn = NONE
    private var pedalDisappear = NONE
    private var pedalOccur = NONE
    private var pedalDisappearShift = 0f
    private var pedalOccurShift = 0f
    private val pedals = ArrayList<PedalEvent>()
    private var finished = false

    /**
     * Rows [from] until [until] of one window's outputs, in order after everything given before: the
     * note outputs [onset], [offset], [frame], [velocity] shaped `[rows][88]`, the pedal outputs
     * [pedalOffset] and [pedalFrame] shaped `[rows][1]`.
     */
    fun append(
        onset: FloatArray,
        offset: FloatArray,
        frame: FloatArray,
        velocity: FloatArray,
        pedalOffset: FloatArray,
        pedalFrame: FloatArray,
        from: Int,
        until: Int,
    ) {
        check(!finished) { "finished" }
        for (row in from until until) {
            val slot = received % RING
            System.arraycopy(onset, row * CLASSES, onsetRing, slot * CLASSES, CLASSES)
            System.arraycopy(offset, row * CLASSES, offsetRing, slot * CLASSES, CLASSES)
            System.arraycopy(frame, row * CLASSES, frameRing, slot * CLASSES, CLASSES)
            System.arraycopy(velocity, row * CLASSES, velocityRing, slot * CLASSES, CLASSES)
            pedalOffsetRing[slot] = pedalOffset[row]
            pedalFrameRing[slot] = pedalFrame[row]
            received++
            // A frame is decided once the frames its peaks look at (four after it) are here.
            while (next + LOOKAHEAD < received) decide(next++, total = -1)
        }
    }

    /** The end of the frames: the last ones decided, and the notes and pedal in the package's order. */
    fun finish(): Transcription {
        check(!finished) { "finished" }
        finished = true
        while (next < received) decide(next++, total = received)
        return Transcription(closed.flatMap { it }, pedals.toList())
    }

    /** Frame [i]; [total] is the number of frames once they are all known (-1 while they come). */
    private fun decide(i: Int, total: Int) {
        val last = total >= 0 && i == total - 1
        val slot = i % RING
        for (k in 0 until CLASSES) {
            val onsetPeak = peak(onsetRing, k, i, 2, onsetThreshold, total)
            val offsetPeak = peak(offsetRing, k, i, 4, offsetThreshold, total)
            val onsetPeakShift = if (onsetPeak) shift(onsetRing, k, i) else 0f
            val offsetPeakShift = if (offsetPeak) shift(offsetRing, k, i) else 0f

            if (onsetPeak) {
                if (truthy(bgn[k])) {   // consecutive onsets: the open note ends the frame before
                    close(k, maxOf(i - 1, 0), 0f)
                    frameDisappear[k] = NONE
                    offsetOccur[k] = NONE
                }
                bgn[k] = i
                onsetShift[k] = onsetPeakShift
                onsetVelocity[k] = velocityRing[slot * CLASSES + k]
            }
            val start = bgn[k]
            if (truthy(start) && i > start) {
                if (frameRing[slot * CLASSES + k] <= frameThreshold && !truthy(frameDisappear[k])) {
                    frameDisappear[k] = i
                    disappearShift[k] = offsetPeakShift
                }
                if (offsetPeak && !truthy(offsetOccur[k])) {
                    offsetOccur[k] = i
                    occurShift[k] = offsetPeakShift
                }
                if (truthy(frameDisappear[k])) {
                    val occur = offsetOccur[k]
                    val fall = frameDisappear[k]
                    if (truthy(occur) && occur - start > fall - occur) close(k, occur, occurShift[k]) else close(k, fall, disappearShift[k])
                    reset(k)
                }
                if (truthy(bgn[k]) && (i - bgn[k] >= MAX_NOTE_FRAMES || last)) {
                    close(k, i, offsetPeakShift)
                    reset(k)
                }
            }
        }
        pedal(i, total)
    }

    private fun pedal(i: Int, total: Int) {
        if (i < 1) return
        val slot = i % RING
        val value = pedalFrameRing[slot]
        val offsetPeak = peak(pedalOffsetRing, 0, i, 4, pedalOffsetThreshold, total, stride = 1)
        val offsetShift = if (offsetPeak) shift(pedalOffsetRing, 0, i, stride = 1) else 0f
        if (value >= pedalFrameThreshold && value > pedalFrameRing[(i - 1) % RING] && !truthy(pedalBgn)) pedalBgn = i
        val start = pedalBgn
        if (truthy(start) && i > start) {
            if (value <= pedalFrameThreshold && !truthy(pedalDisappear)) {
                pedalDisappear = i
                pedalDisappearShift = offsetShift
            }
            if (offsetPeak && !truthy(pedalOccur)) {
                pedalOccur = i
                pedalOccurShift = offsetShift
            }
            if (truthy(pedalOccur)) {
                pedals += PedalEvent(seconds(start, 0f), seconds(pedalOccur, pedalOccurShift))
                pedalBgn = NONE
                pedalDisappear = NONE
                pedalOccur = NONE
            }
            if (truthy(pedalDisappear) && i - pedalDisappear >= PEDAL_WAIT_FRAMES) {
                pedals += PedalEvent(seconds(start, 0f), seconds(pedalDisappear, pedalDisappearShift))
                pedalBgn = NONE
                pedalDisappear = NONE
                pedalOccur = NONE
            }
        }
    }

    private fun close(k: Int, fin: Int, finShift: Float) {
        closed[k] += TranscribedNote(
            onset = seconds(bgn[k], onsetShift[k]),
            offset = seconds(fin, finShift),
            pitch = BEGIN_NOTE + k,
            velocity = (onsetVelocity[k] * VELOCITY_SCALE).toInt(),
        )
    }

    private fun reset(k: Int) {
        bgn[k] = NONE
        frameDisappear[k] = NONE
        offsetOccur[k] = NONE
    }

    /**
     * Whether frame [n] of column [k] is a peak: above [threshold], falling away for [neighbour] frames
     * each side, and at least [neighbour] frames from either end ([total] once the end is known).
     */
    private fun peak(ring: FloatArray, k: Int, n: Int, neighbour: Int, threshold: Float, total: Int, stride: Int = CLASSES): Boolean {
        if (n < neighbour) return false
        if (total >= 0 && n >= total - neighbour) return false
        val x = at(ring, k, n, stride)
        if (!(x > threshold)) return false
        for (d in 0 until neighbour) {
            if (at(ring, k, n - d, stride) < at(ring, k, n - d - 1, stride)) return false
            if (at(ring, k, n + d, stride) < at(ring, k, n + d + 1, stride)) return false
        }
        return true
    }

    /** The peak's shift, in frames (float32 arithmetic, as numpy's); 0 where the three frames are level. */
    private fun shift(ring: FloatArray, k: Int, n: Int, stride: Int = CLASSES): Float {
        val before = at(ring, k, n - 1, stride)
        val x = at(ring, k, n, stride)
        val after = at(ring, k, n + 1, stride)
        val shift = if (before > after) (after - before) / (x - after) / 2f else (after - before) / (x - before) / 2f
        return if (shift.isNaN()) 0f else shift
    }

    private fun at(ring: FloatArray, k: Int, n: Int, stride: Int): Float = ring[(n % RING) * stride + k]

    companion object {
        const val CLASSES = 88
        const val BEGIN_NOTE = 21
        const val FRAMES_PER_SECOND = 100
        const val VELOCITY_SCALE = 128
        const val ONSET_THRESHOLD = 0.3f
        const val OFFSET_THRESHOLD = 0.3f
        const val FRAME_THRESHOLD = 0.1f
        const val PEDAL_OFFSET_THRESHOLD = 0.2f
        const val PEDAL_FRAME_THRESHOLD = 0.5f

        /** A note is cut after this many frames (6 s) without an offset. */
        const val MAX_NOTE_FRAMES = 600

        /** Frames the pedal waits for an offset peak after its frame output fell. */
        const val PEDAL_WAIT_FRAMES = 10

        /** The farthest a peak looks ahead (the offsets' four frames). */
        private const val LOOKAHEAD = 4

        /** Frames kept: a peak looks four back and four ahead, and the pedal one back. */
        private const val RING = 16
        private const val NONE = -1

        /** Python's truth of an int that may be None: neither none nor zero. */
        private fun truthy(frame: Int): Boolean = frame > 0

        /** `(frame + shift) / 100` in float64, then float32: the package's arithmetic. */
        fun seconds(frame: Int, shift: Float): Float = ((frame.toDouble() + shift.toDouble()) / FRAMES_PER_SECOND).toFloat()
    }
}
