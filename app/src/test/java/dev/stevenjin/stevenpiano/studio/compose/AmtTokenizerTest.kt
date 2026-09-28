// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The composer's tokenizer (v1.7 — M24): `bach_bwv846.mid`'s first 15 s give the spike's 214-token seed
 * exactly, as the `anticipation` package made it (`midi_to_events` → `clip(0, 1500)` → `pad` after
 * AUTOREGRESS), and the tokens decode back to the file's notes; the vocabulary is the fixture's.
 */
class AmtTokenizerTest {
    private val fifteen = 15 * Amt.TICKS_PER_SECOND

    private fun seedOf(notes: List<SeedNote>): IntArray =
        AmtTokenizer.prompt(AmtTokenizer.pad(AmtTokenizer.clip(AmtTokenizer.events(notes), 0, fifteen), fifteen))

    @Test
    fun `the vocabulary is the fixture's`() {
        val v = ComposerFixtures.vocabulary
        assertEquals(v.getInt("TIME_OFFSET"), Amt.TIME_OFFSET)
        assertEquals(v.getInt("DUR_OFFSET"), Amt.DUR_OFFSET)
        assertEquals(v.getInt("NOTE_OFFSET"), Amt.NOTE_OFFSET)
        assertEquals(v.getInt("REST"), Amt.REST)
        assertEquals(v.getInt("CONTROL_OFFSET"), Amt.CONTROL_OFFSET)
        assertEquals(v.getInt("SPECIAL_OFFSET"), Amt.SPECIAL_OFFSET)
        assertEquals(v.getInt("SEPARATOR"), Amt.SEPARATOR)
        assertEquals(v.getInt("AUTOREGRESS"), Amt.AUTOREGRESS)
        assertEquals(v.getInt("ANTICIPATE"), Amt.ANTICIPATE)
        assertEquals(v.getInt("VOCAB_SIZE"), Amt.VOCAB_SIZE)
        assertEquals(v.getInt("MAX_TIME"), Amt.MAX_TIME)
        assertEquals(v.getInt("MAX_DUR"), Amt.MAX_DUR)
        assertEquals(v.getInt("MAX_PITCH"), Amt.MAX_PITCH)
        assertEquals(v.getInt("MAX_INSTR"), Amt.MAX_INSTR)
        assertEquals(v.getInt("MAX_NOTE"), Amt.MAX_NOTE)
        assertEquals(v.getInt("TIME_RESOLUTION"), Amt.TICKS_PER_SECOND)
        assertEquals(v.getInt("CONTEXT_SIZE"), Amt.CONTEXT)
        assertEquals(11_000, Amt.PIANO_FIRST)
        assertEquals(11_127, Amt.PIANO_LAST)
    }

    @Test
    fun `the Bach file's first 15 s are the fixture's 214 tokens, token for token`() {
        val notes = AmtTokenizer.notes(ComposerFixtures.bach, seconds = 15.0)
        val tokens = seedOf(notes)
        assertEquals(214, tokens.size)
        assertArrayEquals(ComposerFixtures.inputTokens, tokens)
        val events = AmtTokenizer.decode(tokens)
        assertEquals(ComposerFixtures.seed.getInt("seed_current_time_ticks"), AmtTokenizer.maxTime(events))
        // The same from the whole file's notes: the window only saves reading the rest.
        assertArrayEquals(ComposerFixtures.inputTokens, seedOf(AmtTokenizer.notes(ComposerFixtures.bach)))
    }

    @Test
    fun `a note half-way between two ticks goes the way mido's floating-point sum goes`() {
        // Key 62 at MIDI tick 6792 sounds at exactly 7.075 s (7,075,000 µs): 707.5 ticks. Rounded exactly (or from
        // the parser's microseconds) that is 708; mido's summed seconds are 7.074999…, and the package wrote 707.
        val piece = ComposerFixtures.bach
        val index = (0 until piece.noteCount).single { piece.notes.startMicros[it] == 7_075_000L }
        assertEquals(62, piece.notes.note(index))
        assertEquals(708.0, Math.rint(piece.notes.startMicros[index] / 10_000.0), 0.0)
        val note = AmtTokenizer.notes(piece).single { it.key == 62 && abs(it.on - 7.075) < 1e-9 }
        assertTrue("mido's sum lands below the half: ${note.on * 100}", note.on * 100 < 707.5)
        assertEquals(707, AmtTokenizer.events(listOf(note)).single().time)
        val tokens = ComposerFixtures.inputTokens
        val at = (1 until tokens.size step 3).single { tokens[it + 2] == Amt.NOTE_OFFSET + 62 && tokens[it] in 700..710 }
        assertEquals(707, tokens[at])
    }

