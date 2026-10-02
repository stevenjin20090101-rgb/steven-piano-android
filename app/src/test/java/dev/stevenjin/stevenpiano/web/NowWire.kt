// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The panel's reader of the views' three formats (assets/web/wire.js), in Kotlin for the tests (v1.13 — M32): the
 * same checks in the same order, so what the tablet writes is proven readable and what is cut short or garbled is
 * proven refused.
 */
internal object NowWire {
    const val NOTES_MAGIC = 0x544E5053
    const val INDEX_MAGIC = 0x49535053
    const val PAGE_MAGIC = 0x50535053
    const val VERSION = 1

    private val OP_WORDS = intArrayOf(0, 5, 4, 5, 9, 7, 3, 1)
    private const val MAX_NOTES = 200_000
    private const val MAX_CHORDS = 20_000
    private const val MAX_SYSTEMS = 100_000
    private const val MAX_PAGE_SYSTEMS = 64
    const val BAR_POINTS = 9
    const val BAR_WORDS = 2 + 2 * BAR_POINTS
    private const val SYSTEM_WORDS = 10
    const val HEAD_WORDS = 5

    class WireException(message: String) : Exception(message)

    class Notes(
        val rev: Int, val n: Int, val m: Int, val durationMs: Long,
        val start: LongArray, val end: LongArray, val key: IntArray, val hand: IntArray?, val finger: IntArray?,
        val chordStart: LongArray, val chordNames: List<String>, val chordsCut: Boolean,
    )

    class Index(
        val engraved: Boolean, val twoPages: Boolean, val rev: Int, val layoutId: Int, val pages: Int, val systemsPerPage: Int,
        val systemCount: Int, val pageCount: Int, val barsPerSystem: Int, val geometry: FloatArray, val systemStart: LongArray,
    ) {
        val pageWidth: Float get() = geometry[0]
        val pageHeight: Float get() = geometry[1]
        val space: Float get() = geometry[5]
    }

    class System(val index: Int, val firstBar: Int, val barCount: Int, val final: Boolean, val box: FloatArray, val barFrom: Int)

    class Page(
        val layoutId: Int, val page: Int, val truncated: Boolean, val systems: List<System>,
        val bars: IntArray, val heads: IntArray, val ops: IntArray, val strings: List<String>,
    ) {
        val headCount: Int get() = heads.size / HEAD_WORDS

        fun barStart(b: Int): Long = bars[b * BAR_WORDS].toLong() and 0xFFFF_FFFFL

        fun pointMs(b: Int, k: Int): Long = bars[b * BAR_WORDS + 2 + 2 * k].toLong() and 0xFFFF_FFFFL

        fun pointX(b: Int, k: Int): Float = Float.fromBits(bars[b * BAR_WORDS + 3 + 2 * k])

        /** The ops in order: each its op code and its words. */
        fun opList(): List<IntArray> {
            val out = ArrayList<IntArray>()
            var k = 0
            while (k < ops.size) {
                val words = OP_WORDS[ops[k] and 0xFF]
                out += ops.copyOfRange(k, k + words)
                k += words
            }
            return out
        }
    }

