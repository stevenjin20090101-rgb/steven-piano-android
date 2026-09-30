// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SoundFonts written by the tests themselves (v1.8 — M25): sine-wave samples at known pitches, with
 * loops of whole periods, laid out as SF2 2.04 lays a font out (RIFF `sfbk`, INFO, `sdta` `smpl`, and
 * the nine `pdta` tables with their terminal records), so the reader and the sampler are tested on
 * bytes no other tool made.
 */
object Sf2Fixture {
    /** A sample: its frames, rate, the key it was recorded at, and its loop ([loopStart] until [loopEnd], relative). */
    class Sample(
        val name: String,
        val frames: ShortArray,
        val rate: Int,
        val root: Int,
        val loopStart: Int = 0,
        val loopEnd: Int = 0,
        val correction: Int = 0,
        val type: Int = Sf2Sample.MONO,
        val link: Int = 0,
    )

    /** A zone's generators in order, as (operator, amount); ranges as [range]. The terminal generator is added. */
    class Zone(val generators: List<Pair<Int, Int>>, val terminal: Int?)

    class Preset(val name: String, val bank: Int, val number: Int, val zones: List<Zone>)

    class Instrument(val name: String, val zones: List<Zone>)

    fun range(lo: Int, hi: Int): Int = lo or (hi shl 8)

    /** A sine of [frequency] Hz at [amplitude] of full scale, [frames] long. */
    fun sine(frequency: Double, rate: Int, frames: Int, amplitude: Double = 0.5): ShortArray =
        ShortArray(frames) { (amplitude * 32_767 * sin(2 * PI * frequency * it / rate)).roundToInt().toShort() }

    fun frequencyOf(key: Int): Double = 440.0 * 2.0.pow((key - 69) / 12.0)

    /**
     * A sine sample for [key] at [rate], one second long, looping over its last whole periods (so a held
     * note sounds on), its loop ending on a period boundary so the loop is seamless.
     */
    fun toneSample(name: String, key: Int, rate: Int = 22_050, seconds: Double = 1.0, amplitude: Double = 0.5, type: Int = Sf2Sample.MONO, link: Int = 0): Sample {
        val frequency = frequencyOf(key)
        val frames = (rate * seconds).toInt()
        // Samples of this frequency are periodic over exactly `rate` frames in `frequency` periods only at whole
        // rates; a loop of an integer number of periods, as close to whole frames as can be, is near-seamless.
        val periods = (0.25 * frequency).roundToInt().coerceAtLeast(1)
        val loopLength = (periods * rate / frequency).roundToInt()
        return Sample(name, sine(frequency, rate, frames, amplitude), rate, key, frames - loopLength - 1, frames - 1, type = type, link = link)
    }

    /** The bytes of a SoundFont holding [presets], [instruments] and [samples]. */
    fun build(name: String, presets: List<Preset>, instruments: List<Instrument>, samples: List<Sample>): ByteArray {
        // smpl: each sample followed by 46 zero frames, as SF2 asks.
        val smpl = ByteArrayOutputStream()
        val starts = IntArray(samples.size)
        var at = 0
        for ((i, s) in samples.withIndex()) {
            starts[i] = at
            val bytes = ByteBuffer.allocate((s.frames.size + 46) * 2).order(ByteOrder.LITTLE_ENDIAN)
            s.frames.forEach { bytes.putShort(it) }
            smpl.write(bytes.array())
            at += s.frames.size + 46
        }

        val phdr = Table(38)
        val pbag = Table(4)
        val pgen = Table(4)
        var bag = 0
        var gen = 0
        for (p in presets) {
            phdr.add { put(fixed(p.name, 20)); putShort(p.number.toShort()); putShort(p.bank.toShort()); putShort(bag.toShort()); putInt(0); putInt(0); putInt(0) }
            for (z in p.zones) {
                pbag.add { putShort(gen.toShort()); putShort(0) }
                bag++
                for ((op, amount) in z.generators) pgen.add { putShort(op.toShort()); putShort(amount.toShort()) }.also { gen++ }
                z.terminal?.let { pgen.add { putShort(41); putShort(it.toShort()) }.also { gen++ } }
            }
        }
        phdr.add { put(fixed("EOP", 20)); putShort(0); putShort(0); putShort(bag.toShort()); putInt(0); putInt(0); putInt(0) }
        pbag.add { putShort(gen.toShort()); putShort(0) }
        pgen.add { putShort(0); putShort(0) }

        val inst = Table(22)
        val ibag = Table(4)
        val igen = Table(4)
        bag = 0
        gen = 0
        for (instrument in instruments) {
            inst.add { put(fixed(instrument.name, 20)); putShort(bag.toShort()) }
            for (z in instrument.zones) {
                ibag.add { putShort(gen.toShort()); putShort(0) }
                bag++
                for ((op, amount) in z.generators) igen.add { putShort(op.toShort()); putShort(amount.toShort()) }.also { gen++ }
                z.terminal?.let { igen.add { putShort(53); putShort(it.toShort()) }.also { gen++ } }
            }
        }
        inst.add { put(fixed("EOI", 20)); putShort(bag.toShort()) }
        ibag.add { putShort(gen.toShort()); putShort(0) }
        igen.add { putShort(0); putShort(0) }

        val shdr = Table(46)
        for ((i, s) in samples.withIndex()) {
            val start = starts[i]
            shdr.add {
                put(fixed(s.name, 20))
                putInt(start); putInt(start + s.frames.size); putInt(start + s.loopStart); putInt(start + s.loopEnd)
                putInt(s.rate); put(s.root.toByte()); put(s.correction.toByte()); putShort(s.link.toShort()); putShort(s.type.toShort())
            }
        }
        shdr.add { put(fixed("EOS", 20)); putInt(0); putInt(0); putInt(0); putInt(0); putInt(0); put(0); put(0); putShort(0); putShort(0) }
        val mods = Table(10).apply { add { putShort(0); putShort(0); putShort(0); putShort(0); putShort(0) } }
        val imods = Table(10).apply { add { putShort(0); putShort(0); putShort(0); putShort(0); putShort(0) } }

        val info = list("INFO", chunk("ifil", byteArrayOf(2, 0, 4, 0)) + chunk("INAM", zeroTerminated(name)))
        val sdta = list("sdta", chunk("smpl", smpl.toByteArray()))
        val pdta = list(
            "pdta",
            chunk("phdr", phdr.bytes()) + chunk("pbag", pbag.bytes()) + chunk("pmod", mods.bytes()) + chunk("pgen", pgen.bytes()) +
                chunk("inst", inst.bytes()) + chunk("ibag", ibag.bytes()) + chunk("imod", imods.bytes()) + chunk("igen", igen.bytes()) +
                chunk("shdr", shdr.bytes()),
        )
        val body = "sfbk".toByteArray(Charsets.US_ASCII) + info + sdta + pdta
        return "RIFF".toByteArray(Charsets.US_ASCII) + le32(body.size) + body
    }

