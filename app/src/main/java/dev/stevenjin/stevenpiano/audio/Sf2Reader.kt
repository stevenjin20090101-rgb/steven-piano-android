// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/** A SoundFont file that isn't one, or that this reader can't play ([message] says why, in words). */
class Sf2Exception(message: String) : Exception(message)

/** One sample's header (`shdr`): its frames in the sample data, its loop, rate and pitch, and its stereo partner. */
class Sf2Sample(
    val name: String,
    val start: Int,
    val end: Int,
    val loopStart: Int,
    val loopEnd: Int,
    val rate: Int,
    val originalPitch: Int,
    val pitchCorrection: Int,
    val link: Int,
    val type: Int,
) {
    val rom: Boolean get() = type and ROM != 0

    /** The left channel of a stereo pair (its [link] is the right). */
    val left: Boolean get() = type and 0x7FFF == LEFT

    val right: Boolean get() = type and 0x7FFF == RIGHT

    companion object {
        const val MONO = 1
        const val RIGHT = 2
        const val LEFT = 4
        const val ROM = 0x8000
    }
}

/**
 * A zone resolved for playing (the instrument's generators with the preset's added, SF2 2.04 § 9.4):
 * one sample over [keyLo]–[keyHi] and [velLo]–[velHi]. Frames are absolute indexes into
 * [SoundFont.data], after the address offsets: the sample is [start] until [end], its loop
 * [loopStart] until [loopEnd] (the frame after the loop). [loopMode] 0 plays once, 1 loops for as
 * long as the voice sounds, 3 loops until the key is let go and then plays on to the end. The pitch:
 * [rootKey] sounds at [rate] with [tuneCents] (coarse and fine tune and the sample's correction), and
 * each key away from it moves [scaleTuning] cents. [attenuationCb] is `initialAttenuation`; the
 * volume envelope's times are timecents and [sustainCb] its sustain level in centibels below full;
 * [keyToHold] and [keyToDecay] scale hold and decay by key. [pan] is read, never used. [partner] is
 * the other channel of a stereo pair whose zones match this one's: its frames play inside this
 * region's voice, mixed to mono; a partner itself is never a region of its own ([SoundFont.regionsFor]).
 */
class Region(
    val keyLo: Int,
    val keyHi: Int,
    val velLo: Int,
    val velHi: Int,
    val sample: Int,
    val start: Int,
    val end: Int,
    val loopStart: Int,
    val loopEnd: Int,
    val loopMode: Int,
    val rate: Int,
    val rootKey: Int,
    val tuneCents: Int,
    val scaleTuning: Int,
    val attenuationCb: Int,
    val delayTc: Int,
    val attackTc: Int,
    val holdTc: Int,
    val decayTc: Int,
    val sustainCb: Int,
    val releaseTc: Int,
    val keyToHold: Int,
    val keyToDecay: Int,
    val pan: Int,
) {
    var partner: Region? = null
        internal set

    /** Whether this region is the second channel of a pair, played inside its first's voice. */
    var paired: Boolean = false
        internal set

    val loops: Boolean get() = (loopMode == LOOP_CONTINUOUS || loopMode == LOOP_UNTIL_RELEASE) && loopEnd - loopStart >= MIN_LOOP

    fun matches(key: Int, velocity: Int): Boolean = key in keyLo..keyHi && velocity in velLo..velHi

    /** Whether [other] can play inside this region's voice: the same ranges, frames, pitch and envelope. */
    internal fun pairsWith(other: Region): Boolean =
        keyLo == other.keyLo && keyHi == other.keyHi && velLo == other.velLo && velHi == other.velHi &&
            end - start == other.end - other.start && loopStart - start == other.loopStart - other.start &&
            loopEnd - start == other.loopEnd - other.start && loopMode == other.loopMode && rate == other.rate &&
            rootKey == other.rootKey && tuneCents == other.tuneCents && scaleTuning == other.scaleTuning &&
            attenuationCb == other.attenuationCb && delayTc == other.delayTc && attackTc == other.attackTc &&
            holdTc == other.holdTc && decayTc == other.decayTc && sustainCb == other.sustainCb &&
            releaseTc == other.releaseTc && keyToHold == other.keyToHold && keyToDecay == other.keyToDecay

    companion object {
        const val LOOP_NONE = 0
        const val LOOP_CONTINUOUS = 1
        const val LOOP_UNTIL_RELEASE = 3

        /** A loop shorter than this many frames is no loop (SF2 asks for at least 32). */
        const val MIN_LOOP = 8
    }
}