    private fun header(bytes: ByteArray, magic: Int, size: Int): ByteBuffer {
        if (bytes.size < size) throw WireException("Cut short.")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != magic) throw WireException("Not this format.")
        if ((b.getShort(4).toInt() and 0xFFFF) != VERSION) throw WireException("Reload the page.")
        return b
    }

    private fun need(bytes: ByteArray, offset: Long, size: Long) {
        if (offset < 0 || size < 0 || offset + size > bytes.size) throw WireException("Cut short.")
    }

    private fun align(n: Long): Long = (n + 3) and 3L.inv()

    private fun u32(b: ByteBuffer, at: Int): Long = b.getInt(at).toLong() and 0xFFFF_FFFFL

    private fun strings(bytes: ByteArray, offset: Long, ends: LongArray, total: Long): List<String> {
        need(bytes, offset, total)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = ArrayList<String>()
        var from = 0L
        for (to in ends) {
            if (to < from || to > total) throw WireException("A string runs past its table.")
            try {
                out += decoder.decode(ByteBuffer.wrap(bytes, (offset + from).toInt(), (to - from).toInt())).toString()
            } catch (e: CharacterCodingException) {
                throw WireException("A string is not UTF-8.")
            }
            from = to
        }
        return out
    }

    fun notes(bytes: ByteArray): Notes {
        val b = header(bytes, NOTES_MAGIC, 32)
        val flags = b.getShort(6).toInt() and 0xFFFF
        val rev = b.getInt(8)
        val n = u32(b, 12)
        val m = u32(b, 16)
        val durationMs = u32(b, 20)
        val nameBytes = u32(b, 24)
        if (n > MAX_NOTES || m > MAX_CHORDS) throw WireException("Too many notes.")
        var at = 32L
        need(bytes, at, n * 8)
        val start = LongArray(n.toInt()) { u32(b, (at + it * 4).toInt()) }
        val end = LongArray(n.toInt()) { u32(b, (at + n * 4 + it * 4).toInt()) }
        at += n * 8
        need(bytes, at, align(n))
        val key = IntArray(n.toInt()) { bytes[(at + it).toInt()].toInt() and 0xFF }
        at += align(n)
        var hand: IntArray? = null
        if (flags and 1 != 0) {
            need(bytes, at, align(n))
            val from = at
            hand = IntArray(n.toInt()) { bytes[(from + it).toInt()].toInt() and 0xFF }
            at += align(n)
        }
        var finger: IntArray? = null
        if (flags and 2 != 0) {
            need(bytes, at, align(n))
            val from = at
            finger = IntArray(n.toInt()) { bytes[(from + it).toInt()].toInt() and 0xFF }
            at += align(n)
        }
        need(bytes, at, m * 8)
        val chordStart = LongArray(m.toInt()) { u32(b, (at + it * 4).toInt()) }
        val ends = LongArray(m.toInt()) { u32(b, (at + m * 4 + it * 4).toInt()) }
        at += m * 8
        val names = strings(bytes, at, ends, nameBytes)
        if (at + nameBytes != bytes.size.toLong()) throw WireException("Bytes left over.")
        for (i in 0 until n.toInt()) {
            if (end[i] < start[i]) throw WireException("A note ends before it starts.")
            if (i > 0 && start[i] < start[i - 1]) throw WireException("The notes are out of order.")
        }
        return Notes(rev, n.toInt(), m.toInt(), durationMs, start, end, key, hand, finger, chordStart, names, flags and 4 != 0)
    }

    fun index(bytes: ByteArray): Index {
        val b = header(bytes, INDEX_MAGIC, 96)
        val flags = b.getShort(6).toInt() and 0xFFFF
        fun u(k: Int) = b.getInt(k * 4)
        val geometry = FloatArray(13) { b.getFloat((10 + it) * 4) }
        val index = Index(
            engraved = flags and 1 != 0, twoPages = flags and 2 != 0, rev = u(2), layoutId = u(3), pages = u(4), systemsPerPage = u(5),
            systemCount = u(6), pageCount = u(7), barsPerSystem = u(8), geometry = geometry, systemStart = LongArray(0),
        )
        if (index.pages !in 1..2 || index.systemsPerPage < 1 || index.systemCount !in 0..MAX_SYSTEMS) throw WireException("Not a layout.")
        if (index.pageCount != (index.systemCount + index.systemsPerPage - 1) / index.systemsPerPage) throw WireException("Not a layout.")
        for (k in intArrayOf(0, 1, 5, 9, 10, 11, 12)) if (!(geometry[k] > 0f && geometry[k] < 10_000f)) throw WireException("Not a layout.")
        need(bytes, 96, index.systemCount * 4L)
        if (96 + index.systemCount * 4 != bytes.size) throw WireException("Bytes left over.")
        val starts = LongArray(index.systemCount) { u32(b, 96 + it * 4) }
        return Index(index.engraved, index.twoPages, index.rev, index.layoutId, index.pages, index.systemsPerPage, index.systemCount, index.pageCount, index.barsPerSystem, geometry, starts)
    }

    fun page(bytes: ByteArray): Page {
        val b = header(bytes, PAGE_MAGIC, 40)
        val flags = b.getShort(6).toInt() and 0xFFFF
        val layoutId = b.getInt(8)
        val page = b.getInt(12)
        val systemCount = u32(b, 16)
        val barCount = u32(b, 20)
        val headCount = u32(b, 24)
        val opWords = u32(b, 28)
        val stringCount = u32(b, 32)
        val stringBytes = u32(b, 36)
        if (systemCount > MAX_PAGE_SYSTEMS || barCount > systemCount * 64 || headCount > 1_000_000 || opWords > 4_000_000 || stringCount > 1_000_000) {
            throw WireException("Too large a page.")
        }
        var at = 40L
        need(bytes, at, systemCount * SYSTEM_WORDS * 4)
        val systems = ArrayList<System>()
        var bars = 0
        for (s in 0 until systemCount.toInt()) {
            val o = (at + s * SYSTEM_WORDS * 4).toInt()
            val system = System(b.getInt(o), b.getInt(o + 4), b.getInt(o + 8), b.getInt(o + 12) == 1, FloatArray(6) { b.getFloat(o + 16 + it * 4) }, bars)
            if (system.barCount !in 1..64) throw WireException("Not a system.")
            bars += system.barCount
            systems += system
        }
        at += systemCount * SYSTEM_WORDS * 4
        if (bars.toLong() != barCount) throw WireException("The bars do not add up.")
        need(bytes, at, barCount * BAR_WORDS * 4)
        val barWords = IntArray((barCount * BAR_WORDS).toInt()) { b.getInt((at + it * 4).toInt()) }
        at += barCount * BAR_WORDS * 4
        need(bytes, at, headCount * HEAD_WORDS * 4)
        val heads = IntArray((headCount * HEAD_WORDS).toInt()) { b.getInt((at + it * 4).toInt()) }
        at += headCount * HEAD_WORDS * 4
        need(bytes, at, opWords * 4)
        val ops = IntArray(opWords.toInt()) { b.getInt((at + it * 4).toInt()) }
        at += opWords * 4
        need(bytes, at, stringCount * 4)
        val ends = LongArray(stringCount.toInt()) { u32(b, (at + it * 4).toInt()) }
        at += stringCount * 4
        val strings = strings(bytes, at, ends, stringBytes)
        if (at + stringBytes != bytes.size.toLong()) throw WireException("Bytes left over.")
        val boundary = BooleanArray(ops.size + 1)
        var k = 0
        while (k < ops.size) {
            boundary[k] = true
            val op = ops[k] and 0xFF
            val words = OP_WORDS.getOrElse(op) { 0 }
            if (words == 0 || k + words > ops.size) throw WireException("An op is cut short.")
            if (op == 3 && (ops[k + 1].toLong() and 0xFFFF_FFFFL) >= stringCount) throw WireException("A string is missing.")
            k += words
        }
        boundary[ops.size] = true
        for (h in 0 until headCount.toInt()) {
            val o = h * HEAD_WORDS
            val from = heads[o + 2]
            val to = heads[o + 3]
            if (from < 0 || to < from || to > ops.size || !boundary[from] || !boundary[to] || heads[o + 4] !in 0 until systemCount) {
                throw WireException("A head points outside the page.")
            }
            if (h > 0 && heads[o] < heads[o - HEAD_WORDS]) throw WireException("The heads are out of order.")
        }
        return Page(layoutId, page, flags and 1 != 0, systems, barWords, heads, ops, strings)
    }
}
