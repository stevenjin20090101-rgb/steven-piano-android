// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.audio.SoundDownload
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
import dev.stevenjin.stevenpiano.audio.TabletSoundState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The tablet's piano sound in words (DESIGN.md › v1.8 — M25): sizes, progress, which note shows when, Always's warning. */
class TabletSoundCopyTest {
    private val waiting = TabletSoundState(TabletSoundMode.WHEN_NOT_CONNECTED, connected = false, installed = false)

    @Test
    fun `Always warns of the drift`() {
        assertTrue(TabletSoundCopy.modeNote(TabletSoundMode.ALWAYS).endsWith("It may sound slightly early or late compared with the piano."))
        assertTrue(TabletSoundCopy.modeNote(TabletSoundMode.WHEN_NOT_CONNECTED).contains("while the piano isn't connected"))
    }

    @Test
    fun `the SoundFont's row reads its size and licence, its download, or that it is installed`() {
        assertEquals("57 MB · CC0 · FreePats · A Kawai upright, recorded note by note.", TabletSoundCopy.line(waiting))
        assertEquals("Downloading · 12 of 57 MB", TabletSoundCopy.line(waiting.copy(download = SoundDownload.Running(12_000_000, 57_377_848)), locale = Locale.ROOT))
        assertEquals(0.5f, TabletSoundCopy.progress(waiting.copy(download = SoundDownload.Running(50, 100)))!!, 1e-6f)
        assertNull(TabletSoundCopy.progress(waiting))
        assertEquals("Installed · 57 MB · CC0", TabletSoundCopy.line(waiting.copy(installed = true)))
        assertEquals("There isn't enough free space for the piano sound.", TabletSoundCopy.line(waiting.copy(download = SoundDownload.Failed("There isn't enough free space for the piano sound."))))
    }

    @Test
    fun `the glyph says the volume, and when the sound is off here`() {
        assertEquals("Tablet sound, volume 60%", TabletSoundCopy.glyphDescription(waiting.copy(installed = true)))
        assertEquals("Tablet sound, off here, volume 60%", TabletSoundCopy.glyphDescription(waiting))
    }

    @Test
    fun `Now playing's note shows only while the sound waits for its download`() {
        assertEquals("Hear it on this tablet: the piano sound is a 57 MB download.", TabletSoundCopy.nowPlayingNote(waiting))
        assertEquals(
            "Downloading the piano sound · 3 of 57 MB",
            TabletSoundCopy.nowPlayingNote(waiting.copy(download = SoundDownload.Running(3_000_000, 57_377_848)), locale = Locale.ROOT),
        )
        assertNull("installed", TabletSoundCopy.nowPlayingNote(waiting.copy(installed = true)))
        assertNull("the piano is connected", TabletSoundCopy.nowPlayingNote(waiting.copy(connected = true)))
        assertNull("off", TabletSoundCopy.nowPlayingNote(waiting.copy(mode = TabletSoundMode.OFF)))
    }
}
