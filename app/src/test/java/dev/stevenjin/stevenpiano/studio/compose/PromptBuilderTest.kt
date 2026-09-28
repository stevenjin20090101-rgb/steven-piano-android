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
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.studio.StudioFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * The composer's prompt (v1.7 — M24): the seed's first 15 s as the package builds a prompt, untouched at
 * the seed's own key and tempo (the fixture's 214 tokens), transposed and time-scaled otherwise; mood
 * chips to sampling, length to a token budget, the seed's key and tempo as the sheet's defaults.
 */
class PromptBuilderTest {
    private val bach = SeedPiece("Prelude in C major", "Johann Sebastian Bach", ComposerFixtures.bach)
    private val fixtureEvents = AmtTokenizer.decode(ComposerFixtures.inputTokens)

    /** A one-track file at 120 bpm (480 ticks a quarter, 960 a second) from (tick, length, key). */
    private fun midi(vararg notes: Triple<Long, Long, Int>): MidiPiece = SmfParser.parse(
        SmfBuilder(format = 0).track {
            val offs = notes.map { Triple(it.first + it.second, 0, it.third) }
            val all = (notes.map { Triple(it.first, 1, it.third) } + offs).sortedWith(compareBy({ it.first }, { it.second }))
            for ((tick, on, key) in all) if (on == 1) noteOn(tick, key) else noteOff(tick, key)
        }.build(),
    )

    @Test
    fun `the mood chips set the sampling and how softly the piano plays`() {
        assertEquals(listOf("Calm", "Bright", "Wild", "Melancholy"), Mood.entries.map { it.label })
        assertEquals(SamplingSettings(0.8, 0.9), Mood.Calm.sampling)
        assertEquals(SamplingSettings(1.0, 0.95), Mood.Bright.sampling)
        assertEquals(SamplingSettings(1.15, 0.98), Mood.Wild.sampling)
        assertEquals(SamplingSettings(0.85, 0.9), Mood.Melancholy.sampling)
        assertTrue(Mood.entries.all { it.sampling.topP <= 0.98 && !it.sampling.allowEnd && !it.sampling.greedy })
        assertEquals("Calm plays softest", Mood.Calm, Mood.entries.minBy { it.velocity })
        assertTrue(Mood.Bright.velocity < Mood.Wild.velocity && Mood.Melancholy.velocity < Mood.Bright.velocity)
    }

    @Test
    fun `a length of 1 to 5 minutes is 30 tokens a second, at most 9,000`() {
        assertEquals(listOf(1_800, 3_600, 5_400, 7_200, 9_000), (1..5).map { PromptBuilder.budget(it) })
        assertEquals(1_800, PromptBuilder.budget(0))
        assertEquals(9_000, PromptBuilder.budget(12))
        assertTrue((1..5).all { PromptBuilder.budget(it) % 3 == 0 })
    }

    @Test
    fun `the Bach seed at its own key and tempo is the fixture's seed, token for token`() {
        val facts = PromptBuilder.facts(bach.midi)
        assertEquals(SeedFacts(MusicKey.C, 120, 120.0), facts)
        val prompt = PromptBuilder.build(bach, ComposeRequest())
        assertArrayEquals(ComposerFixtures.inputTokens, prompt.tokens)
        assertEquals(1_499, prompt.currentTime)
        assertEquals(0, prompt.transpose)
        assertEquals(1.0, prompt.timeScale, 0.0)
        assertEquals(120, prompt.bpm)
        assertEquals(MusicKey.C, prompt.key)
        assertEquals(Mood.Calm.sampling, prompt.sampling)
        assertEquals(3_600, prompt.budget)
        assertEquals("the seed's 15 s and 2 minutes", 1_500 + 12_000, prompt.endTime)
        assertEquals("Prelude in C major (Johann Sebastian Bach)", prompt.mannerOf)
        // Choosing the seed's own key and tempo is the same as leaving them.
        val same = PromptBuilder.build(bach, ComposeRequest(Mood.Wild, MusicKey.C, 120, 5))
        assertArrayEquals(ComposerFixtures.inputTokens, same.tokens)
        assertEquals(9_000, same.budget)
        assertEquals(1_500 + 30_000, same.endTime)
        assertEquals(Mood.Wild.sampling, same.sampling)
    }