    @Test
    fun `the seed decodes back to the file's notes, each within half a tick`() {
        val events = AmtTokenizer.decode(ComposerFixtures.inputTokens)
        assertEquals(71, events.size)
        assertTrue(events.none { it.isRest })
        val piece = ComposerFixtures.bach
        val notes = piece.notes
        val inWindow = (0 until notes.size).filter { notes.startMicros[it] <= 15_005_000L }
        assertEquals(inWindow.size, events.size)
        val half = AmtTokenizer.MICROS_PER_TICK / 2 + 1
        for ((e, i) in events.zip(inWindow)) {
            assertEquals(0, e.instrument)
            assertEquals(notes.note(i), e.pitch)
            assertTrue("onset ${e.time} vs ${notes.startMicros[i]}", abs(e.time * AmtTokenizer.MICROS_PER_TICK - notes.startMicros[i]) <= half)
            val length = notes.endMicros[i] - notes.startMicros[i]
            assertTrue("duration ${e.duration} vs $length", abs(e.duration * AmtTokenizer.MICROS_PER_TICK - length) <= half)
        }
        // As notes for the MIDI writer: the same keys and times, velocities left for the post-processor.
        val written = AmtTokenizer.toNotes(events)
        assertEquals(71, written.size)
        assertEquals(events.first().time * 10_000L, written.first().onMicros)
        assertTrue(written.all { it.velocity == AmtTokenizer.DEFAULT_VELOCITY })
    }

    @Test
    fun `PyTorch's continuation decodes to the fixture's events`() {
        val tokens = ComposerFixtures.torchContinuation
        val whole = tokens.copyOf(tokens.size / 3 * 3)
        val events = AmtTokenizer.decode(whole)
        val expected = ComposerFixtures.seed.getJSONArray("expected_continuation_events")
        assertEquals(expected.length(), events.size)
        for ((i, e) in events.withIndex()) {
            val o = expected.getJSONObject(i)
            assertEquals(o.getInt("time"), e.time)
            assertEquals(o.getInt("duration"), e.duration)
            assertEquals(o.getInt("instrument"), e.instrument)
            assertEquals(o.getInt("pitch"), e.pitch)
        }
    }

    @Test
    fun `events round-trip through tokens`() {
        val random = Random(7)
        repeat(20) {
            val events = List(random.nextInt(0, 50)) {
                if (random.nextInt(8) == 0) {
                    AmtEvent.rest(random.nextInt(Amt.MAX_TIME))
                } else {
                    AmtEvent(random.nextInt(Amt.MAX_TIME), random.nextInt(Amt.MAX_DUR), random.nextInt(Amt.MAX_NOTE))
                }
            }
            val tokens = AmtTokenizer.tokens(events)
            assertEquals(events, AmtTokenizer.decode(tokens))
            assertEquals(events, AmtTokenizer.decode(AmtTokenizer.prompt(events)))
        }
        val rest = AmtTokenizer.tokens(listOf(AmtEvent.rest(250)))
        assertArrayEquals(intArrayOf(250, Amt.DUR_OFFSET, Amt.REST), rest)
    }

    @Test
    fun `decoding refuses controls, special tokens and partial events`() {
        val bad = listOf(
            intArrayOf(Amt.AUTOREGRESS, 10, Amt.DUR_OFFSET + 5),                 // a partial event
            intArrayOf(10, Amt.DUR_OFFSET + 5, Amt.CONTROL_OFFSET + 11_060),     // an anticipated note
            intArrayOf(Amt.SEPARATOR, Amt.SEPARATOR, Amt.SEPARATOR),
            intArrayOf(10, 11_060, Amt.DUR_OFFSET + 5),                          // slots swapped
            intArrayOf(Amt.DUR_OFFSET, Amt.DUR_OFFSET, 11_060),
        )
        for (tokens in bad) {
            try {
                AmtTokenizer.decode(tokens)
                fail("decoded ${tokens.toList()}")
            } catch (e: IllegalArgumentException) {
                // refused
            }
        }
        for (event in listOf(AmtEvent(Amt.MAX_TIME, 0, 60), AmtEvent(0, Amt.MAX_DUR, 60), AmtEvent(0, 0, Amt.REST_NOTE + 1), AmtEvent(-1, 0, 60))) {
            try {
                AmtTokenizer.tokens(listOf(event))
                fail("encoded $event")
            } catch (e: IllegalArgumentException) {
                // refused
            }
        }
    }