/**
 * A SoundFont read for playing: its [name] (INAM), the chosen preset's [regions] (bank 0 preset 0,
 * else the first) and the sample frames, 16-bit, as [data] (a view of the file's `smpl` chunk: a
 * memory-mapped file on the tablet, a heap buffer in tests). [regionsFor] gives the regions a key can
 * sound, a stereo pair's second channel left out.
 */
class SoundFont(val name: String, val presetName: String, val samples: List<Sf2Sample>, val regions: List<Region>, val data: ShortBuffer) {
    private val byKey: Array<Array<Region>> = Array(128) { key -> regions.filter { !it.paired && key in it.keyLo..it.keyHi }.toTypedArray() }

    /** The regions key [key] (0–127) can sound, in the file's order; the velocity chooses among them ([Region.matches]). */
    fun regionsFor(key: Int): Array<Region> = byKey[key.coerceIn(0, 127)]

    /** Sample frames in the file. */
    val frames: Int get() = data.limit()
}

/**
 * Reads SoundFont 2 (RIFF `sfbk`): the sample data (`smpl`; 24-bit `sm24` is ignored) and, from
 * `pdta`, the presets (`phdr`, `pbag`, `pmod`, `pgen`), the instruments (`inst`, `ibag`, `imod`,
 * `igen`) and the samples (`shdr`). Only what a piano preset needs is kept (BUILD_SPEC › v1.8 —
 * M25): key and velocity ranges, the sample and its address offsets, loop points and mode, root key,
 * coarse and fine tune, scale tuning, initial attenuation and the volume envelope (delay, attack,
 * hold, decay, sustain, release, and key to hold and decay). Pan is read and ignored (the sampler is
 * mono); filters, LFOs, the modulation envelope, reverb and chorus sends are ignored; modulators are
 * read past (the sampler applies SF2's default velocity-to-attenuation). ROM samples are skipped. The
 * generators follow SF2 2.04 § 9.4: an instrument zone's own values over its global zone's over the
 * defaults, then a preset zone's (its own over its global zone's) added, the ranges intersected. A
 * stereo pair (a left sample linked to a right one) whose two zones match in everything but pan plays
 * as one voice ([Region.partner]). Anything malformed throws [Sf2Exception]; indexes are all checked,
 * so a damaged file never reads outside itself. Pure: no Android.
 */
