// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.midi.SmfParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * A transcription into a piece (v1.7 — M23): the MIDI file the importer is handed, the title from the
 * recording's name (or "Recording · <date>"), the composer "Made in Studio", the sheet's own line, and
 * the refusals.
 */
class StudioPiecesTest {
    private class FakeLibrary : StudioLibrary {
        val added = mutableListOf<Triple<String, ByteArray, Pair<String, String>>>()
        val described = mutableMapOf<Long, String>()
        val discarded = mutableListOf<Long>()
        var refuse = false

        override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long? {
            if (refuse) return null
            added += Triple(fileName, bytes, title to composer)
            return 40L + added.size
        }

        override suspend fun describe(pieceId: Long, description: String) {
            described[pieceId] = description
        }

        override suspend fun discard(pieceId: Long) {
            discarded += pieceId
        }

        override suspend fun exists(pieceId: Long) = pieceId !in discarded
    }

    private val at = ZonedDateTime.of(2026, 9, 28, 14, 3, 5, 0, ZoneId.of("America/New_York"))
    private val library = FakeLibrary()
    private val pieces = StudioPieces(library, Locale.US)
    private val transcription = Transcription(
        notes = listOf(
            TranscribedNote(0.5f, 1.25f, 60, 64),
            TranscribedNote(0.5f, 2.0f, 64, 128),
            TranscribedNote(3.0101f, 3.02f, 108, 0),
        ),
        pedals = listOf(PedalEvent(0.4f, 2.5f)),
    )

    @Test
    fun `the notes and pedal become a piece named for the recording, by Made in Studio, described with the date`() = runBlocking {
        val piece = pieces.add(transcription, "Clair de lune (live).m4a", at)
        assertEquals(StudioPiece(41, "Clair de lune (live)"), piece)
        val (fileName, bytes, names) = library.added.single()
        assertEquals("Clair de lune (live).mid", fileName)
        assertEquals("Clair de lune (live)" to "Made in Studio", names)
        assertEquals("Made in Studio · Sep 28, 2026", library.described[41])
        val midi = SmfParser.parse(bytes)
        assertEquals("Clair de lune (live)", midi.sequenceName)
        assertEquals(listOf("Made in Studio, 2026-09-28 14:03:05"), midi.texts)
        assertEquals(3, midi.noteCount)
        val notes = (0 until 3).map { i -> midi.notes.run { listOf(startMicros[i], endMicros[i], note(i).toLong(), velocity(i).toLong()) } }
        for ((want, got) in listOf(listOf(500_000L, 1_250_000L, 60L, 64L), listOf(500_000L, 2_000_000L, 64L, 127L), listOf(3_010_100L, 3_020_000L, 108L, 1L))
            .zip(notes.sortedBy { it[2] })) {
            assertTrue("$want vs $got", abs(want[0] - got[0]) <= 600 && abs(want[1] - got[1]) <= 600)
            assertEquals("key and velocity (128 held to 127, 0 to 1)", want.drop(2), got.drop(2))
        }
        assertEquals(listOf(127, 0), midi.events.filter { it.command == 0xB0 && it.data1 == 64 }.map { it.data2 })
        assertEquals(ComposerNames.STUDIO, "Made in Studio")
    }

    @Test
    fun `a recording without a usable name is called Recording and the date`() {
        assertEquals("Recording · Sep 28, 2026", pieces.title(null, at))
        assertEquals("Recording · Sep 28, 2026", pieces.title("   .wav", at))
        assertEquals("take_7", pieces.title("content/recordings/take_7.wav", at))
        assertEquals("a.b", pieces.title("a.b.flac", at))
        assertEquals(".hidden", pieces.title(".hidden", at))
        assertEquals("Recording · 28 Sept 2026", StudioPieces(library, Locale.UK).title(null, at))
        assertEquals(200, pieces.title("x".repeat(500) + ".mp3", at).length)
    }

    @Test
    fun `nothing heard, or a library that won't take it, is refused in words`() = runBlocking {
        failsWith(StudioFailures.NO_NOTES) { pieces.add(Transcription(emptyList(), listOf(PedalEvent(0f, 1f))), "x.wav", at) }
        library.refuse = true
        failsWith(StudioFailures.NOT_SAVED) { pieces.add(transcription, "x.wav", at) }
        assertTrue(library.described.isEmpty())
    }

    private suspend fun failsWith(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("no failure; expected \"$message\"")
        } catch (e: StudioFailure) {
            assertEquals(message, e.message)
        }
    }
}
