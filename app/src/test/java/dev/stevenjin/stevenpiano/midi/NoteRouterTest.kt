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
import dev.stevenjin.stevenpiano.midi.NoteRouter.Companion.PEDAL_BURST
import org.junit.Test

class NoteRouterTest {
    private val router = NoteRouter()
    private val out = MidiBatch()

    private fun send(status: Int, data1: Int, data2: Int, atMs: Long = 0): List<String> {
        out.clear()
        router.route(status, data1, data2, atMs * 1000, out)
        return out.hex()
    }

    @Test
    fun `out-of-range notes fold by octaves into C1-B7`() {
        assertEquals(35, KeyMap.map(23, 0, fold = true))
        assertEquals(96, KeyMap.map(108, 0, fold = true))
        assertEquals(33, KeyMap.map(21, 0, fold = true))
        assertEquals(103, KeyMap.map(127, 0, fold = true))
        assertEquals(98, KeyMap.map(105, 5, fold = true))
        assertEquals(60, KeyMap.map(60, 0, fold = true))
        assertEquals(listOf("90 23 50"), send(0x90, 23, 80))
    }

    @Test
    fun `drop mode skips out-of-range notes and their releases`() {
        router.fold = false
        assertEquals(KeyMap.UNPLAYABLE, KeyMap.map(23, 0, fold = false))
        assertEquals(KeyMap.UNPLAYABLE, KeyMap.map(106, 2, fold = false))
        assertEquals(emptyList<String>(), send(0x90, 23, 80))
        assertEquals(emptyList<String>(), send(0x80, 23, 0))
        assertEquals(listOf("90 18 50"), send(0x90, 24, 80))
    }

    @Test
    fun `a transpose change mid-note releases the key that was sent`() {
        assertEquals(listOf("90 3C 50"), send(0x90, 60, 80))
        router.transpose = 2
        assertEquals(listOf("80 3C 00"), send(0x80, 60, 0, atMs = 200))
        assertEquals(listOf("90 3E 50"), send(0x90, 60, 80, atMs = 300))
    }

    @Test
    fun `two sources on one key send one Note On and one Note Off`() {
        assertEquals(listOf("90 3C 50"), send(0x90, 60, 80))
        assertEquals(emptyList<String>(), send(0x91, 60, 90, atMs = 10))
        assertEquals(emptyList<String>(), send(0x80, 60, 0, atMs = 300))
        assertTrue(router.isSounding(60))
        assertEquals(listOf("80 3C 00"), send(0x81, 60, 0, atMs = 400))
        assertFalse(router.isSounding(60))
    }

    @Test
    fun `notes that fold onto one key share it`() {
        assertEquals(listOf("90 60 50"), send(0x90, 96, 80))
        assertEquals(emptyList<String>(), send(0x90, 108, 80))
        assertEquals(emptyList<String>(), send(0x80, 96, 0, atMs = 200))
        assertEquals(listOf("80 60 00"), send(0x80, 108, 0, atMs = 250))
    }

    @Test
    fun `a strike within 100 ms of the previous strike of an idle key is thinned`() {
        assertEquals(listOf("90 3C 50"), send(0x90, 60, 80, atMs = 0))
        assertEquals(listOf("80 3C 00"), send(0x80, 60, 0, atMs = 40))
        assertEquals(emptyList<String>(), send(0x90, 60, 80, atMs = 99))
        assertEquals(emptyList<String>(), send(0x80, 60, 0, atMs = 120))   // its release is ignored too
        assertEquals(listOf("90 3C 50"), send(0x90, 60, 80, atMs = 130))
        assertEquals(listOf("80 3C 00"), send(0x80, 60, 0, atMs = 170))
        assertEquals(listOf("90 3C 50"), send(0x90, 60, 80, atMs = 230))   // exactly 100 ms later
    }

    @Test
    fun `a source struck again while sounding is released first`() {
        assertEquals(listOf("90 3C 50"), send(0x90, 60, 80, atMs = 0))
        assertEquals(listOf("80 3C 00", "90 3C 46"), send(0x90, 60, 70, atMs = 200))
    }

    @Test
    fun `CC64 is forwarded on channel 1, repeats are not`() {
        assertEquals(listOf("B0 40 7F"), send(0xB3, 64, 127))
        assertEquals(emptyList<String>(), send(0xB0, 64, 127))
        assertEquals(listOf("B0 40 28"), send(0xB0, 64, 40))
        assertEquals(listOf("B0 40 00"), send(0xB0, 64, 0))
    }