    @Test
    fun `another key moves every note of the seed by one shift, by signature and toward the middle`() {
        fun shifted(key: MusicKey): Prompt = PromptBuilder.build(bach, ComposeRequest(key = key))
        val d = shifted(MusicKey(2, false))
        assertEquals(2, d.transpose)
        assertEquals(fixtureEvents.map { it.copy(note = it.note + 2) }, d.events)
        assertEquals("A minor shares C major's signature", 0, shifted(MusicKey(9, true)).transpose)
        assertEquals("E minor is G major's: up 7 or down 5, the smaller", -5, shifted(MusicKey(4, true)).transpose)
        assertEquals("F♯ either way: down, nearer the middle for this seed", -6, shifted(MusicKey(6, false)).transpose)
        assertEquals(fixtureEvents.map { it.copy(note = it.note - 6) }, shifted(MusicKey(6, false)).events)
        val pitches = listOf(25, 104, 106)
        assertEquals("up 5 would push two keys off the top, down 7 only one off the bottom", -7, PromptBuilder.transposition(MusicKey.C, MusicKey(5, false), pitches))
        assertEquals(0, PromptBuilder.transposition(MusicKey(9, true), MusicKey.C, pitches))
        assertEquals(3, PromptBuilder.transposition(MusicKey(9, true), MusicKey(0, true), emptyList()))
    }

    @Test
    fun `another tempo stretches or squeezes the seed's times before the 15 s are cut`() {
        val slow = PromptBuilder.build(bach, ComposeRequest(bpm = 60))
        assertEquals(2.0, slow.timeScale, 0.0)
        assertEquals(60, slow.bpm)
        val firstHalf = fixtureEvents.filter { it.time <= 750 }
        assertEquals(firstHalf.size, slow.events.size)
        for ((was, now) in firstHalf.zip(slow.events)) {
            assertEquals(was.note, now.note)
            assertTrue("$was -> $now", abs(now.time - 2 * was.time) <= 1 && abs(now.duration - 2 * was.duration) <= 1)
        }
        assertTrue(slow.events.all { it.time <= PromptBuilder.SEED_TICKS })
        val fast = PromptBuilder.build(bach, ComposeRequest(bpm = 500))
        assertEquals("the stepper stops at 200", 200, fast.bpm)
        assertEquals(0.6, fast.timeScale, 1e-12)
        assertTrue("more of the piece fits in 15 s", fast.events.size > fixtureEvents.size)
        assertEquals(40, PromptBuilder.build(bach, ComposeRequest(bpm = 10)).bpm)
    }

    @Test
    fun `the seed's key is found from its notes, and Melancholy suggests its minor`() {
        // A minor: i - iv - V - i, arpeggiated, a second each.
        val chords = listOf(listOf(57, 60, 64), listOf(62, 65, 69), listOf(64, 68, 71), listOf(57, 60, 64, 69))
        val notes = chords.flatMapIndexed { bar, chord -> chord.mapIndexed { i, key -> Triple(bar * 960L + i * 240L, 240L, key) } }
        val minor = midi(*notes.toTypedArray())
        val facts = PromptBuilder.facts(minor)
        assertEquals(MusicKey(9, true), facts.key)
        assertEquals(120, facts.bpm)
        assertEquals(MusicKey(9, true), PromptBuilder.suggestedKey(Mood.Melancholy, facts.key))
        assertEquals(MusicKey(9, true), PromptBuilder.suggestedKey(Mood.Melancholy, MusicKey.C))
        assertEquals(MusicKey(4, true), PromptBuilder.suggestedKey(Mood.Melancholy, MusicKey(7, false)))
        assertEquals(MusicKey.C, PromptBuilder.suggestedKey(Mood.Calm, MusicKey.C))
        assertEquals(MusicKey.C, PromptBuilder.keyOf(emptyList()))
    }

