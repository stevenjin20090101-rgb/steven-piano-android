// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/** What a crafted file may cost the parser (the v1.2 audit, F2 and F7): bounded, or refused in plain English. */
class SmfLimitsTest {
    private val mb = 1024L * 1024

    /**
     * One track that strikes middle C, then strikes it again every tick with running status: three
     * bytes per strike, each of which the parser turns into two events (the implicit Note Off, then
     * the Note On). The worst ratio of events to bytes a file can reach.
     */
    private fun restrikes(bytes: Int): ByteArray {
        val body = ByteArrayOutputStream(bytes + 16)
        body.write(byteArrayOf(0x00, 0x90.toByte(), 0x3C, 0x40))
        while (body.size() + 3 <= bytes) body.write(byteArrayOf(0x01, 0x3C, 0x40))
        val track = body.toByteArray()
        val out = ByteArrayOutputStream(track.size + 22)
        out.write("MThd".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0.toByte()))   // format 0, one track, 480 per quarter
        out.write("MTrk".toByteArray())
        out.write(byteArrayOf((track.size ushr 24).toByte(), (track.size ushr 16).toByte(), (track.size ushr 8).toByte(), track.size.toByte()))
        out.write(track)
        return out.toByteArray()
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) {
            System.gc()
            Thread.sleep(20)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun `a 1 MB file of running-status re-strikes parses under the event cap in little memory`() {
        val bytes = restrikes(mb.toInt())
        val before = usedHeap()
        val piece = SmfParser.parse(bytes)
        val grown = usedHeap() - before
        assertTrue("${piece.events.size} events", piece.events.size in 600_000..SmfParser.MAX_EVENTS)
        assertTrue("the parsed piece holds ${grown / mb} MB", grown < 64 * mb)
        assertEquals((piece.events.size + 1) / 2, piece.noteCount)   // the first strike, then an off and an on each
        assertEquals(0x90, piece.events.command(piece.events.size - 1))
    }

    @Test
    fun `an 8 MB file of re-strikes is refused as too many events, without running out of memory`() {
        val bytes = restrikes(8 * mb.toInt())
        val before = usedHeap()
        try {
            SmfParser.parse(bytes)
            fail("5.6 million events should be refused")
        } catch (e: SmfException) {
            assertEquals("This file has too many events.", e.message)
        }
        assertTrue(usedHeap() - before < 64 * mb)
    }

    @Test
    fun `a piece longer than a day is refused, a day or less plays`() {
        // One tick per quarter at 16.78 s a quarter: tick 5,149 is 23.99 h in, 5,150 is 24.0004 h.
        fun at(tick: Long) = SmfBuilder(format = 0, division = 1).track {
            tempo(0, 0xFFFFFF)
            noteOn(0, 60)
            noteOff(tick, 60)
        }.build()
        assertEquals(5_149L * 0xFFFFFF, SmfParser.parse(at(5_149)).durationMicros)
        for (tick in listOf(5_150L, 1L shl 27)) {   // the audit's case: one 2^27-tick delta, 71 years
            try {
                SmfParser.parse(at(tick))
                fail("tick $tick should be refused")
            } catch (e: SmfException) {
                assertEquals("This file lasts longer than a day, which Steven Piano can't play.", e.message)
            }
        }
    }

    @Test
    fun `tracks past those the header lists are skipped, with one warning`() {
        val builder = SmfBuilder(declaredTracks = 2)
            .track { noteOn(0, 60); noteOff(10, 60) }
            .track { noteOn(0, 62); noteOff(10, 62) }
            .track { noteOn(0, 64); noteOff(10, 64) }
        val piece = SmfParser.parse(builder.build())
        assertEquals(listOf(60, 62), (0 until piece.noteCount).map(piece.notes::note).sorted())
        assertEquals(listOf("The file holds more tracks than the 2 it lists; the extra ones are skipped."), piece.warnings)
    }

    @Test
    fun `at most 1024 tracks are read, however many the header lists`() {
        val builder = SmfBuilder(declaredTracks = 2_000)
        repeat(1_024) { builder.track { end(0) } }
        repeat(76) { builder.track { noteOn(0, 60); noteOff(10, 60) } }   // only in tracks past the cap
        val piece = SmfParser.parse(builder.build())
        assertEquals(0, piece.noteCount)
        assertEquals(listOf("Only the first 1024 tracks are read; the rest are skipped."), piece.warnings)
    }

    @Test
    fun `twenty warnings are kept, and the rest are counted`() {
        val builder = SmfBuilder()
        repeat(30) { builder.track { raw(0, 0xF1) } }   // a system message: the track is damaged
        val warnings = SmfParser.parse(builder.build()).warnings
        assertEquals(21, warnings.size)
        assertEquals("Track 20 is damaged, so the rest of it is skipped.", warnings[19])
        assertEquals("…and 10 more.", warnings[20])
    }

    @Test
    fun `text metas are read to 256 bytes and 16 of a kind, a UTF-8 name never cut mid-character`() {
        val huge = "Grand Sonata " + "x".repeat(1_000_000)
        val euros = "€".repeat(100)   // 300 bytes: 256 falls inside the 86th
        val piece = SmfParser.parse(
            SmfBuilder(format = 0).track {
                name(0, huge)
                name(0, euros)
                repeat(20) { name(0, "Name $it") }
                repeat(20) { meta(0, 0x01, "Text $it".toByteArray()) }
                meta(0, 0x02, ("© " + "y".repeat(5_000)).toByteArray())
                noteOn(0, 60)
                noteOff(10, 60)
            }.build(),
        )
        assertEquals(16, piece.sequenceNames.size)
        assertEquals(huge.take(256), piece.sequenceNames[0])
        assertEquals("€".repeat(85), piece.sequenceNames[1])
        assertEquals("Name 13", piece.sequenceNames.last())
        assertEquals(16, piece.texts.size)
        assertTrue(piece.copyright!!.toByteArray().size <= 256)
    }

    @Test
    fun `the event list reads by index without objects, and as a list of events`() {
        val events = listOf(TimedEvent(0, 0x90, 60, 80), TimedEvent(500, 0x80, 60, 0), TimedEvent(500, 0xB3, 64, 127))
        val list = EventList.of(events)
        assertEquals(events, list)
        assertEquals(500L, list.atMicros(2))
        assertEquals(0xB3, list.status(2))
        assertEquals(0xB0, list.command(2))
        assertEquals(3, list.channel(2))
        assertEquals(64, list.data1(2))
        assertEquals(127, list.data2(2))
        assertEquals(500L, list.lastMicros)
        assertEquals(1, list.firstAtOrAfter(1))
        assertEquals(3, list.firstAtOrAfter(501))
        assertEquals(0, list.firstAtOrAfter(-5))
        assertEquals(0L, EventList.Empty.lastMicros)
        assertFalse(EventList.Empty.iterator().hasNext())
    }
}
