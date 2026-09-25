// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

import kotlin.math.min

/**
 * Standard MIDI File reader for formats 0 and 1, the Kotlin counterpart of `parseMidi()` in
 * firmware/gui/piano-control.html. Tracks are merged stably by (tick, rank) with the rank
 * tempo < controller < note-off < note-on, so a released key is always let go before it is
 * struck again at the same instant. Damaged files are read as far as they go, with warnings.
 *
 * A file is untrusted input, so what it can cost is bounded: at most [MAX_EVENTS] events (about
 * two million, 12 bytes each once merged), the tracks the header lists up to [MAX_TRACKS], text
 * metas read to [MAX_TEXT_BYTES] and [MAX_TEXTS] of each kind, [MAX_SIGNATURES] time signatures
 * and as many key signatures, [MAX_WARNINGS] warnings, and at most [MAX_DURATION_MICROS] (a day)
 * of music. Past a cap the file is refused in plain English ([SmfException]) or the excess is
 * skipped with a warning.
 */
object SmfParser {
    internal const val RANK_TEMPO = 0
    internal const val RANK_CONTROL = 1
    internal const val RANK_NOTE_OFF = 2
    internal const val RANK_NOTE_ON = 3

    private const val NOT_MIDI = "This isn't a MIDI file, or it is damaged."
    private const val FORMAT_2 =
        "This MIDI file stores separate patterns (format 2), which can't be played as one piece."
    private const val SMPTE = "This MIDI file counts time in video frames (SMPTE), which Steven Piano can't play."
    private const val NO_TRACKS = "This MIDI file has no tracks."
    private const val TOO_MANY_EVENTS = "This file has too many events."
    private const val TOO_LONG = "This file lasts longer than a day, which Steven Piano can't play."
    private const val TOO_MANY_SIGNATURES = "Too many signature changes; some were ignored."

    fun parse(bytes: ByteArray): MidiPiece {
        if (bytes.size < 14 || !bytes.hasTag(0, "MThd")) throw SmfException(NOT_MIDI)
        val headerLength = bytes.u32(4)
        val format = bytes.u16(8)
        val declaredTracks = bytes.u16(10)
        val division = bytes.u16(12)
        when {
            headerLength < 6 || format > 2 || division == 0 -> throw SmfException(NOT_MIDI)
            format == 2 -> throw SmfException(FORMAT_2)
            division and 0x8000 != 0 -> throw SmfException(SMPTE)
        }

        val raw = RawEvents()
        val header = Track0Meta()
        val trackNames = ArrayList<String>()
        val warnings = Warnings()
        val open = OpenNotes()   // one table for every track, cleared between them
        val readable = min(declaredTracks, MAX_TRACKS)
        var tracks = 0
        var skipped = false
        var pos = 8L + headerLength   // extra header bytes are skipped
        while (pos + 8 <= bytes.size) {
            val start = pos + 8
            val end = start + bytes.u32(pos.toInt() + 4)
            val cutShort = end > bytes.size
            if (bytes.hasTag(pos.toInt(), "MTrk")) {
                if (tracks < readable) {
                    if (cutShort) warnings += "Track ${tracks + 1} is cut short, so it plays as far as it goes."
                    val reader = TrackReader(bytes, start.toInt(), min(end, bytes.size.toLong()).toInt(), tracks, raw, header, warnings, open)
                    reader.read(warnOnTruncation = !cutShort)
                    trackNames += reader.name.orEmpty()
                    tracks++
                } else {
                    skipped = true   // past the tracks the header lists (or the cap): skipped whole
                }
            } else if (cutShort) {
                warnings += "The file ends with damaged data, which is skipped."
            }
            pos = end   // unknown chunks are skipped whole
        }
        if (tracks == 0) throw SmfException(NO_TRACKS)
        if (skipped) {
            warnings += if (declaredTracks > MAX_TRACKS) {
                "Only the first $MAX_TRACKS tracks are read; the rest are skipped."
            } else {
                "The file holds more tracks than the $declaredTracks it lists; the extra ones are skipped."
            }
        } else if (tracks < declaredTracks) {
            warnings += "The file lists $declaredTracks tracks but holds $tracks."
        }
        if (raw.signaturesDropped) warnings += TOO_MANY_SIGNATURES

        val tempo = TempoMap.Builder(division)
        val events = raw.merge(tempo)
        val tempoMap = tempo.build()
        val durationMicros = events.lastMicros
        if (durationMicros > MAX_DURATION_MICROS) throw SmfException(TOO_LONG)
        val timeSignatures = SignatureLists.times(raw.times, tempoMap)
        return MidiPiece(
            format = format,
            ppq = division,
            sequenceNames = header.names,
            texts = header.texts,
            copyright = header.copyright,
            durationMicros = durationMicros,
            events = events,
            notes = pairNotes(events, raw.mergedTracks, durationMicros),
            warnings = warnings.list(),
            tempoMap = tempoMap,
            timeSignatures = timeSignatures,
            keySignatures = SignatureLists.keys(raw.keys, tempoMap),
            barStartsMicros = Bars.starts(tempoMap, timeSignatures, durationMicros),
            trackNames = trackNames,
        )
    }