    @Test
    fun `a key signature in the file narrows the key to its major and relative minor`() {
        // G major's triad and scale, in a file that says no sharps or flats: C major, not G major.
        val gNotes = listOf(55, 59, 62, 67, 71, 74, 79, 66, 67, 62, 59, 55)
        fun file(sharps: Int?, keys: List<Int>) = SmfParser.parse(
            SmfBuilder(format = 0).track {
                if (sharps != null) keySignature(0, sharps)
                keys.forEachIndexed { i, key ->
                    noteOn(i * 480L, key)
                    noteOff(i * 480L + 460, key)
                }
            }.build(),
        )
        assertEquals("the notes alone", MusicKey(7, false), PromptBuilder.facts(file(null, gNotes)).key)
        assertEquals("the signature's two keys", MusicKey(0, false), PromptBuilder.facts(file(0, gNotes)).key)
        // A minor's figure in a file that says C major (as Für Elise's do): its relative minor.
        val aMinor = listOf(76, 75, 76, 75, 76, 71, 74, 72, 69, 57, 64, 69, 72, 76, 81, 71, 64, 68, 71, 69)
        assertEquals(MusicKey(9, true), PromptBuilder.facts(file(0, aMinor)).key)
        // Five flats: D♭ major or B♭ minor, never F minor.
        val dFlat = listOf(61, 65, 68, 73, 77, 80, 66, 70, 73, 68, 72, 75, 61, 65, 68)
        assertEquals(MusicKey(1, false), PromptBuilder.facts(file(-5, dFlat)).key)
        assertEquals("nothing to go on: the signature's major", MusicKey(1, false), PromptBuilder.keyOf(emptyList(), -5))
        assertEquals(MusicKey.C, PromptBuilder.keyOf(emptyList()))
    }

    @Test
    fun `a seed starts at its first note, stays on the piano's keys, and one without notes is refused`() {
        val late = midi(Triple(2_880L, 480L, 60), Triple(3_360L, 480L, 64))
        val prompt = PromptBuilder.build(SeedPiece("Late", null, late), ComposeRequest())
        assertEquals(listOf(AmtEvent(0, 50, 60), AmtEvent(50, 50, 64)), AmtTokenizer.unpad(prompt.events))
        assertEquals("Late", prompt.mannerOf)
        // Keys off the piano fold by octaves; two that land on one key at one time are one note.
        val wide = midi(Triple(0L, 480L, 20), Triple(0L, 480L, 32), Triple(480L, 480L, 110), Triple(960L, 480L, 60))
        val folded = PromptBuilder.build(SeedPiece("Wide", "", wide), ComposeRequest())
        assertEquals(listOf(32, 98, 60), AmtTokenizer.unpad(folded.events).map { it.pitch })
        assertEquals("Wide", folded.mannerOf)
        for (empty in listOf(midi(), SmfParser.parse(SmfBuilder(format = 0).track { noteOn(0, 36, channel = 9); noteOff(100, 36, channel = 9) }.build()))) {
            try {
                PromptBuilder.build(SeedPiece("Silence", null, empty), ComposeRequest())
                fail("a seed without notes")
            } catch (e: StudioFailure) {
                assertEquals(ComposeFailures.NO_SEED, e.message)
            }
        }
    }

    @Test
    fun `keys read as the sheet's chips`() {
        assertEquals(24, MusicKey.all.size)
        assertEquals("C major", MusicKey.C.label)
        assertEquals("F♯ major", MusicKey(6, false).label)
        assertEquals("B♭ major", MusicKey(10, false).label)
        assertEquals("C♯ minor", MusicKey(1, true).label)
        assertEquals("A minor", MusicKey(9, true).label)
        assertEquals(0, MusicKey(9, true).relativeMajor)
        assertEquals(MusicKey(9, true), MusicKey.C.relativeMinor)
        assertEquals(MusicKey(4, true), MusicKey(4, true).relativeMinor)
    }
}