    @Test
    fun `a file's pedal changes pass three at once, then one per 50 ms, the latest waiting value winning`() {
        assertEquals(listOf("B0 40 7F"), send(0xB0, 64, 127, atMs = 0))
        assertEquals(listOf("B0 40 00"), send(0xB0, 64, 0, atMs = 1))
        assertEquals(listOf("B0 40 7F"), send(0xB0, 64, 127, atMs = 2))
        assertEquals(emptyList<String>(), send(0xB0, 64, 0, atMs = 3))    // the burst is spent: it waits
        assertEquals(emptyList<String>(), send(0xB0, 64, 40, atMs = 4))   // and is replaced by the newer value
        assertEquals(50_000L, router.pedalDueMicros)   // 2,000 µs of credit left at 2 ms: a change's worth (50,000) by 50 ms
        out.clear()
        router.flushPedal(49_999, out)
        assertEquals(emptyList<String>(), out.hex())
        router.flushPedal(50_000, out)
        assertEquals(listOf("B0 40 28"), out.hex())
        assertEquals(Long.MAX_VALUE, router.pedalDueMicros)
    }

    @Test
    fun `a waiting pedal change back to where the pedal is sends nothing`() {
        send(0xB0, 64, 127, atMs = 0)
        send(0xB0, 64, 0, atMs = 1)
        send(0xB0, 64, 127, atMs = 2)
        send(0xB0, 64, 0, atMs = 3)
        assertEquals(emptyList<String>(), send(0xB0, 64, 127, atMs = 4))   // back down, as the piano has it
        assertEquals(Long.MAX_VALUE, router.pedalDueMicros)
    }

    @Test
    fun `a pedal flapping every millisecond reaches the piano at most 20 times a second`() {
        var changes = 0
        for (ms in 0L until 2_000L) {
            out.clear()
            router.flushPedal(ms * 1000, out)
            router.route(0xB0, 64, if (ms % 2 == 0L) 127 else 0, ms * 1000, out)
            changes += out.hex().count { it.startsWith("B0 40") }
        }
        assertTrue("$changes changes in 2 s", changes <= PEDAL_BURST + 2 * 20)
    }

