// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.studio.compose.Composition
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** A note as the cover reads it: its [onMs] and [offMs], its MIDI [key] and [velocity]. */
class CoverNote(val onMs: Long, val offMs: Long, val key: Int, val velocity: Int)

/**
 * What a Studio piece's cover is drawn from (v1.12 — M30): its [notes] (null: a cover drawn at once from the
 * settings, before there are any), its [key] and [mood] (null when not known: the key is read from the notes,
 * the mood from how hard they are played), its [bpm], and the job's random [seed], which gives a piece its own
 * accent and placement.
 */
class CoverInput(val notes: List<CoverNote>?, val key: MusicKey?, val mood: Mood?, val bpm: Int?, val seed: Long) {
    companion object {
        fun of(composition: Composition, key: MusicKey?, mood: Mood?, bpm: Int?, seed: Long) = CoverInput(
            composition.notes.map { CoverNote(it.onMicros / 1_000, it.offMicros / 1_000, it.key, it.velocity) },
            key, mood, bpm, seed,
        )

        fun of(notes: NoteList, key: MusicKey?, mood: Mood?, bpm: Int?, seed: Long) = CoverInput(
            (0 until notes.size).map { CoverNote(notes.startMicros[it] / 1_000, notes.endMicros[it] / 1_000, notes.note(it), notes.velocity(it)) },
            key, mood, bpm, seed,
        )

        /** Before a note is written: the cover the card shows at once. */
        fun provisional(key: MusicKey?, mood: Mood?, bpm: Int?, seed: Long) = CoverInput(null, key, mood, bpm, seed)
    }
}

/**
 * The cover's design, worked out from its [CoverInput]: the palette (a paper or an ink ground from the key's mode
 * and the mood, within the app's own range, and one accent of the piece's own: a hue from the key's place on the
 * circle of fifths, its strength from the mood, nudged by the seed), and the shapes: the pitch-class histogram as
 * twelve rays around a disc (circle-of-fifths order), the disc set high or low by the register, the density of
 * notes over time as a band along the foot, and every note a faint perforation, time across and pitch up. The
 * look can change without touching what it is drawn from.
 */
