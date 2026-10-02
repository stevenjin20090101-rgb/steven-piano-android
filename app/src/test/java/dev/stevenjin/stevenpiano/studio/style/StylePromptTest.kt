// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.style

import dev.stevenjin.stevenpiano.channels.Channels
import dev.stevenjin.stevenpiano.data.builtin.BuiltInCatalogue
import dev.stevenjin.stevenpiano.data.builtin.LibraryFixture
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The idea box's keyword parser (v1.12 — M30) over Steven's library as the importer names it
 * ([LibraryFixture.corpus]) with the real channels and built-in lists: moods, tempo, keys, lengths, a title, a
 * composer with a form, refinements of the last turn, words it does not use, and input meant to hurt it.
 */
class StylePromptTest {
    private val lists = BuiltInCatalogue.parse(LibraryFixture.asset(BuiltInCatalogue.ASSET).readText())
    private val channels = Channels.parse(LibraryFixture.asset(Channels.ASSET).readText(), lists)
    private val library = StyleLibrary.of(LibraryFixture.corpus, channels, lists)

    private fun parse(text: String, previous: PreviousTurn? = null) = StylePrompt.parse(text, library, previous)

    @Test
    fun `a calm chopin nocturne for three minutes is Chopin's nocturnes, calm, three minutes, every word used`() {
        val result = parse("a calm chopin nocturne, 3 minutes")
        assertEquals(Mood.Calm, result.spec.mood)
        assertEquals(3, result.spec.minutes)
        assertEquals(SeedAsk.Pool("composer:chopin+form:nocturne", "Chopin's nocturnes"), result.spec.seed)
        assertEquals(emptyList<String>(), result.unused)
        assertFalse(result.refinement)
        assertTrue(result.candidates.isNotEmpty())
        assertTrue(result.candidates.all { id -> library.byId.getValue(id).let { it.composerKey == "chopin" && "nocturne" in it.titleKey } })
        assertEquals("Calm · 3 min · in the manner of Chopin's nocturnes", result.line)
    }

    @Test
    fun `a title is found, and a tempo word beside it`() {
        val result = parse("clair de lune but faster")
        val seed = result.spec.seed as SeedAsk.Title
        assertEquals("clair de lune", seed.words)
        assertEquals("Suite bergamasque — Clair de Lune", library.byId.getValue(result.candidates.first()).title)
        assertEquals(TempoAsk.Scale(StyleVocabulary.tempoSteps.getValue("faster")), result.spec.tempo)
        assertEquals(emptyList<String>(), result.unused)
        assertTrue(result.line, result.line.endsWith("in the manner of Suite bergamasque — Clair de Lune (Debussy)"))
    }

    @Test
    fun `mood, tempo and key are understood together`() {
        val result = parse("Stormy and fast, in D minor")
        assertEquals(Mood.Wild, result.spec.mood)
        assertEquals(MusicKey(2, true), result.spec.key)
        assertEquals(TempoAsk.Class(132, "fast"), result.spec.tempo)
        assertEquals("Wild · D minor · fast · 2 min", result.line.substringBefore(" · in the manner"))
        assertEquals(emptyList<String>(), result.unused)
    }

    @Test
    fun `lengths are held to one to five minutes, and the line says so`() {
        assertEquals(5, parse("a ten minute waltz").spec.minutes)
        assertTrue(parse("a ten minute waltz").line.contains("5 min (the longest)"))
        assertEquals(1, parse("30 seconds of something bright").spec.minutes)
        assertTrue(parse("30 seconds of something bright").line.contains("1 min (the shortest)"))
        assertEquals(3, parse("2:30 please").spec.minutes)   // two and a half, rounded
        assertEquals(2, parse("a bright waltz, 2 minutes").spec.minutes)
    }

