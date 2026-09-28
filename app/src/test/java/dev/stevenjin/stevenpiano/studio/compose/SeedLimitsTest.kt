// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.SmfParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory

/**
 * Audit delta 2 (finding S1): a seed is read to [AmtTokenizer.MAX_NOTES] notes. A crafted file within the
 * importer's caps (6 MB, 2,000,000 events: a million notes in its first 12.4 s) used to be read whole
 * into boxed lists, 151 MB for the compose sheet's key and tempo and 377 MB for a prompt, on a
 * 192 MB heap (the sheet ran it with nothing to catch an OutOfMemoryError). Ordinary seeds are untouched.
 */
class SeedLimitsTest {
    /**
     * The crafted file: one track at 120 bpm, 480 ticks a quarter; tick after tick every key 24–107 let
     * go (a Note On of velocity 0) and struck again, in running status, three bytes an event.
     */
    private fun crafted(events: Int): ByteArray {
        val track = ByteArrayOutputStream()
        track.write(byteArrayOf(0, 0x90.toByte(), 24, 64))
        var written = 1
        var first = true
        while (written < events) {
            for (k in 24..107) {
                if (written >= events) break
                if (!first || k != 24) {
                    track.write(if (k == 24) 1 else 0)
                    track.write(k)
                    track.write(0)
                    written++
                }
                if (written >= events) break
                track.write(0)
                track.write(k)
                track.write(64)
                written++
            }
            first = false
        }
        track.write(byteArrayOf(0, 0xFF.toByte(), 0x2F, 0))
        val body = track.toByteArray()
        return ByteArrayOutputStream().apply {
            write("MThd".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 1, 0xE0.toByte()))
            write("MTrk".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
            write(body)
        }.toByteArray()
    }

    private val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    /** Bytes this thread allocates in [block] (-1 where the JVM can't tell). */
    private fun <T> allocatedBy(block: () -> T): Pair<T, Long> {
        val bean = threads?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
        val before = bean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: 0L
        val result = block()
        val after = bean?.getThreadAllocatedBytes(Thread.currentThread().id)
        return result to (after?.minus(before) ?: -1L)
    }

    @Test
    fun `a crafted file's million notes in its first seconds are read to 4,096, and its prompt stays small`() {
        val bytes = crafted(2_000_000)
        assertTrue("within the importer's 8 MB cap: ${bytes.size}", bytes.size <= 8 * 1024 * 1024)
        val piece: MidiPiece = SmfParser.parse(bytes)
        assertEquals("the parser keeps every event", 1_999_999, piece.events.size)

        val (notes, notesBytes) = allocatedBy { AmtTokenizer.notes(piece, 15.0) }
        assertEquals(AmtTokenizer.MAX_NOTES, notes.size)
        // The first notes as the file has them: every key in turn, each lasting until it is struck again a tick later.
        assertEquals((24..107).toList(), notes.take(84).map { it.key })
        assertTrue(notes.take(84).all { it.on == 0.0 && it.off > 0.0 && it.off < 0.002 })

        val (facts, factsBytes) = allocatedBy { PromptBuilder.facts(piece) }
        val (prompt, buildBytes) = allocatedBy { PromptBuilder.build(SeedPiece("crafted", null, piece), ComposeRequest()) }
        assertEquals(120, facts.bpm)
        assertTrue("prompt events ${prompt.events.size}", prompt.events.size <= AmtTokenizer.MAX_NOTES + 15)
        assertTrue("budget as for any seed", prompt.budget == PromptBuilder.budget(2))
        if (notesBytes >= 0) {
            // Before the limit: 151 MB, 151 MB and 377 MB (measured on the JVM; the tablet's heap is 192 MB).
            assertTrue("notes allocated ${notesBytes / 1_000_000} MB", notesBytes < 4_000_000)
            assertTrue("facts allocated ${factsBytes / 1_000_000} MB", factsBytes < 8_000_000)
            assertTrue("the prompt allocated ${buildBytes / 1_000_000} MB", buildBytes < 16_000_000)
        }
    }

    @Test
    fun `the limit leaves an ordinary seed alone`() {
        val bach = ComposerFixtures.bach
        assertTrue(AmtTokenizer.notes(bach).size < AmtTokenizer.MAX_NOTES)
        assertEquals(AmtTokenizer.notes(bach), AmtTokenizer.notes(bach, limit = Int.MAX_VALUE))
        assertArrayEquals(ComposerFixtures.inputTokens, PromptBuilder.build(SeedPiece("Prelude", "Bach", bach), ComposeRequest()).tokens)
        // A limit below a piece's notes keeps its first ones, in order.
        val first = AmtTokenizer.notes(bach, limit = 10)
        assertEquals(AmtTokenizer.notes(bach).take(10), first)
        assertTrue(AmtTokenizer.notes(bach, limit = 0).isEmpty())
    }
}