    @Test
    fun `pad puts a rest in every second without an event, clip keeps both ends`() {
        val events = listOf(AmtEvent(0, 20, 60), AmtEvent(350, 20, 64), AmtEvent(400, 20, 67))
        val padded = AmtTokenizer.pad(events, 620)
        assertEquals(
            listOf(
                AmtEvent(0, 20, 60), AmtEvent.rest(100), AmtEvent.rest(200), AmtEvent.rest(300), AmtEvent(350, 20, 64),
                AmtEvent(400, 20, 67), AmtEvent.rest(500), AmtEvent.rest(600),
            ),
            padded,
        )
        assertEquals("a gap of exactly a second needs none", listOf(AmtEvent(0, 5, 60), AmtEvent(100, 5, 60)), AmtTokenizer.pad(listOf(AmtEvent(0, 5, 60), AmtEvent(100, 5, 60))))
        assertEquals(events, AmtTokenizer.unpad(padded))
        assertEquals(listOf(AmtEvent(350, 20, 64), AmtEvent(400, 20, 67)), AmtTokenizer.clip(events, 350, 400))
        assertEquals(listOf(AmtEvent(0, 20, 60)), AmtTokenizer.clip(events, 0, 349))
        assertEquals(400, AmtTokenizer.maxTime(events))
        assertEquals(0, AmtTokenizer.minTime(events))
        assertEquals(0, AmtTokenizer.maxTime(emptyList()))
        assertEquals(listOf(AmtEvent(50, 20, 64), AmtEvent(100, 20, 67)), AmtTokenizer.translate(AmtTokenizer.clip(events, 350, 400), -300))
    }

    @Test
    fun `drums are left out, a re-strike ends the note before, a note never ended lasts 250 ms`() {
        val bytes = SmfBuilder(format = 0).track {
            noteOn(0, 60)
            noteOn(0, 36, channel = 9)
            noteOff(96, 36, channel = 9)
            noteOff(480, 60)                 // 0.5 s at 120 bpm, 480 ticks a quarter
            noteOn(960, 64)
            noteOn(1440, 64)                 // struck again while held: the first ends here
            noteOff(1920, 64)
            cc(2000, 64, 127)
            noteOn(2400, 67, channel = 1)    // never let go
        }.build()
        val piece = SmfParser.parse(bytes)
        val notes = AmtTokenizer.notes(piece)
        assertEquals(listOf(60, 64, 64, 67), notes.map { it.key })
        assertEquals(
            listOf(AmtEvent(0, 50, 60), AmtEvent(100, 50, 64), AmtEvent(150, 50, 64), AmtEvent(250, Amt.UNKNOWN_DURATION, 67)),
            AmtTokenizer.events(notes),
        )
        // Only the notes starting within 1.2 s of the first; the one held across the window's end still ends where it ends.
        val window = AmtTokenizer.notes(piece, seconds = 1.2)
        assertEquals(listOf(AmtEvent(0, 50, 60), AmtEvent(100, 50, 64)), AmtTokenizer.events(window))
        // Times from the first note, and scaled: at half speed everything takes twice as long.
        val late = SmfParser.parse(SmfBuilder(format = 0).track { noteOn(1920, 60); noteOff(2400, 60); noteOn(2880, 62); noteOff(3010, 62) }.build())
        val lateNotes = AmtTokenizer.notes(late)
        assertEquals(listOf(AmtEvent(0, 50, 60), AmtEvent(100, 14, 62)), AmtTokenizer.events(lateNotes, origin = lateNotes.first().on))
        assertEquals(listOf(AmtEvent(0, 100, 60), AmtEvent(200, 27, 62)), AmtTokenizer.events(lateNotes, origin = lateNotes.first().on, scale = 2.0))
        assertTrue(AmtTokenizer.notes(SmfParser.parse(SmfBuilder(format = 0).track { noteOn(0, 36, channel = 9); noteOff(10, 36, channel = 9) }.build())).isEmpty())
    }

    @Test
    fun `a tempo change between two notes times the later one as the parser does`() {
        val bytes = SmfBuilder(format = 0).track {
            tempo(0, 500_000)
            noteOn(0, 60)
            noteOff(480, 60)
            tempo(720, 250_000)              // alone at its tick, between the two notes
            noteOn(1000, 62)
            noteOff(1480, 62)
            noteOn(1480, 64)
            noteOff(1960, 64)
        }.build()
        val piece = SmfParser.parse(bytes)
        val events = AmtTokenizer.events(AmtTokenizer.notes(piece))
        val parsed = piece.notes
        assertEquals(3, events.size)
        for (i in events.indices) {
            assertEquals(Math.rint(parsed.startMicros[i] / 10_000.0).toInt(), events[i].time)
            assertEquals(Math.rint((parsed.endMicros[i] - parsed.startMicros[i]) / 10_000.0).toInt(), events[i].duration)
        }
        assertEquals(listOf(0, 90, 115), events.map { it.time })
        assertEquals(listOf(50, 25, 25), events.map { it.duration })
    }
}