    @Test
    fun `the stop sequence is never held back, and after it the pedal goes at once`() {
        send(0xB0, 64, 127, atMs = 0)
        send(0xB0, 64, 0, atMs = 1)
        send(0xB0, 64, 127, atMs = 2)
        send(0xB0, 64, 0, atMs = 3)   // waiting
        out.clear()
        router.silence(out)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), out.hex())
        assertEquals(Long.MAX_VALUE, router.pedalDueMicros)   // the waiting change is gone with the rest
        assertEquals(listOf("B0 40 7F"), send(0xB0, 64, 127, atMs = 3))   // a resumed piece's pedal: at once
    }

    @Test
    fun `all-off controllers, other controllers and non-note messages never reach the piano`() {
        for (controller in listOf(120, 121, 123, 7, 10, 66, 67, 1, 91)) {
            assertEquals("CC$controller", emptyList<String>(), send(0xB0, controller, 0))
            assertEquals("CC$controller", emptyList<String>(), send(0xB0, controller, 127))
        }
        assertEquals(emptyList<String>(), send(0xC0, 5, 0))
        assertEquals(emptyList<String>(), send(0xE0, 0, 64))
        assertEquals(emptyList<String>(), send(0xA0, 60, 10))
        assertEquals(emptyList<String>(), send(0xD0, 20, 0))
    }

    @Test
    fun `silence is pedal up then All Notes Off, and forgets held keys`() {
        send(0x90, 60, 80)
        send(0xB0, 64, 127)
        out.clear()
        router.silence(out)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), out.hex())
        assertEquals(0L, router.activeLow)
        assertEquals(emptyList<String>(), send(0x80, 60, 0, atMs = 200))
        assertEquals(listOf("90 3C 50"), send(0x90, 60, 80, atMs = 300))
        assertEquals(emptyList<String>(), send(0xB0, 64, 0, atMs = 300))   // the pedal is known to be up
    }

    @Test
    fun `the drum channel is skipped only while the setting is on`() {
        assertEquals(emptyList<String>(), send(0x99, 38, 100))
        assertEquals(emptyList<String>(), send(0xB9, 64, 127))
        router.skipDrums = false
        assertEquals(listOf("90 26 64"), send(0x99, 38, 100, atMs = 500))
    }

    @Test
    fun `velocity scales and stays within 1 to 127`() {
        router.velocityPct = 150
        assertEquals(listOf("90 3C 7F"), send(0x90, 60, 100))
        router.velocityPct = 50
        assertEquals(listOf("90 3E 20"), send(0x90, 62, 64))
        assertEquals(listOf("90 40 01"), send(0x90, 64, 1))
    }

    @Test
    fun `active keys track what the piano is playing`() {
        send(0x90, 24, 80)
        send(0x90, 107, 80)
        assertEquals(1L, router.activeLow)
        assertEquals(1L shl 19, router.activeHigh)
        send(0x80, 24, 0, atMs = 200)
        assertEquals(0L, router.activeLow)
        assertTrue(router.isSounding(107))
    }

    // ---- A MIDI keyboard's bank and the instrument's profile (v1.11 — M29) -----------------------

    private fun ext(atMs: Long = 0, block: NoteRouter.(Long) -> Unit): List<String> {
        out.clear()
        router.block(atMs * 1000)
        return out.hex()
    }

    @Test
    fun `a keyboard's notes keep their pitch, transpose never applies, the velocity percentage does`() {
        router.transpose = 5
        router.velocityPct = 50
        assertEquals(listOf("90 3C 32"), ext { externalNoteOn(60, 100, it, out) })
        assertEquals(listOf("80 3C 00"), ext(300) { externalNoteOff(60, out) })
        assertEquals(emptyList<String>(), ext(400) { externalNoteOff(60, out) })
    }

    @Test
    fun `keys 21 to 23 and 108 fold in by an octave on Steven Piano, or are dropped with folding off`() {
        assertEquals(listOf("90 21 50", "90 22 50", "90 23 50", "90 60 50"), ext {
            externalNoteOn(21, 80, it, out)
            externalNoteOn(22, 80, it, out)
            externalNoteOn(23, 80, it, out)
            externalNoteOn(108, 80, it, out)
        })
        assertEquals(listOf("80 21 00", "80 60 00"), ext(200) {
            externalNoteOff(21, out)
            externalNoteOff(108, out)
        })
        router.fold = false
        assertEquals(emptyList<String>(), ext(400) {
            externalNoteOn(21, 80, it, out)
            externalNoteOn(108, 80, it, out)
            externalNoteOff(21, out)
        })
        assertEquals(listOf("90 18 50"), ext(500) { externalNoteOn(24, 80, it, out) })
    }

    @Test
    fun `a keyboard key and a piece's note on one key share it on Steven Piano`() {
        assertEquals(listOf("90 3C 50"), send(0x90, 60, 80))
        assertEquals(emptyList<String>(), ext(150) { externalNoteOn(60, 90, it, out) })
        assertEquals(emptyList<String>(), send(0x80, 60, 0, atMs = 200))
        assertTrue(router.isSounding(60))
        assertEquals(listOf("80 3C 00"), ext(300) { externalNoteOff(60, out) })
    }

    @Test
    fun `a key pressed again while held keeps holding within 100 ms on Steven Piano, and strikes again after`() {
        assertEquals(listOf("90 3C 50"), ext { externalNoteOn(60, 80, it, out) })
        assertEquals("a lost release, too soon: it keeps holding", emptyList<String>(), ext(50) { externalNoteOn(60, 90, it, out) })
        assertEquals(listOf("80 3C 00", "90 3C 5A"), ext(150) { externalNoteOn(60, 90, it, out) })
        send(0x90, 60, 80, atMs = 300)   // the piece shares it now
        assertEquals("shared: never re-struck", emptyList<String>(), ext(500) { externalNoteOn(60, 70, it, out) })
        assertTrue(router.externalHeld)
        assertEquals(emptyList<String>(), ext(600) { externalNoteOff(60, out) })
        assertTrue(!router.externalHeld)
    }

    @Test
    fun `the keyboard's pedal goes at once when it crosses 64, the values between are paced`() {
        assertEquals("from up, crossing: at once", listOf("B0 40 50"), ext { externalPedal(64, 80, it, out) })
        assertEquals(listOf("B0 40 5A"), ext(1) { externalPedal(64, 90, it, out) })
        assertEquals(listOf("B0 40 64"), ext(2) { externalPedal(64, 100, it, out) })
        assertEquals(listOf("B0 40 6E"), ext(3) { externalPedal(64, 110, it, out) })
        assertEquals("three paced changes spent the bucket: the fourth waits", emptyList<String>(), ext(4) { externalPedal(64, 120, it, out) })
        assertTrue(router.pedalDueMicros != Long.MAX_VALUE)
        assertEquals("crossing 64 goes at once, and the waiting value with it", listOf("B0 40 00"), ext(5) { externalPedal(64, 0, it, out) })
        assertEquals(Long.MAX_VALUE, router.pedalDueMicros)
        assertEquals(listOf("B0 40 7F"), ext(6) { externalPedal(64, 127, it, out) })
        assertEquals("sostenuto and soft: not Steven Piano's", emptyList<String>(), ext(7) {
            externalPedal(66, 127, it, out)
            externalPedal(67, 127, it, out)
        })
    }

    @Test
    fun `letting go of the keyboard leaves the piece's keys, and its pedal goes back to the higher of the piece's and the screen's`() {
        send(0x90, 64, 80)
        send(0xB0, 64, 40, atMs = 1)
        ext(2) {
            externalNoteOn(60, 80, it, out)
            externalNoteOn(64, 80, it, out)
            externalPedal(64, 127, it, out)
        }
        assertEquals(listOf("80 3C 00", "B0 40 28"), ext(200) { silenceExternal(out) })
        assertTrue(router.isSounding(64))
        assertTrue(!router.isSounding(60))
        ext(300) {
            liveSustain(true, out)
            externalPedal(64, 0, it, out)
        }
        assertEquals("the screen's sustain wins over the piece's 40", listOf("B0 40 7F"), ext(400) { silenceExternal(out) })
        assertEquals("nothing left to let go", emptyList<String>(), ext(500) { silenceExternal(out) })
    }

    @Test
    fun `keys outside the screen's 84 light it an octave in, counted`() {
        router.profile = InstrumentProfile.StandardPiano
        ext {
            externalNoteOn(21, 80, it, out)
            externalNoteOn(33, 80, it, out)
            externalNoteOn(108, 80, it, out)
        }
        assertEquals(1L shl (33 - 24), router.activeLow)
        assertEquals(1L shl (96 - 88), router.activeHigh)
        ext(200) { externalNoteOff(21, out) }
        assertEquals("33 still sounds itself", 1L shl (33 - 24), router.activeLow)
        ext(300) { externalNoteOff(33, out) }
        assertEquals(0L, router.activeLow)
    }

    @Test
    fun `a MIDI piano takes 21 to 108, strikes a held key again, and its three pedals pass unpaced`() {
        router.profile = InstrumentProfile.StandardPiano
        router.transpose = 2
        assertEquals("21 is its own; a piece's note transposes", listOf("90 15 50", "90 6C 50"), ext {
            externalNoteOn(21, 80, it, out)
            route(0x90, 106, 80, it, out)
        })
        assertEquals("125 + 2 = 127 folds by octaves to 103", listOf("90 67 50"), send(0x90, 125, 80, atMs = 1))
        assertEquals("struck again at once: no 100 ms on a digital piano", listOf("80 15 00", "90 15 5A"), ext(2) { externalNoteOn(21, 90, it, out) })
        assertEquals("a key another source holds is struck again", listOf("80 6C 00", "90 6C 46"), ext(3) { externalNoteOn(108, 70, it, out) })
        assertEquals(listOf("B0 40 7F", "B0 42 7F", "B0 43 40", "B0 40 00", "B0 40 7F"), ext(4) {
            externalPedal(64, 127, it, out)
            externalPedal(66, 127, it, out)
            externalPedal(67, 64, it, out)
            externalPedal(67, 64, it, out)
            externalPedal(64, 0, it, out)
            externalPedal(64, 127, it, out)
        })
        assertEquals("a piece's sostenuto passes too", listOf("B0 42 00"), send(0xB0, 66, 0, atMs = 5))
    }

    @Test
    fun `a MIDI piano's stop sequence is a Note Off for every key held, its pedals up, then All Notes Off`() {
        router.profile = InstrumentProfile.StandardPiano
        ext {
            externalNoteOn(21, 80, it, out)
            externalNoteOn(60, 80, it, out)
            route(0x90, 108, 80, it, out)
            externalPedal(64, 127, it, out)
        }
        out.clear()
        router.silence(out)
        assertEquals(listOf("80 15 00", "80 3C 00", "80 6C 00", "B0 40 00", "B0 42 00", "B0 43 00", "B0 7B 00"), out.hex())
        assertEquals(0L, router.activeLow)
        assertEquals("forgotten", emptyList<String>(), ext(100) { externalNoteOff(60, out) })
    }
}