    /**
     * Pairs every Note On with its Note Off per channel and key; a re-strike ends the previous note.
     * Each note keeps the track its Note On came from ([eventTracks], by event): the hands are read
     * from it (`score.Hands`). Timing and pitch are exactly as before.
     */
    private fun pairNotes(events: EventList, eventTracks: ShortArray, durationMicros: Long): NoteList {
        var count = 0
        for (i in 0 until events.size) if (events.command(i) == 0x90) count++
        val starts = LongArray(count)
        val ends = LongArray(count)
        val keys = ByteArray(count)
        val velocities = ByteArray(count)
        val channels = ByteArray(count)
        val tracks = ShortArray(count)
        val open = IntArray(16 * 128) { -1 }
        var n = 0
        for (i in 0 until events.size) {
            val at = events.atMicros(i)
            val channel = events.channel(i)
            val key = events.data1(i)
            val source = channel * 128 + key
            when (events.command(i)) {
                0x90 -> {
                    if (open[source] >= 0) ends[open[source]] = at
                    starts[n] = at
                    ends[n] = -1L
                    keys[n] = key.toByte()
                    velocities[n] = events.data2(i).toByte()
                    channels[n] = channel.toByte()
                    tracks[n] = eventTracks[i]
                    open[source] = n++
                }
                0x80 -> if (open[source] >= 0) {
                    ends[open[source]] = at
                    open[source] = -1
                }
            }
        }
        for (i in 0 until n) if (ends[i] < 0) ends[i] = durationMicros
        return NoteList(starts, ends, keys, velocities, channels, tracks)
    }

    private class Track0Meta {
        val names = mutableListOf<String>()
        val texts = mutableListOf<String>()
        var copyright: String? = null
    }

    /** The first [MAX_WARNINGS] warnings, then a count of the rest. */
    private class Warnings {
        private val kept = ArrayList<String>()
        private var more = 0

        operator fun plusAssign(warning: String) {
            if (kept.size < MAX_WARNINGS) kept += warning else more++
        }

        fun list(): List<String> = if (more == 0) kept else kept + "…and $more more."
    }

    /**
     * The raw index and tick of the note sounding on each channel * 128 + key, within one track.
     * One table serves every track, cleared as each begins: a file of a thousand tiny tracks
     * allocates it once.
     */
    private class OpenNotes {
        val index = IntArray(16 * 128)
        val tick = LongArray(16 * 128)

        fun clear() = index.fill(-1)
    }

    private class Truncated : Exception()
    private class Damaged : Exception()