    /**
     * A small piano: C3, C4 and C5 sine samples at 22,050 Hz, splitting the keyboard (0–53 on C3,
     * 54–65 on C4, 66–127 on C5; C4 in two velocity layers, the loud one 6 dB louder), looping; a global
     * zone sets a 10 ms attack, a 50 ms hold, a 200 ms decay to 6 dB down and a 300 ms release.
     */
    fun piano(): ByteArray {
        val samples = listOf(
            toneSample("C3", 48),
            toneSample("C4soft", 60, amplitude = 0.25),
            toneSample("C4loud", 60, amplitude = 0.5),
            toneSample("C5", 72),
        )
        val global = Zone(
            listOf(ATTACK to tc(0.010), HOLD to tc(0.050), DECAY to tc(0.200), SUSTAIN to 60, RELEASE to tc(0.300)),
            terminal = null,
        )
        val zones = listOf(
            global,
            Zone(listOf(KEY_RANGE to range(0, 53), SAMPLE_MODES to 1), 0),
            Zone(listOf(KEY_RANGE to range(54, 65), VEL_RANGE to range(0, 80), SAMPLE_MODES to 1), 1),
            Zone(listOf(KEY_RANGE to range(54, 65), VEL_RANGE to range(81, 127), SAMPLE_MODES to 1), 2),
            Zone(listOf(KEY_RANGE to range(66, 127), SAMPLE_MODES to 1), 3),
        )
        return build(
            "Fixture piano",
            listOf(Preset("Fixture piano", 0, 0, listOf(Zone(emptyList(), 0)))),
            listOf(Instrument("Fixture piano", zones)),
            samples,
        )
    }

    /** Seconds as absolute timecents: 1200 · log2(s). */
    fun tc(seconds: Double): Int = (1200 * kotlin.math.ln(seconds) / kotlin.math.ln(2.0)).roundToInt()

    fun font(bytes: ByteArray): SoundFont = Sf2Reader.read(ByteBuffer.wrap(bytes))

    // Generator operators (SF2 2.04 § 8.1.2).
    const val START_OFFSET = 0
    const val END_OFFSET = 1
    const val LOOP_START_OFFSET = 2
    const val LOOP_END_OFFSET = 3
    const val PAN = 17
    const val DELAY = 33
    const val ATTACK = 34
    const val HOLD = 35
    const val DECAY = 36
    const val SUSTAIN = 37
    const val RELEASE = 38
    const val KEY_TO_HOLD = 39
    const val KEY_RANGE = 43
    const val VEL_RANGE = 44
    const val ATTENUATION = 48
    const val COARSE_TUNE = 51
    const val FINE_TUNE = 52
    const val SAMPLE_MODES = 54
    const val SCALE_TUNING = 56
    const val ROOT_KEY = 58

    private class Table(private val size: Int) {
        private val out = ByteArrayOutputStream()

        fun add(fill: ByteBuffer.() -> Unit) {
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            b.fill()
            out.write(b.array())
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun fixed(text: String, size: Int): ByteArray = text.toByteArray(Charsets.US_ASCII).copyOf(size)

    private fun zeroTerminated(text: String): ByteArray {
        val raw = text.toByteArray(Charsets.US_ASCII) + 0
        return if (raw.size % 2 == 0) raw else raw + 0
    }

    fun chunk(id: String, data: ByteArray): ByteArray {
        val pad = if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return id.toByteArray(Charsets.US_ASCII) + le32(data.size) + data + pad
    }

    fun list(kind: String, body: ByteArray): ByteArray = chunk("LIST", kind.toByteArray(Charsets.US_ASCII) + body)

    fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
