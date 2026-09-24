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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmfParserTest {

    private fun List<TimedEvent>.hex() = map { "${it.atMicros} ${hex(it.status, it.data1, it.data2)}" }

    @Test
    fun `format 0 lands on the microsecond timeline at 120 BPM by default`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 0).track {
                name(0, "Nocturne")
                noteOn(0, 60, 90)
                noteOff(480, 60)
                noteOn(480, 64, 70)
                noteOff(960, 64)
            }.build(),
        )
        assertEquals(0, piece.format)
        assertEquals(480, piece.ppq)
        assertEquals("Nocturne", piece.sequenceName)
        assertEquals(
            listOf("0 90 3C 5A", "500000 80 3C 00", "500000 90 40 46", "1000000 80 40 00"),
            piece.events.hex(),
        )
        assertEquals(1_000_000L, piece.durationMicros)
        assertEquals(2, piece.noteCount)
        assertTrue(piece.warnings.isEmpty())
    }

    @Test
    fun `format 1 merges tracks by time and keeps only Track 0's names`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 1).track {
                name(0, "Kinderszenen Opus 15")
                name(0, "Von fremden Ländern und Menschen")
                tempo(0, 500_000)
            }.track {
                name(0, "Piano right")
                noteOn(0, 72)
                noteOff(480, 72)
            }.track {
                noteOn(240, 48)
                noteOff(720, 48)
            }.build(),
        )
        assertEquals(listOf("Kinderszenen Opus 15", "Von fremden Ländern und Menschen"), piece.sequenceNames)
        assertEquals(
            listOf("0 90 48 50", "250000 90 30 50", "500000 80 48 00", "750000 80 30 00"),
            piece.events.hex(),
        )
    }

    @Test
    fun `running status carries the last status and velocity 0 is a Note Off`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 0).track {
                raw(0, 0x90, 60, 100)
                raw(0, 64, 100)
                raw(240, 60, 0)
                raw(240, 64, 0)
            }.build(),
        )
        assertEquals(
            listOf("0 90 3C 64", "0 90 40 64", "250000 80 3C 00", "250000 80 40 00"),
            piece.events.hex(),
        )
        assertTrue(piece.warnings.isEmpty())
    }

    @Test
    fun `a meta event cancels running status`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 0).track {
                raw(0, 0x90, 60, 100)
                name(0, "Title")
                raw(240, 60, 0)   // a data byte with no status to run on
            }.build(),
        )
        assertEquals(listOf("0 90 3C 64"), piece.events.hex())
        assertEquals(1, piece.warnings.size)
    }

    @Test
    fun `a tempo change mid-file is exact to the microsecond`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 1, division = 96).track {
                tempo(0, 333_333)
                tempo(7, 250_000)
            }.track {
                noteOn(1, 60)      // 1 * 333333 / 96 = 3472.2
                noteOff(10, 60)    // 7 * 333333 / 96 = 24305.5, then 3 * 250000 / 96 = 7812.5
            }.build(),
        )
        assertEquals(listOf("3472 90 3C 50", "32117 80 3C 00"), piece.events.hex())

        val halfSpeed = SmfParser.parse(
            SmfBuilder().track {
                tempo(0, 500_000)
                tempo(960, 1_000_000)
            }.track {
                noteOn(480, 60)
                noteOff(1440, 60)
            }.build(),
        )
        assertEquals(listOf("500000 90 3C 50", "2000000 80 3C 00"), halfSpeed.events.hex())
    }

    @Test
    fun `note off comes before note on at equal times, whatever the track order`() {
        val piece = SmfParser.parse(
            SmfBuilder().track {
                noteOn(480, 60, 70)                // the new strike, in the earlier track
            }.track {
                noteOn(0, 60, 90)
                noteOff(480, 60)
            }.build(),
        )
        assertEquals(
            listOf("0 90 3C 5A", "500000 80 3C 00", "500000 90 3C 46"),
            piece.events.hex(),
        )
    }

    @Test
    fun `controllers sort before notes at equal times, and non-piano messages are dropped`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 0).track {
                raw(0, 0xC0, 5)          // program change
                raw(0, 0xE0, 0, 64)      // pitch bend
                raw(0, 0xA0, 60, 10)     // poly aftertouch
                raw(0, 0xD0, 20)         // channel pressure
                noteOn(0, 60)
                cc(0, 64, 127)
                raw(0, 0xF0, 3, 0x7E, 0x7F, 0xF7)   // SysEx, skipped
                noteOff(480, 60)
            }.build(),
        )
        assertEquals(listOf("0 B0 40 7F", "0 90 3C 50", "500000 80 3C 00"), piece.events.hex())
    }

    @Test
    fun `a zero-length note is dropped rather than left held`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 0).track {
                noteOn(0, 60)
                noteOff(0, 60)
                noteOn(240, 62)
                noteOff(480, 62)
            }.build(),
        )
        assertEquals(listOf("250000 90 3E 50", "500000 80 3E 00"), piece.events.hex())
    }

    @Test
    fun `a key struck again without a release is released first`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 0).track {
                noteOn(0, 60)
                noteOn(240, 60)
                noteOff(480, 60)
            }.build(),
        )
        assertEquals(
            listOf("0 90 3C 50", "250000 80 3C 00", "250000 90 3C 50", "500000 80 3C 00"),
            piece.events.hex(),
        )
        assertEquals(2, piece.noteCount)
        assertEquals(250_000L, piece.notes.endMicros[0])
    }

    @Test
    fun `SMPTE timing and format 2 are rejected in plain English`() {
        val smpte = assertThrows(SmfException::class.java) {
            SmfParser.parse(SmfBuilder(division = 0xE728).track { noteOn(0, 60) }.build())
        }
        assertTrue(smpte.message!!.contains("SMPTE"))
        val format2 = assertThrows(SmfException::class.java) {
            SmfParser.parse(SmfBuilder(format = 2).track { noteOn(0, 60) }.build())
        }
        assertTrue(format2.message!!.contains("format 2"))
        assertThrows(SmfException::class.java) { SmfParser.parse("RIFF....WAVEfmt ".toByteArray()) }
        assertThrows(SmfException::class.java) { SmfParser.parse(SmfBuilder().build()) }
    }

    @Test
    fun `a track cut short parses as far as it goes, with a warning`() {
        val full = SmfBuilder().track {
            tempo(0, 500_000)
        }.track {
            noteOn(0, 60)
            noteOff(480, 60)
            noteOn(480, 62)
            noteOff(960, 62)
        }.build()
        val piece = SmfParser.parse(full.copyOf(full.size - 6))   // loses End of Track and a Note Off
        assertEquals(listOf("0 90 3C 50", "500000 80 3C 00", "500000 90 3E 50"), piece.events.hex())
        assertEquals(1, piece.warnings.size)
        assertEquals(500_000L, piece.notes.endMicros[1])   // closed at the end of the piece
    }

    @Test
    fun `junk after the last track is skipped with a warning`() {
        val music = SmfBuilder(format = 0).track { noteOn(0, 60); noteOff(480, 60) }.build()
        val junk = byteArrayOf(0xB3.toByte(), 0xD0.toByte(), 0xC6.toByte(), 0x8E.toByte(), 0xDE.toByte(), 0x88.toByte(), 0x5A, 0x00, 1, 2, 3)
        val piece = SmfParser.parse(music + junk)
        assertEquals(2, piece.events.size)
        assertEquals(listOf("The file ends with damaged data, which is skipped."), piece.warnings)
    }

    @Test
    fun `extra header bytes and unknown chunks are skipped, missing tracks warn`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 1, headerExtra = byteArrayOf(1, 2), declaredTracks = 3)
                .unknownChunk("XFIH", byteArrayOf(9, 9, 9))
                .track { noteOn(0, 60); noteOff(480, 60) }
                .track { noteOn(0, 67); noteOff(480, 67) }
                .build(),
        )
        assertEquals(4, piece.events.size)
        assertEquals(listOf("The file lists 3 tracks but holds 2."), piece.warnings)
    }

    @Test
    fun `text is UTF-8 when valid, else Latin-1`() {
        val utf8 = SmfParser.parse(SmfBuilder().track { name(0, "Frédéric") }.build())
        val latin1 = SmfParser.parse(SmfBuilder().track { name(0, "Frédéric", Charsets.ISO_8859_1) }.build())
        assertEquals("Frédéric", utf8.sequenceName)
        assertEquals("Frédéric", latin1.sequenceName)
        assertNull(SmfParser.parse(SmfBuilder().track { noteOn(0, 60) }.build()).sequenceName)
    }

    @Test
    fun `notes pair per channel and key, sorted by start`() {
        val piece = SmfParser.parse(
            SmfBuilder(format = 0).track {
                noteOn(0, 60, channel = 0)
                noteOn(120, 60, channel = 1)
                noteOff(240, 60, channel = 0)
                noteOff(480, 60, channel = 1)
            }.build(),
        )
        val notes = piece.notes
        assertEquals(2, notes.size)
        assertEquals(listOf(0L, 125_000L), notes.startMicros.toList())
        assertEquals(listOf(250_000L, 500_000L), notes.endMicros.toList())
        assertEquals(listOf(0, 1), (0 until notes.size).map(notes::channel))
        assertEquals(375_000L, notes.maxDurationMicros)
        assertEquals(1, notes.firstStartingAtOrAfter(1))
        assertEquals(2, notes.firstStartingAtOrAfter(125_001))
        assertEquals(0, notes.firstStartingAtOrAfter(0))
    }
}