    /** Reads one MTrk chunk into [raw]. */
    private class TrackReader(
        private val b: ByteArray,
        private var p: Int,
        private val end: Int,
        private val track: Int,
        private val raw: RawEvents,
        private val header: Track0Meta,
        private val warnings: Warnings,
        open: OpenNotes,
    ) {
        private val openIndex = open.index
        private val openTick = open.tick

        /** The track's name: its first track-name meta (FF 03), or null when it has none. */
        var name: String? = null
            private set

        init {
            open.clear()
        }

        fun read(warnOnTruncation: Boolean) {
            var tick = 0L
            var running = 0
            try {
                while (p < end) {
                    tick += varLen()
                    if (tick > MAX_TICK) throw Damaged()
                    var status = u8()
                    if (status < 0x80) {
                        if (running == 0) throw Damaged()
                        status = running
                        p--   // running status: that byte was data
                    }
                    when {
                        status == 0xFF -> {
                            running = 0   // a meta event cancels running status
                            if (!meta(tick)) return
                        }
                        status == 0xF0 || status == 0xF7 -> {
                            running = 0   // SysEx is skipped and cancels running status too
                            skip(varLen())
                        }
                        status > 0xF0 -> throw Damaged()   // system messages have no place in a file
                        else -> {
                            running = status
                            channelMessage(status, tick)
                        }
                    }
                }
            } catch (e: Truncated) {
                if (warnOnTruncation) warnings += "Track ${track + 1} ends in the middle of an event."
            } catch (e: Damaged) {
                warnings += "Track ${track + 1} is damaged, so the rest of it is skipped."
            }
        }

        /** Returns false at End of Track. */
        private fun meta(tick: Long): Boolean {
            val type = u8()
            val length = varLen()
            val at = p
            skip(length)
            when (type) {
                0x2F -> return false
                0x51 -> if (length >= 3) {
                    val tempo = (b.u16(at) shl 8) or (b[at + 2].toInt() and 0xFF)
                    if (tempo > 0) raw.add(tick, RANK_TEMPO, tempo, track)
                }
                // Signatures stay beside the packed words (whose 2-bit rank is full): the score reads them.
                0x58 -> if (length >= 2) raw.time(tick, b[at].toInt() and 0xFF, b[at + 1].toInt() and 0xFF)
                0x59 -> if (length >= 2) raw.key(tick, b[at].toInt(), b[at + 1].toInt() and 0xFF)
                0x03 -> {
                    if (name == null) name = text(at, length)
                    if (track == 0 && header.names.size < MAX_TEXTS) text(at, length)?.let(header.names::add)
                }
                0x01 -> if (track == 0 && header.texts.size < MAX_TEXTS) text(at, length)?.let(header.texts::add)
                0x02 -> if (header.copyright == null) header.copyright = text(at, length)
            }
            return true
        }

        /** A text meta's first [MAX_TEXT_BYTES], decoded; null when that is empty. */
        private fun text(at: Int, length: Int): String? = MidiText.decode(b, at, length, MAX_TEXT_BYTES).ifEmpty { null }

        private fun channelMessage(status: Int, tick: Long) {
            val command = status and 0xF0
            val channel = status and 0x0F
            val data1 = data()
            val data2 = if (command == 0xC0 || command == 0xD0) 0 else data()
            when (command) {
                0x90 -> if (data2 > 0) noteOn(tick, channel, data1, data2) else noteOff(tick, channel, data1)
                0x80 -> noteOff(tick, channel, data1)
                0xB0 -> raw.add(tick, RANK_CONTROL, MidiBatch.pack(status, data1, data2), track)
                // Program change, pitch bend and aftertouch mean nothing to the piano.
            }
        }

        private fun noteOn(tick: Long, channel: Int, key: Int, velocity: Int) {
            val source = channel * 128 + key
            if (openIndex[source] >= 0) {
                if (openTick[source] == tick) return   // the same onset twice
                raw.add(tick, RANK_NOTE_OFF, MidiBatch.pack(0x80 or channel, key, 0), track)
            }
            openIndex[source] = raw.add(tick, RANK_NOTE_ON, MidiBatch.pack(0x90 or channel, key, velocity), track)
            openTick[source] = tick
        }

        private fun noteOff(tick: Long, channel: Int, key: Int) {
            val source = channel * 128 + key
            val open = openIndex[source]
            openIndex[source] = -1
            if (open >= 0 && openTick[source] == tick) {
                raw.cancel(open)   // a zero-length note: sorting off-before-on would leave it held
                return
            }
            raw.add(tick, RANK_NOTE_OFF, MidiBatch.pack(0x80 or channel, key, 0), track)
        }

        private fun u8(): Int {
            if (p >= end) throw Truncated()
            return b[p++].toInt() and 0xFF
        }

        private fun data(): Int {
            val v = u8()
            if (v >= 0x80) throw Damaged()
            return v
        }

        private fun skip(length: Int) {
            if (length > end - p) throw Truncated()
            p += length
        }

        private fun varLen(): Int {
            var value = 0
            repeat(4) {
                val byte = u8()
                value = (value shl 7) or (byte and 0x7F)
                if (byte < 0x80) return value
            }
            throw Damaged()
        }
    }

    /**
     * Events of every track in arrival order, as ticks plus packed words and the track each came
     * from (0-based, in file order); signatures in lists of their own.
     */
    private class RawEvents {
        private var ticks = LongArray(1024)
        private var words = IntArray(1024)
        private var tracks = ShortArray(1024)
        var size = 0
            private set

        /** After [merge]: the track of each merged event, by the merged index. */
        var mergedTracks = ShortArray(0)
            private set

