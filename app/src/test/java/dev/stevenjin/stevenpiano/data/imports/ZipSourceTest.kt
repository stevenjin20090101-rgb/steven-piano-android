// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val midi = byteArrayOf(0x4D, 0x54, 0x68, 0x64)

    private fun zip(vararg entries: Pair<String, ByteArray>, charset: Charset = Charsets.UTF_8): File {
        val file = tmp.newFile()
        ZipOutputStream(file.outputStream(), charset).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `lists MIDI files, skips hidden ones, and reads INDEX csv from its folder`() {
        val index = "collection,composer,title,size_kb,path\nnocturnes,Chopin,Nocturne in E-flat,1,Chopin - Nocturne.mid\n"
        val file = zip(
            "ALL SONGS/Chopin - Nocturne.mid" to midi,
            "ALL SONGS/sub/Satie - Gymnopédie No. 1.MIDI" to midi,
            "__MACOSX/ALL SONGS/._Chopin - Nocturne.mid" to byteArrayOf(0),
            "ALL SONGS/.hidden.mid" to byteArrayOf(0),
            "ALL SONGS/readme.txt" to byteArrayOf(0),
            "ALL SONGS/INDEX.csv" to index.toByteArray(),
        )
        ZipSource(file).use { zip ->
            val items = zip.items()
            assertEquals(
                listOf("ALL SONGS/Chopin - Nocturne.mid", "ALL SONGS/sub/Satie - Gymnopédie No. 1.MIDI"),
                items.map { it.relativePath },
            )
            assertEquals("Chopin - Nocturne.mid", items[0].name)
            assertEquals("ALL SONGS/", zip.indexBase)
            val source = OpenedSource(items, zip.readIndex(), zip.indexBase)
            assertEquals("Nocturne in E-flat", source.rowFor(items[0])?.title)
            assertNull(source.rowFor(items[1]))
            assertArrayEquals(midi, items[0].open().use { it.readBytes() })
        }
    }

    @Test
    fun `names that are not UTF-8 are read as Latin-1`() {
        val file = zip("Satie - Gymnopédie.mid" to midi, charset = Charsets.ISO_8859_1)
        ZipSource(file).use { zip ->
            assertEquals(listOf("Satie - Gymnopédie.mid"), zip.items().map { it.relativePath })
            assertNull(zip.readIndex())
        }
    }

    @Test
    fun `a zip made for the import is deleted when closed`() {
        val file = zip("a.mid" to midi)
        ZipSource(file, deleteWhenClosed = true).close()
        assertEquals(false, file.exists())
    }
}