class CoverSpec private constructor(
    val ground: Int,
    val groundLow: Int,
    val accent: Int,
    val ink: Int,
    val histogram: DoubleArray,
    val density: DoubleArray,
    val register: Double,
    val notes: List<CoverNote>,
    val spanMs: Long,
    val turn: Double,
) {
    companion object {
        private const val BINS = 48

        fun of(input: CoverInput): CoverSpec {
            val random = Random(input.seed)
            val notes = input.notes.orEmpty().filter { it.offMs >= it.onMs }.sortedWith(compareBy({ it.onMs }, { it.key }))
            val key = input.key ?: keyOf(notes)
            val mood = input.mood ?: moodOf(notes)
            val dark = key.minor || mood == Mood.Melancholy || mood == Mood.Wild
            val fifths = Math.floorMod(key.tonic * 7, 12)
            val hue = Math.floorMod((fifths * 30 + 210 + (if (key.minor) -18 else 0) + random.nextInt(-12, 13)).toLong(), 360L).toDouble()
            val (saturation, lightShift) = when (mood) {
                Mood.Calm -> 0.32 to 0.0
                Mood.Bright -> 0.55 to 0.06
                Mood.Wild -> 0.62 to -0.04
                Mood.Melancholy -> 0.26 to -0.06
            }
            val ground = if (dark) hsl(hue, 0.10, 0.08) else hsl(hue, 0.18, 0.93)
            val groundLow = if (dark) hsl(hue, 0.14, 0.13) else hsl(hue, 0.20, 0.86)
            // The accent stands well apart from the ground in lightness, so the cover reads in black and white too.
            val accent = hsl(hue, saturation, (if (dark) 0.64 else 0.42) + lightShift)
            val ink = if (dark) hsl(hue, 0.08, 0.90) else hsl(hue, 0.10, 0.12)
            val histogram = DoubleArray(12)
            if (notes.isEmpty()) {
                val scale = if (key.minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
                for ((i, degree) in scale.withIndex()) histogram[(key.tonic + degree) % 12] = if (i == 0) 1.0 else if (i == 4) 0.8 else 0.45 + 0.1 * random.nextDouble()
            } else {
                for (n in notes) histogram[Math.floorMod(n.key, 12)] += (n.offMs - n.onMs).coerceIn(50L, 2_000L).toDouble()
            }
            val most = histogram.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            for (i in 0 until 12) histogram[i] /= most
            val span = notes.lastOrNull()?.let { last -> max(1L, max(last.offMs, notes.maxOf { it.offMs }) - notes.first().onMs) } ?: 1L
            val density = DoubleArray(BINS)
            if (notes.isEmpty()) {
                val level = when (mood) {
                    Mood.Calm -> 0.35
                    Mood.Melancholy -> 0.4
                    Mood.Bright -> 0.6
                    Mood.Wild -> 0.8
                }
                for (i in 0 until BINS) density[i] = level + 0.15 * StrictMath.sin(i * 0.4 + random.nextDouble() * 6.28)
            } else {
                val start = notes.first().onMs
                for (n in notes) density[((n.onMs - start) * BINS / (span + 1)).toInt().coerceIn(0, BINS - 1)] += 1.0
                val peak = density.maxOrNull()?.takeIf { it > 0 } ?: 1.0
                for (i in 0 until BINS) density[i] /= peak
            }
            val register = if (notes.isEmpty()) 0.5 else ((notes.sumOf { it.key }.toDouble() / notes.size - 36.0) / 60.0).coerceIn(0.0, 1.0)
            return CoverSpec(ground, groundLow, accent, ink, histogram, smooth(density), register, notes, span, random.nextDouble())
        }

        /** The key by Krumhansl–Kessler profiles over how long each pitch class sounds; C major for nothing. */
        private fun keyOf(notes: List<CoverNote>): MusicKey {
            val weight = DoubleArray(12)
            for (n in notes) weight[Math.floorMod(n.key, 12)] += (n.offMs - n.onMs).coerceIn(50L, 2_000L).toDouble()
            if (weight.all { it == 0.0 }) return MusicKey.C
            var best = MusicKey.C
            var bestScore = Double.NEGATIVE_INFINITY
            for (minor in listOf(false, true)) for (tonic in 0..11) {
                val profile = if (minor) MINOR else MAJOR
                var score = 0.0
                for (pc in 0..11) score += weight[pc] * profile[Math.floorMod(pc - tonic, 12)]
                if (score > bestScore + 1e-9) {
                    bestScore = score
                    best = MusicKey(tonic, minor)
                }
            }
            return best
        }

        /** The mood by touch and pace: soft and sparse is calm, loud or busy is wild. */
        private fun moodOf(notes: List<CoverNote>): Mood {
            if (notes.isEmpty()) return Mood.Calm
            val velocity = notes.sumOf { it.velocity }.toDouble() / notes.size
            val span = max(1L, notes.last().onMs - notes.first().onMs)
            val perSecond = notes.size * 1000.0 / span
            return when {
                velocity > 74 || perSecond > 9 -> Mood.Wild
                velocity > 60 || perSecond > 5 -> Mood.Bright
                else -> Mood.Calm
            }
        }

        private fun smooth(values: DoubleArray): DoubleArray = DoubleArray(values.size) { i ->
            val a = values[max(0, i - 1)]
            val b = values[i]
            val c = values[min(values.size - 1, i + 1)]
            (a + 2 * b + c) / 4
        }

        private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /** HSL to an opaque ARGB colour (hue in degrees). */
        fun hsl(hue: Double, saturation: Double, lightness: Double): Int {
            val l = lightness.coerceIn(0.0, 1.0)
            val s = saturation.coerceIn(0.0, 1.0)
            val c = (1 - StrictMath.abs(2 * l - 1)) * s
            val h = ((hue % 360.0) + 360.0) % 360.0 / 60.0
            val x = c * (1 - StrictMath.abs(h % 2 - 1))
            val (r, g, b) = when {
                h < 1 -> Triple(c, x, 0.0)
                h < 2 -> Triple(x, c, 0.0)
                h < 3 -> Triple(0.0, c, x)
                h < 4 -> Triple(0.0, x, c)
                h < 5 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            val m = l - c / 2
            fun channel(v: Double) = ((v + m) * 255 + 0.5).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }
}

/**
 * Draws a [CoverSpec] (v1.12 — M30): a [size] × [size] opaque ARGB image, row by row from the top. Pure and
 * deterministic (`StrictMath`): the same piece gives the same pixels on every device.
 */
object StudioCover {
    const val SIZE = 768

    fun render(input: CoverInput, size: Int = SIZE): IntArray = render(CoverSpec.of(input), size)

    fun render(spec: CoverSpec, size: Int = SIZE): IntArray {
        val px = IntArray(size * size)
        val s = size.toDouble()
        // The ground: a vertical wash between its two tones.
        for (y in 0 until size) {
            val row = mix(spec.ground, spec.groundLow, y / s)
            for (x in 0 until size) px[y * size + x] = row
        }
        // Every note a faint perforation: time across, pitch up.
        if (spec.notes.isNotEmpty()) {
            val start = spec.notes.first().onMs
            val dash = max(2, size / 160)
            for (n in spec.notes) {
                val x0 = ((n.onMs - start).toDouble() / spec.spanMs * (s * 0.9) + s * 0.05).toInt()
                val x1 = min(size - 1, x0 + max(dash, ((n.offMs - n.onMs).toDouble() / spec.spanMs * s * 0.9).toInt().coerceAtMost(size / 12)))
                val y = (s * 0.92 - (n.key.coerceIn(21, 108) - 21) / 87.0 * s * 0.84).toInt()
                fillRect(px, size, x0, y, x1, y + max(1, dash / 2), spec.ink, 0.10 + 0.10 * n.velocity.coerceIn(0, 127) / 127.0)
            }
        }
        // The disc, set high or low by the register, and the twelve rays of the pitch classes around it.
        val cx = s * (0.5 + 0.06 * (spec.turn - 0.5))
        val cy = s * (0.62 - 0.22 * spec.register)
        val radius = s * 0.20
        for (i in 0 until 12) {
            val pc = Math.floorMod(i * 7, 12)
            val weight = spec.histogram[pc]
            if (weight <= 0.02) continue
            val angle = StrictMath.PI * 2 * i / 12 + spec.turn * StrictMath.PI / 6 - StrictMath.PI / 2
            val inner = radius * 1.12
            val outer = inner + s * 0.18 * weight
            line(px, size, cx + StrictMath.cos(angle) * inner, cy + StrictMath.sin(angle) * inner, cx + StrictMath.cos(angle) * outer, cy + StrictMath.sin(angle) * outer, s * 0.012 + s * 0.012 * weight, spec.accent, 0.85)
        }
        disc(px, size, cx, cy, radius, spec.accent, 0.92)
        disc(px, size, cx, cy, radius * 0.18, spec.ground, 1.0)
        // The density of notes over time, a band along the foot.
        val bins = spec.density.size
        val foot = s * 0.97
        for (x in 0 until size) {
            val t = x / s * (bins - 1)
            val i = t.toInt().coerceIn(0, bins - 2)
            val f = t - i
            val level = spec.density[i] * (1 - f) + spec.density[i + 1] * f
            val top = foot - s * 0.16 * level
            for (y in top.toInt().coerceAtLeast(0) until foot.toInt().coerceAtMost(size)) {
                val cover = if (y == top.toInt()) 1 - (top - top.toInt()) else 1.0
                blend(px, y * size + x, spec.ink, 0.55 * cover)
            }
        }
        return px
    }

    private fun fillRect(px: IntArray, size: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, alpha: Double) {
        for (y in max(0, y0) until min(size, y1)) for (x in max(0, x0) until min(size, x1)) blend(px, y * size + x, color, alpha)
    }

    private fun disc(px: IntArray, size: Int, cx: Double, cy: Double, r: Double, color: Int, alpha: Double) {
        val y0 = max(0, (cy - r - 1).toInt())
        val y1 = min(size - 1, (cy + r + 1).toInt())
        val x0 = max(0, (cx - r - 1).toInt())
        val x1 = min(size - 1, (cx + r + 1).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val d = StrictMath.sqrt((x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy))
            val cover = (r - d + 0.5).coerceIn(0.0, 1.0)
            if (cover > 0) blend(px, y * size + x, color, alpha * cover)
        }
    }

    private fun line(px: IntArray, size: Int, ax: Double, ay: Double, bx: Double, by: Double, width: Double, color: Int, alpha: Double) {
        val half = width / 2
        val y0 = max(0, (min(ay, by) - half - 1).toInt())
        val y1 = min(size - 1, (max(ay, by) + half + 1).toInt())
        val x0 = max(0, (min(ax, bx) - half - 1).toInt())
        val x1 = min(size - 1, (max(ax, bx) + half + 1).toInt())
        val dx = bx - ax
        val dy = by - ay
        val length2 = dx * dx + dy * dy
        for (y in y0..y1) for (x in x0..x1) {
            val px0 = x + 0.5 - ax
            val py0 = y + 0.5 - ay
            val t = if (length2 == 0.0) 0.0 else ((px0 * dx + py0 * dy) / length2).coerceIn(0.0, 1.0)
            val ex = px0 - t * dx
            val ey = py0 - t * dy
            val d = StrictMath.sqrt(ex * ex + ey * ey)
            val cover = (half - d + 0.5).coerceIn(0.0, 1.0)
            if (cover > 0) blend(px, y * size + x, color, alpha * cover)
        }
    }

    private fun blend(px: IntArray, at: Int, color: Int, alpha: Double) {
        px[at] = mix(px[at], color, alpha)
    }

    /** [a] towards [b] by [t] (0–1), opaque. */
    private fun mix(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * k + 0.5).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