        /** Time signatures (numerator, power of two) and key signatures (sharps, signed; mode) as read, [MAX_SIGNATURES] of each at most. */
        val times = ArrayList<SignatureLists.Raw>()
        val keys = ArrayList<SignatureLists.Raw>()

        /** A signature past [MAX_SIGNATURES] of its kind was read and left out. */
        var signaturesDropped = false
            private set

        fun time(tick: Long, numerator: Int, power: Int) {
            if (times.size < MAX_SIGNATURES) times += SignatureLists.Raw(tick, numerator, power) else signaturesDropped = true
        }

        fun key(tick: Long, sharps: Int, mode: Int) {
            if (keys.size < MAX_SIGNATURES) keys += SignatureLists.Raw(tick, sharps, mode) else signaturesDropped = true
        }

        /** [payload] is a packed message, or the tempo for [RANK_TEMPO], from [track]. Returns the event's index. */
        fun add(tick: Long, rank: Int, payload: Int, track: Int): Int {
            if (size == MAX_EVENTS) throw SmfException(TOO_MANY_EVENTS)
            if (size == ticks.size) {
                ticks = ticks.copyOf(size * 2)
                words = words.copyOf(size * 2)
                tracks = tracks.copyOf(size * 2)
            }
            ticks[size] = tick
            words[size] = (rank shl 24) or payload
            tracks[size] = track.toShort()   // at most MAX_TRACKS (1,024) tracks are read
            return size++
        }

        fun cancel(index: Int) {
            words[index] = CANCELED
        }

        /**
         * Stable merge by (tick, rank), then ticks to microseconds through the tempo map as it
         * builds up in [tempo]: a tempo change sorts first at its tick and times what follows.
         * The sort keys' array is reused for the events' times (event k is written at or before
         * key k, which has been read by then), so the merge needs one long array, not two. Each merged
         * event's track goes to [mergedTracks].
         */
        fun merge(tempo: TempoMap.Builder): EventList {
            val order = LongArray(size) { i -> (ticks[i] shl 25) or ((rank(words[i]).toLong()) shl 23) or i.toLong() }
            order.sort()
            var count = 0
            for (i in 0 until size) if (words[i] != CANCELED && rank(words[i]) != RANK_TEMPO) count++
            val packed = IntArray(count)
            val merged = ShortArray(count)
            var n = 0
            for (k in 0 until size) {
                val i = (order[k] and INDEX_MASK).toInt()
                val word = words[i]
                if (word == CANCELED) continue
                if (rank(word) == RANK_TEMPO) {
                    tempo.change(ticks[i], word and 0xFFFFFF)
                } else {
                    order[n] = tempo.micros(ticks[i])
                    packed[n] = word and 0xFFFFFF
                    merged[n] = tracks[i]
                    n++
                }
            }
            mergedTracks = merged
            return EventList(order, packed, n)
        }

        private fun rank(word: Int): Int = (word ushr 24) and 0x3
    }

    private const val MAX_TICK = 1L shl 37
    private const val INDEX_MASK = (1L shl 23) - 1
    private const val CANCELED = -1

    /** Events a file may hold: 2,097,152. An 8 MB file of re-struck notes could otherwise make about 5.6 million. */
    const val MAX_EVENTS = 1 shl 21

    /** Tracks read, at most, whatever the header lists. */
    const val MAX_TRACKS = 1024

    /** Bytes of a text meta that are read; a name is a line, not a megabyte. */
    const val MAX_TEXT_BYTES = 256

    /** Track names (FF 03) and texts (FF 01) kept from Track 0. */
    const val MAX_TEXTS = 16

    /**
     * Time signatures (FF 58) kept from a file, and key signatures (FF 59) likewise: a score's metre or
     * key changes a few times, and every change the score and the chord names look up costs them time
     * (the v1.3 delta audit, M1: 1.25 million key signatures took 7.7 s to name the chords).
     */
    const val MAX_SIGNATURES = 4_096

    /** Warnings kept; the rest are counted in one more. */
    const val MAX_WARNINGS = 20

    /** A day: the longest piece the player times (its clock arithmetic has room for about three years). */
    const val MAX_DURATION_MICROS = 24L * 60 * 60 * 1_000_000
}

private fun ByteArray.u16(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)

private fun ByteArray.u32(i: Int): Long = (u16(i).toLong() shl 16) or u16(i + 2).toLong()

private fun ByteArray.hasTag(i: Int, tag: String): Boolean =
    i + tag.length <= size && tag.indices.all { this[i + it].toInt() == tag[it].code }