object Sf2Reader {
    /** Memory-maps [file] (read-only; nothing is copied to the heap) and reads it. */
    fun read(file: File): SoundFont {
        val buffer = RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        }
        return read(buffer)
    }

    /** Reads the font in [buffer] (from its position to its limit); the sample data stays a view of it. */
    fun read(buffer: ByteBuffer): SoundFont {
        val buf = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (buf.limit() < 12 || fourCc(buf, 0) != "RIFF" || fourCc(buf, 8) != "sfbk") throw Sf2Exception("This isn't a SoundFont file.")
        val riffEnd = (8L + (buf.getInt(4).toLong() and 0xFFFFFFFFL)).coerceAtMost(buf.limit().toLong()).toInt()
        val chunks = HashMap<String, Chunk>()
        var name = ""
        forEachChunk(buf, 12, riffEnd) { id, offset, size ->
            if (id == "LIST" && size >= 4) {
                val kind = fourCc(buf, offset)
                forEachChunk(buf, offset + 4, offset + size) { sub, subOffset, subSize ->
                    if (kind == "INFO" && sub == "INAM") name = text(buf, subOffset, subSize)
                    if ((kind == "sdta" || kind == "pdta") && sub !in chunks) chunks[sub] = Chunk(subOffset, subSize)
                }
            }
        }
        val smpl = chunks["smpl"] ?: throw Sf2Exception("The SoundFont has no samples.")
        val view = buf.duplicate()
        // Through Buffer: ByteBuffer's own position(int) and limit(int) are newer than API 26.
        (view as Buffer).position(smpl.offset)
        (view as Buffer).limit(smpl.offset + smpl.size / 2 * 2)
        val data = view.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val phdr = records(chunks, "phdr", 38)
        val pbag = records(chunks, "pbag", 4)
        val pgen = records(chunks, "pgen", 4)
        val inst = records(chunks, "inst", 22)
        val ibag = records(chunks, "ibag", 4)
        val igen = records(chunks, "igen", 4)
        val shdr = records(chunks, "shdr", 46)
        // The modulators (pmod, imod) are read past: the sampler applies SF2's default velocity curve alone.

        val samples = (0 until shdr.count - 1).map { i -> sample(buf, shdr.at(i), data.limit()) }
        val presetIndex = choosePreset(buf, phdr)
        val presetName = text(buf, phdr.at(presetIndex), 20)
        val zones = Zones(buf, pbag, pgen, ibag, igen, inst)
        val regions = ArrayList<Region>()
        for (presetZone in zones.presetZones(phdr, presetIndex)) {
            val instrument = presetZone.terminal ?: continue
            for (instrumentZone in zones.instrumentZones(instrument)) {
                val sampleId = instrumentZone.terminal ?: continue
                if (sampleId >= samples.size) throw Sf2Exception("A zone names a sample the SoundFont doesn't have.")
                val region = resolve(presetZone, instrumentZone, samples[sampleId], sampleId, data.limit()) ?: continue
                regions += region
            }
        }
        if (regions.isEmpty()) throw Sf2Exception("The SoundFont's preset has nothing to play.")
        pairStereo(regions, samples)
        return SoundFont(name.ifBlank { presetName }, presetName, samples, regions, data)
    }

    private class Chunk(val offset: Int, val size: Int)

    /** A table of fixed-size records: [count] of them, the last the terminal one. */
    private class Records(val offset: Int, val size: Int, val count: Int) {
        fun at(i: Int): Int = offset + i * size
    }

    private fun records(chunks: Map<String, Chunk>, id: String, size: Int): Records {
        val chunk = chunks[id] ?: throw Sf2Exception("The SoundFont has no $id table.")
        if (chunk.size % size != 0 || chunk.size / size < 1) throw Sf2Exception("The SoundFont's $id table is damaged.")
        return Records(chunk.offset, size, chunk.size / size)
    }

    private inline fun forEachChunk(buf: ByteBuffer, from: Int, to: Int, each: (String, Int, Int) -> Unit) {
        var at = from
        while (at + 8 <= to) {
            val id = fourCc(buf, at)
            val size = buf.getInt(at + 4).toLong() and 0xFFFFFFFFL
            if (size > to - at - 8) throw Sf2Exception("The SoundFont's $id chunk runs past its end.")
            each(id, at + 8, size.toInt())
            at += 8 + size.toInt() + (size.toInt() and 1)
        }
    }

    private fun fourCc(buf: ByteBuffer, at: Int): String = String(CharArray(4) { (buf.get(at + it).toInt() and 0xFF).toChar() })

    /** A zero-padded ASCII field, printable characters only. */
    private fun text(buf: ByteBuffer, at: Int, size: Int): String {
        val out = StringBuilder()
        for (i in 0 until size) {
            val c = buf.get(at + i).toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) out.append(c.toChar())
        }
        return out.toString().trim()
    }

    private fun word(buf: ByteBuffer, at: Int): Int = buf.getShort(at).toInt() and 0xFFFF

    private fun sample(buf: ByteBuffer, at: Int, frames: Int): Sf2Sample {
        val start = buf.getInt(at + 20).toLong() and 0xFFFFFFFFL
        val end = buf.getInt(at + 24).toLong() and 0xFFFFFFFFL
        val loopStart = buf.getInt(at + 28).toLong() and 0xFFFFFFFFL
        val loopEnd = buf.getInt(at + 32).toLong() and 0xFFFFFFFFL
        return Sf2Sample(
            name = text(buf, at, 20),
            start = start.coerceAtMost(frames.toLong()).toInt(),
            end = end.coerceAtMost(frames.toLong()).toInt(),
            loopStart = loopStart.coerceAtMost(frames.toLong()).toInt(),
            loopEnd = loopEnd.coerceAtMost(frames.toLong()).toInt(),
            rate = buf.getInt(at + 36),
            originalPitch = buf.get(at + 40).toInt() and 0xFF,
            pitchCorrection = buf.get(at + 41).toInt(),
            link = word(buf, at + 42),
            type = word(buf, at + 44),
        )
    }

    /** Bank 0, preset 0 when the font has it (the General MIDI piano), else its first preset. */
    private fun choosePreset(buf: ByteBuffer, phdr: Records): Int {
        if (phdr.count < 2) throw Sf2Exception("The SoundFont has no presets.")
        for (i in 0 until phdr.count - 1) {
            if (word(buf, phdr.at(i) + 20) == 0 && word(buf, phdr.at(i) + 22) == 0) return i
        }
        return 0
    }

    /** A zone's generators as read ([values], null where unset) and its terminal generator ([terminal]: an instrument or a sample). */
    private class Zone(val values: Array<Int?>, val terminal: Int?)

    /** Walks the bag and generator tables, every index checked against the table it points into. */
    private class Zones(
        private val buf: ByteBuffer,
        private val pbag: Records,
        private val pgen: Records,
        private val ibag: Records,
        private val igen: Records,
        private val inst: Records,
    ) {
        /** The preset's zones, each with its global zone's values under its own ("global" itself left out). */
        fun presetZones(phdr: Records, preset: Int): List<Zone> {
            val first = word(buf, phdr.at(preset) + 24)
            val last = word(buf, phdr.at(preset + 1) + 24)
            return zonesOf(pbag, pgen, first, last, TERMINAL_INSTRUMENT)
        }

        fun instrumentZones(instrument: Int): List<Zone> {
            if (instrument + 1 >= inst.count) throw Sf2Exception("A preset names an instrument the SoundFont doesn't have.")
            val first = word(buf, inst.at(instrument) + 20)
            val last = word(buf, inst.at(instrument + 1) + 20)
            return zonesOf(ibag, igen, first, last, TERMINAL_SAMPLE)
        }

        private fun zonesOf(bags: Records, gens: Records, first: Int, last: Int, terminal: Int): List<Zone> {
            if (first > last || last >= bags.count) throw Sf2Exception("The SoundFont's zones are damaged.")
            val zones = ArrayList<Zone>()
            var global: Array<Int?>? = null
            for (bag in first until last) {
                val genFirst = word(buf, bags.at(bag))
                val genLast = word(buf, bags.at(bag + 1))
                if (genFirst > genLast || genLast >= gens.count) throw Sf2Exception("The SoundFont's generators are damaged.")
                val values = arrayOfNulls<Int>(GENERATORS)
                var end: Int? = null
                for (g in genFirst until genLast) {
                    val op = word(buf, gens.at(g))
                    val amount = buf.getShort(gens.at(g) + 2).toInt()
                    if (op == terminal) {
                        end = word(buf, gens.at(g) + 2)
                        break   // nothing after the terminal generator counts
                    }
                    if (op < GENERATORS) values[op] = if (op == KEY_RANGE || op == VEL_RANGE) word(buf, gens.at(g) + 2) else amount
                }
                if (end == null) {
                    // A zone without its terminal generator: the global zone when it is the first, else nothing.
                    if (bag == first) global = values
                    continue
                }
                val merged = arrayOfNulls<Int>(GENERATORS)
                for (i in 0 until GENERATORS) merged[i] = values[i] ?: global?.get(i)
                zones += Zone(merged, end)
            }
            return zones
        }
    }

    /**
     * One instrument zone under one preset zone, as the sampler plays it; null when it can't sound
     * (a ROM sample, no frames, or ranges that don't meet).
     */
    private fun resolve(preset: Zone, zone: Zone, sample: Sf2Sample, sampleId: Int, frames: Int): Region? {
        if (sample.rom || sample.rate <= 0) return null
        val p = preset.values
        val z = zone.values
        fun inst(op: Int): Int = z[op] ?: DEFAULTS[op]
        fun sum(op: Int): Int = inst(op) + (p[op] ?: 0)
        val key = intersect(z[KEY_RANGE], p[KEY_RANGE]) ?: return null
        val vel = intersect(z[VEL_RANGE], p[VEL_RANGE]) ?: return null
        // Address offsets are the instrument's alone (not allowed at the preset level).
        val start = (sample.start.toLong() + inst(START_OFFSET) + COARSE * inst(START_COARSE)).clampFrames(frames)
        val end = (sample.end.toLong() + inst(END_OFFSET) + COARSE * inst(END_COARSE)).clampFrames(frames)
        if (end - start < 2) return null
        val loopStart = (sample.loopStart.toLong() + inst(LOOP_START_OFFSET) + COARSE * inst(LOOP_START_COARSE)).clampFrames(frames).coerceIn(start, end)
        val loopEnd = (sample.loopEnd.toLong() + inst(LOOP_END_OFFSET) + COARSE * inst(LOOP_END_COARSE)).clampFrames(frames).coerceIn(start, end)
        val root = inst(OVERRIDING_ROOT_KEY).takeIf { it in 0..127 } ?: sample.originalPitch.takeIf { it in 0..127 } ?: 60
        return Region(
            keyLo = key.first,
            keyHi = key.last,
            velLo = vel.first,
            velHi = vel.last,
            sample = sampleId,
            start = start,
            end = end,
            loopStart = loopStart,
            loopEnd = loopEnd,
            loopMode = inst(SAMPLE_MODES) and 3,
            rate = sample.rate,
            rootKey = root,
            tuneCents = sum(COARSE_TUNE) * 100 + sum(FINE_TUNE) + sample.pitchCorrection,
            scaleTuning = sum(SCALE_TUNING),
            attenuationCb = sum(INITIAL_ATTENUATION).coerceIn(0, 1440),
            delayTc = sum(DELAY_VOL_ENV),
            attackTc = sum(ATTACK_VOL_ENV),
            holdTc = sum(HOLD_VOL_ENV),
            decayTc = sum(DECAY_VOL_ENV),
            sustainCb = sum(SUSTAIN_VOL_ENV).coerceIn(0, 1440),
            releaseTc = sum(RELEASE_VOL_ENV),
            keyToHold = sum(KEY_TO_VOL_HOLD),
            keyToDecay = sum(KEY_TO_VOL_DECAY),
            pan = sum(PAN).coerceIn(-500, 500),
        )
    }

    private fun Long.clampFrames(frames: Int): Int = coerceIn(0L, frames.toLong()).toInt()

    /** Two ranges (lo in the low byte, hi in the high) intersected; either absent is 0–127; null when they don't meet. */
    private fun intersect(a: Int?, b: Int?): IntRange? {
        val lo = maxOf(a?.let { it and 0xFF } ?: 0, b?.let { it and 0xFF } ?: 0)
        val hi = minOf(a?.let { (it shr 8) and 0xFF } ?: 127, b?.let { (it shr 8) and 0xFF } ?: 127, 127)
        return if (lo <= hi) lo..hi else null
    }

    /**
     * A left sample's region takes its linked right sample's region as its [Region.partner] when the two
     * match in everything but pan; the right one then plays only inside it. A sample without its match
     * plays on its own.
     */
    private fun pairStereo(regions: List<Region>, samples: List<Sf2Sample>) {
        for (left in regions) {
            val sample = samples[left.sample]
            if (!sample.left || left.paired || left.partner != null) continue
            val right = regions.firstOrNull { it !== left && it.sample == sample.link && !it.paired && it.partner == null && samples[it.sample].right && left.pairsWith(it) }
                ?: continue
            left.partner = right
            right.paired = true
        }
    }

    const val GENERATORS = 61

    // The generators the sampler reads (SF2 2.04 § 8.1.2), and the terminal ones.
    private const val START_OFFSET = 0
    private const val END_OFFSET = 1
    private const val LOOP_START_OFFSET = 2
    private const val LOOP_END_OFFSET = 3
    private const val START_COARSE = 4
    private const val END_COARSE = 12
    private const val PAN = 17
    private const val DELAY_VOL_ENV = 33
    private const val ATTACK_VOL_ENV = 34
    private const val HOLD_VOL_ENV = 35
    private const val DECAY_VOL_ENV = 36
    private const val SUSTAIN_VOL_ENV = 37
    private const val RELEASE_VOL_ENV = 38
    private const val KEY_TO_VOL_HOLD = 39
    private const val KEY_TO_VOL_DECAY = 40
    private const val TERMINAL_INSTRUMENT = 41
    private const val KEY_RANGE = 43
    private const val VEL_RANGE = 44
    private const val LOOP_START_COARSE = 45
    private const val INITIAL_ATTENUATION = 48
    private const val LOOP_END_COARSE = 50
    private const val COARSE_TUNE = 51
    private const val FINE_TUNE = 52
    private const val TERMINAL_SAMPLE = 53
    private const val SAMPLE_MODES = 54
    private const val SCALE_TUNING = 56
    private const val OVERRIDING_ROOT_KEY = 58
    private const val COARSE = 32_768L

    /** Envelope times of 1 ms and less (SF2's "instant"). */
    const val INSTANT_TC = -12_000

    /** SF2's defaults for the generators read here (every other one is 0). */
    private val DEFAULTS = IntArray(GENERATORS).apply {
        this[DELAY_VOL_ENV] = INSTANT_TC
        this[ATTACK_VOL_ENV] = INSTANT_TC
        this[HOLD_VOL_ENV] = INSTANT_TC
        this[DECAY_VOL_ENV] = INSTANT_TC
        this[RELEASE_VOL_ENV] = INSTANT_TC
        this[SCALE_TUNING] = 100
        this[OVERRIDING_ROOT_KEY] = -1
    }
}