    @Test
    fun `slower, longer and another refine the last turn and say what changed`() {
        val first = parse("a calm chopin nocturne, 3 minutes")
        val previous = PreviousTurn(first.spec, bpm = 60)
        val slower = parse("slower", previous)
        assertTrue(slower.refinement)
        assertEquals(TempoAsk.Exact(51), slower.spec.tempo)
        assertEquals(first.spec.seed, slower.spec.seed)
        assertEquals(3, slower.spec.minutes)
        assertEquals("Slower: 51 bpm", slower.line)
        val longer = parse("longer", previous)
        assertEquals(4, longer.spec.minutes)
        assertEquals("Longer: 4 min", longer.line)
        val again = parse("another like it", previous)
        assertTrue(again.again)
        assertEquals(first.spec, again.spec)
        assertEquals("Another like it", again.line)
        // A seed-bearing idea starts afresh.
        assertFalse(parse("a waltz", previous).refinement)
    }

    @Test
    fun `a negated word changes nothing and is not used`() {
        val result = parse("not too fast")
        assertNull(result.spec.tempo)
        assertEquals(listOf("fast"), result.unused)
    }

    @Test
    fun `words the parser does not know are not used, in the person's own spelling, and never in the line`() {
        val result = parse("Calm unicorn Rainbows")
        assertEquals(listOf("unicorn", "Rainbows"), result.unused)
        assertFalse(result.line.contains("unicorn", ignoreCase = true) || result.line.contains("rainbow", ignoreCase = true))
        assertEquals(Mood.Calm, result.spec.mood)
    }

    @Test
    fun `accents and case do not matter`() {
        val a = parse("für elise")
        assertEquals(a.candidates, parse("fur elise").candidates)
        assertEquals(a.candidates, parse("FÜR ELISE").candidates)
        assertEquals("Für Elise", library.byId.getValue(a.candidates.first()).title)
    }

    @Test
    fun `hostile input neither crashes nor matches, and only its own words come back unused`() {
        for (text in listOf("(.*)+$", "🎹🎹🎹 ‮esrever‬", "שלום עולם", "calm\u0000\u0007slow", "%s%n%d", "' OR 1=1 --", "\uD800 broken")) {
            val result = parse(text)
            val typed = StyleWords.cut(text).map { it.text }
            assertTrue("$text: ${result.unused}", typed.containsAll(result.unused))
            assertTrue(result.candidates.size <= StylePrompt.MAX_CANDIDATES)
        }
        assertEquals(TempoAsk.Class(60, "slow"), parse("calm\u0000\u0007slow").spec.tempo)   // a control character parts words
        val long = "nocturne ".repeat(2_000)
        val started = System.nanoTime()
        val result = parse(long)
        assertTrue("10,000 characters parse in time", System.nanoTime() - started < 3_000_000_000L)
        assertTrue(StyleWords.cut(long, StylePrompt.MAX_WORDS).size <= StylePrompt.MAX_WORDS)
        assertEquals(SeedAsk.Pool("form:nocturne", "a nocturne"), result.spec.seed)
    }

    @Test
    fun `the same idea over a shuffled library gives the same result`() {
        val shuffled = StyleLibrary.of(LibraryFixture.corpus.shuffled(Random(7)), channels, lists)
        for (text in listOf("a calm chopin nocturne", "something bright and fast", "clair de lune", "a sad waltz in e minor")) {
            assertEquals(text, parse(text), StylePrompt.parse(text, shuffled))
        }
    }

    @Test
    fun `a spec survives the history's line`() {
        val spec = parse("a stormy chopin etude in c minor, 4 minutes, 120 bpm").spec
        assertEquals(spec, StyleSpec.decode(spec.encode()))
        assertNull(StyleSpec.decode(null))
    }

    @Test
    fun `titles come from what was understood, never from what was typed`() {
        assertEquals("Calm, after Clair de lune", StylePrompt.title(Mood.Calm, SeedAsk.Title("clair de lune", "x"), "Clair de lune"))
        assertEquals("Wild, after Chopin", StylePrompt.title(Mood.Wild, SeedAsk.Pool("composer:chopin+form:etude", "Chopin's études"), "Étude Op. 10"))
        assertEquals("Calm, after a nocturne", StylePrompt.title(Mood.Calm, SeedAsk.Pool("form:nocturne", "a nocturne"), "Nocturne"))
        assertEquals("Bright piece", StylePrompt.title(Mood.Bright, SeedAsk.Default, "Für Elise"))
        assertEquals("Melancholy piece", StylePrompt.title(Mood.Melancholy, SeedAsk.Pool("mood:melancholy", ""), "Nocturne"))
    }
}
