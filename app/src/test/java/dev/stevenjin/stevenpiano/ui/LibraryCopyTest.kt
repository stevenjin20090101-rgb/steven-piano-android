// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.library.LibraryFailures
import dev.stevenjin.stevenpiano.library.PackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The words about Steven's library pack (DESIGN.md › v1.10 — M27), one line each, and when each shows. */
class LibraryCopyTest {
    private val offer = PackState.Offered(version = 1, pieces = 1_726, sizeBytes = 61_237_277, newPieces = 1_726)

    @Test
    fun `the row's line is the pack's facts, what the load does, or why it stopped`() {
        assertEquals("1,726 pieces · 61 MB · MAESTRO, piano-midi.de, Mutopia · for non-commercial use", LibraryCopy.facts(offer, Locale.ROOT))
        assertEquals("before the pack is known, its sources alone", "MAESTRO, piano-midi.de, Mutopia · for non-commercial use", LibraryCopy.facts(null))
        assertEquals("1,726 pieces · 61 MB · MAESTRO, piano-midi.de, Mutopia · for non-commercial use", LibraryCopy.line(offer, offer, Locale.ROOT))
        assertEquals("Asking for the library…", LibraryCopy.line(PackState.Checking, offer))
        assertEquals("Loading · 23 of 61 MB", LibraryCopy.line(PackState.Downloading(23_000_000, 61_237_277), offer, Locale.ROOT))
        assertEquals("Adding the pieces…", LibraryCopy.line(PackState.Importing, offer))
        assertEquals(LibraryFailures.OFFLINE, LibraryCopy.line(PackState.Failed(LibraryFailures.OFFLINE), offer))
        assertEquals(LibraryCopy.facts(offer, Locale.ROOT), LibraryCopy.line(PackState.Done(1_726), offer, Locale.ROOT))
    }

    @Test
    fun `an update says how many pieces are new, when it knows`() {
        assertEquals("Update the library · 2 new pieces", LibraryCopy.updateLabel(2))
        assertEquals("Update the library · 1 new piece", LibraryCopy.updateLabel(1))
        assertEquals("Update the library", LibraryCopy.updateLabel(0))
        assertEquals("Update the library", LibraryCopy.updateLabel(null))
        assertEquals("Load Steven's library", LibraryCopy.LOAD)
    }

    @Test
    fun `the Library's bar and the notification follow the load`() {
        assertEquals("Loading Steven's library · 23 of 61 MB", LibraryCopy.bar(PackState.Downloading(23_000_000, 61_237_277), Locale.ROOT))
        assertEquals("Loading Steven's library…", LibraryCopy.bar(PackState.Checking))
        assertNull("the import shows its own line", LibraryCopy.bar(PackState.Importing))
        assertNull(LibraryCopy.bar(offer))
        assertEquals(0.5f, LibraryCopy.progress(PackState.Downloading(50, 100))!!, 1e-6f)
        assertNull(LibraryCopy.progress(PackState.Importing))
        assertEquals("23 of 61 MB", LibraryCopy.notificationText(PackState.Downloading(23_000_000, 61_237_277), ImportProgress.Idle, Locale.ROOT))
        val importing = ImportProgress(done = 204, total = 1_726, finished = false)
        assertEquals("Imported 204 of 1,726", LibraryCopy.notificationText(PackState.Importing, importing, Locale.ROOT))
        assertEquals("Loading Steven's library", LibraryCopy.NOTIFICATION_TITLE)
    }

    @Test
    fun `the licence sheet credits the three collections and says what the pack may be used for`() {
        assertEquals(listOf("MAESTRO v3.0.0 · Google Magenta", "piano-midi.de", "The Mutopia Project"), LibraryCopy.CREDIT_LINES.map { it.name })
        assertTrue(LibraryCopy.CREDIT_LINES[0].line.contains("Curtis Hawthorne et al."))
        assertTrue(LibraryCopy.CREDIT_LINES[0].line.endsWith("CC BY-NC-SA 4.0."))
        assertTrue(LibraryCopy.CREDIT_LINES[1].line.startsWith("Bernd Krueger, www.piano-midi.de."))
        assertTrue(LibraryCopy.CREDIT_LINES[2].line.endsWith("Public domain."))
        assertTrue(LibraryCopy.NON_COMMERCIAL_NOTE.startsWith("For non-commercial use"))
        assertEquals("Load · 61 MB", LibraryCopy.loadButton(offer))
        assertEquals("Load", LibraryCopy.loadButton(null))
        assertEquals(
            "1,726 piano pieces from three open collections, a 61 MB download from Steven Piano's releases on GitHub.",
            LibraryCopy.sheetIntro(offer, Locale.ROOT),
        )
    }
}
