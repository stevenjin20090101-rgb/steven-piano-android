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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ImportCopyTest {
    private val original = Locale.getDefault()

    @Before
    fun english() = Locale.setDefault(Locale.US)

    @After
    fun restore() = Locale.setDefault(original)

    @Test
    fun `a running import counts files looked at`() {
        assertEquals("Looking for MIDI files…", ImportCopy.running(ImportProgress(finished = false)))
        assertEquals("Imported 1,204 of 1,727", ImportCopy.running(ImportProgress(done = 1_204, total = 1_727, finished = false)))
    }

    @Test
    fun `the summary names what came in and what could not be read, and passes over duplicates`() {
        assertEquals("Imported 12 pieces.", ImportCopy.summary(ImportProgress(done = 14, total = 14, imported = 12, duplicates = 2)))
        assertEquals("Imported 1 piece.", ImportCopy.summary(ImportProgress(done = 1, total = 1, imported = 1)))
        assertEquals("Imported 5 pieces. 1 file couldn't be read.", ImportCopy.summary(ImportProgress(done = 6, total = 6, imported = 5, failed = 1)))
        assertEquals("2 files couldn't be read.", ImportCopy.summary(ImportProgress(done = 2, total = 2, failed = 2)))
        assertEquals("That piece is already in the library.", ImportCopy.summary(ImportProgress(done = 1, total = 1, duplicates = 1)))
        assertEquals("Those pieces are already in the library.", ImportCopy.summary(ImportProgress(done = 3, total = 3, duplicates = 3)))
    }

    @Test
    fun `files another app sent are asked about before they are added`() {
        assertEquals("Add 3 files to the library?", ImportCopy.addShared(3))
        assertEquals("Add 1 file to the library?", ImportCopy.addShared(1))
        assertEquals("Add 500 files to the library?", ImportCopy.addShared(500))
        assertEquals("Another app sent this file. Adding copies it into Steven Piano.", ImportCopy.sharedDetail(1))
    }

    @Test
    fun `a shared file the app may not read says so, and what to do instead`() {
        assertEquals(
            "Couldn't read that file. Try Add files instead.",
            ImportCopy.summary(ImportProgress(done = 1, total = 1, failed = 1, unreadable = true)),
        )
        assertEquals(
            "Couldn't read those files. Try Add files instead.",
            ImportCopy.summary(ImportProgress(done = 3, total = 3, failed = 3, unreadable = true)),
        )
    }
}
