// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RollCardFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val card = ByteArray(RollCardFiles.CARD_BYTES) { if (it % 7 == 0) 200.toByte() else 0 }

    @Test
    fun `a card kept on disk reads back the same, small`() {
        val files = RollCardFiles(File(tmp.root, "rollcards"))
        assertNull(files.read(7))
        files.write(7, card)
        assertArrayEquals(card, files.read(7))
        val stored = File(tmp.root, "rollcards").listFiles()!!.single()
        assertTrue("${stored.length()} bytes", stored.length() < 8 * 1024)
        files.delete(7)
        assertNull(files.read(7))
    }

    @Test
    fun `anything that is not a card of the right size reads as none`() {
        val dir = File(tmp.root, "rollcards")
        val files = RollCardFiles(dir)
        files.write(1, ByteArray(10))   // not a card: not kept
        assertNull(files.read(1))
        dir.mkdirs()
        File(dir, "v1-2.a8.gz").writeBytes(byteArrayOf(1, 2, 3))   // not gzip
        assertNull(files.read(2))
        files.write(3, card)
        File(dir, "v1-3.a8.gz").let { it.writeBytes(it.readBytes().copyOf(20)) }   // cut short
        assertNull(files.read(3))
    }
}
