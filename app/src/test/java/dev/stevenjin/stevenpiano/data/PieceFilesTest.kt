// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PieceFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a file is stored once under its hash`() {
        val files = PieceFiles(tmp.root)
        val name = files.write("abc", byteArrayOf(1, 2, 3))
        assertEquals("abc.mid", name)
        assertArrayEquals(byteArrayOf(1, 2, 3), files.read(name))
        assertEquals("abc.mid", files.write("abc", byteArrayOf(1, 2, 3)))
        assertFalse(File(tmp.root, "pieces/abc.mid.part").exists())
        files.delete(name)
        assertFalse(File(tmp.root, "pieces/abc.mid").exists())
    }
}
