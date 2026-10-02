// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.channels

import dev.stevenjin.stevenpiano.data.builtin.BuiltInCatalogue
import dev.stevenjin.stevenpiano.data.builtin.LibraryFixture
import dev.stevenjin.stevenpiano.data.builtin.LibraryFixture.piece
import dev.stevenjin.stevenpiano.ui.ChannelCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The channels (DESIGN.md › v1.5 — M17): their order and names, each pool's rule (composers,
 * titles, Calm's note density, Epic as the built-in list, Everything as the library), the pools in
 * Steven's library, the four composers a card shows, and what the channels say.
 */
class ChannelsTest {
    private val builtIns = BuiltInCatalogue.parse(LibraryFixture.asset(BuiltInCatalogue.ASSET).readText())
    private val channels = Channels.parse(LibraryFixture.asset(Channels.ASSET).readText(), builtIns)

    private fun channel(key: String) = channels.first { it.key == key }

    @Test
    fun `the channels in their order on screen`() {
        assertEquals(listOf("calm", "epic", "recognisable", "popular", "baroque", "romantic", "impressionist", "nocturnes", "etudes", "everything"), channels.map { it.key })
        assertEquals(listOf("Calm", "Epic", "Recognisable", "Popular", "Baroque", "Romantic", "Impressionist", "Nocturnes", "Études", "Everything"), channels.map { it.name })
    }

    @Test
    fun `Everything is the whole library, and Epic is the built-in list's pieces in its order`() {
        val library = LibraryFixture.corpus
        assertEquals(library.map { it.id }, channel("everything").pool(library))
        assertEquals(builtIns.first { it.key == "epic" }.matches(library), channel("epic").pool(library))
        assertEquals(LibraryFixture.epicZip.map { it.id }, channel("epic").pool(LibraryFixture.epicZip))
    }

    @Test
    fun `Calm takes its composers and titles, and only what plays no more than six notes a second`() {
        val calm = channel("calm")
        val pieces = listOf(
            piece(1, "Gymnopédie No. 1", "Erik Satie", notes = 400, durationMs = 180_000),   // 2.2 a second
            piece(2, "Nocturne Op. 9 No. 2", "Chopin", notes = 1_000, durationMs = 250_000),   // by title: 4 a second
            piece(3, "Étude Op. 10 No. 4", "Chopin", notes = 2_400, durationMs = 120_000),   // Chopin, not by title
            piece(4, "Suite bergamasque — Clair de Lune", "Debussy", notes = 1_800, durationMs = 300_000),   // 6 exactly
            piece(5, "L'isle joyeuse", "Debussy", notes = 4_000, durationMs = 300_000),   // Debussy, but 13 a second
            piece(6, "Träumerei", "Schumann", notes = 300, durationMs = 150_000),   // folded title
            piece(7, "Nocturne", "Field", notes = 0, durationMs = 0),   // no length: not counted calm
        )
        assertEquals(listOf(1L, 2L, 4L, 6L), calm.pool(pieces))
    }

    @Test
    fun `Baroque, Romantic and Impressionist go by composer, Nocturnes and Études by title`() {
        val pieces = listOf(
            piece(1, "Rondo in C minor", "Bach, CPE"),
            piece(2, "Invention No. 1", "Johann Sebastian Bach"),
            piece(3, "Sonata K. 380", "Scarlatti"),
            piece(4, "Nachtstück Op. 23", "Robert Schumann"),
            piece(5, "Étude Op. 25 No. 11", "Chopin"),
            piece(6, "Etüde Opus 10 No. 5", "chopin"),
            piece(7, "Etueden Opus 109 — Agitato", "burgmueller"),
            piece(8, "Study in Chromatic Steps", "Debussy"),
            piece(9, "Notturno Op. 54 No. 4", "Grieg"),
            piece(10, "Jeux d'eau", "Ravel"),
        )
        assertEquals(listOf(1L, 2L, 3L), channel("baroque").pool(pieces))
        assertEquals(listOf(4L, 5L, 6L, 9L), channel("romantic").pool(pieces))
        assertEquals(listOf(8L, 10L), channel("impressionist").pool(pieces))
        assertEquals(listOf(4L, 9L), channel("nocturnes").pool(pieces))
        assertEquals(listOf(5L, 6L, 7L, 8L), channel("etudes").pool(pieces))
    }

    @Test
    fun `in Steven's library every channel has a pool to play`() {
        val fromZip = Channels.summaries(channels, LibraryFixture.allSongs)
        println("Channel pools in Steven's library from ALL-SONGS.zip: " + fromZip.joinToString { "${it.key} ${it.size}" })
        assertTrue(fromZip.all { it.playable })
        val summaries = Channels.summaries(channels, LibraryFixture.corpus)
        println("Channel pools in Steven's library from the midi folder: " + summaries.joinToString { "${it.key} ${it.size}" })
        assertTrue(summaries.all { it.playable })
        assertEquals(1_727, summaries.last().size)
        assertEquals(listOf("chopin", "debussy", "schumann"), summaries.first { it.key == "calm" }.composers.take(3).map { it.key })
        assertEquals(listOf("bach", "scarlatti", "handel", "purcell"), summaries.first { it.key == "baroque" }.composers.map { it.key })
    }

    @Test
    fun `a card shows the four composers it holds most pieces by, known ones only`() {
        val pool = listOf(
            piece(1, "A", "Debussy"), piece(2, "B", "Debussy"), piece(3, "C", "Ravel"), piece(4, "D", "Satie"),
            piece(5, "E", "Satie"), piece(6, "F", "Satie"), piece(7, "G", "Fauré"), piece(8, "H", "Albéniz"), piece(9, "I", ""), piece(10, "J", ""),
        )
        assertEquals(
            listOf(CardComposer("satie", "Satie"), CardComposer("debussy", "Debussy"), CardComposer("albeniz", "Albéniz"), CardComposer("faure", "Fauré")),
            Channels.topComposers(pool),
        )
    }

    @Test
    fun `a pool under three pieces cannot play`() {
        assertFalse(ChannelSummary("calm", "Calm", listOf(1, 2), emptyList()).playable)
        assertTrue(ChannelSummary("calm", "Calm", listOf(1, 2, 3), emptyList()).playable)
    }

    @Test
    fun `the composer line names the channel while one plays, and a card says what it holds`() {
        assertEquals("Claude Debussy · Calm · Channel", ChannelCopy.eyebrow("Claude Debussy", "Calm"))
        assertEquals("Calm · Channel", ChannelCopy.eyebrow(" ", "Calm"))
        assertEquals("Claude Debussy", ChannelCopy.eyebrow("Claude Debussy", null))
        assertEquals("", ChannelCopy.eyebrow("", null))
        val calm = ChannelSummary("calm", "Calm", listOf(1, 2, 3, 4), emptyList())
        assertEquals("4 pieces", ChannelCopy.cardMeta(calm, playing = false))
        assertEquals("Playing", ChannelCopy.cardMeta(calm, playing = true))
        assertEquals("Add more pieces", ChannelCopy.cardMeta(ChannelSummary("epic", "Epic", listOf(1), emptyList()), playing = false))
    }
}
