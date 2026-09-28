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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a zip, an INDEX.csv or a crash's leftovers may cost an import (the v1.2 audit, F4). */
class ImportLimitsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** [size] zero bytes, read without holding them. */
    private fun zeros(size: Long): InputStream = object : InputStream() {
        var left = size

        override fun read(): Int = if (left-- > 0) 0 else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            left -= n
            return n
        }
    }

    @Test
    fun `the capped reader returns everything up to the cap, and nothing past it`() {
        assertArrayEquals(ByteArray(10), ImportLimits.readCapped(ByteArrayInputStream(ByteArray(10)), 10))
        assertNull(ImportLimits.readCapped(ByteArrayInputStream(ByteArray(11)), 10))
        assertNull(ImportLimits.readCapped(zeros(3L * 1024 * 1024 * 1024), ImportLimits.INDEX_BYTES))   // 3 GB: never buffered
    }

    @Test
    fun `a zip over the size cap is refused, declared or found out while copying, and its copy is deleted`() {
        val cache = tmp.newFolder("cache")
        val plenty = { Long.MAX_VALUE }
        for (declared in listOf(2_000L, null)) {
            try {
                ZipSource.copyToCache(zeros(2_000), cache, declared, maxBytes = 1_000, usableSpace = plenty)
                fail("declared $declared: over the cap")
            } catch (e: IOException) {
                assertEquals("The zip is larger than 0 MB.", e.message)
            }
        }
        assertEquals(0, cache.listFiles()!!.size)
        val copy = ZipSource.copyToCache(zeros(1_000), cache, null, maxBytes = 1_000, usableSpace = plenty)
        assertEquals(1_000L, copy.length())
        assertTrue(copy.name.startsWith("import-") && copy.name.endsWith(".zip"))
    }

    @Test
    fun `a zip is not copied without room for it and a margin, and a copy that fills the disk stops`() {
        val cache = tmp.newFolder("cache")
        val margin = ImportLimits.SPACE_MARGIN_BYTES
        try {
            ZipSource.copyToCache(zeros(1_000), cache, 1_000, usableSpace = { margin + 999 })
            fail("no room for the declared size plus the margin")
        } catch (e: IOException) {
            assertEquals("Not enough free space to read the zip.", e.message)
        }
        var free = margin + 20L * 1024 * 1024
        try {
            // Size unknown: the space is checked every 8 MB as the copy grows.
            ZipSource.copyToCache(object : InputStream() {
                val inner = zeros(64L * 1024 * 1024)
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) free -= it }
            }, cache, null, usableSpace = { free })
            fail("the disk filled up")
        } catch (e: IOException) {
            assertEquals("Not enough free space to read the zip.", e.message)
        }
        assertEquals(0, cache.listFiles()!!.size)
    }

    @Test
    fun `a zip with more than 20,000 entries is refused before they are listed`() {
        val file = tmp.newFile("many.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            repeat(ImportLimits.ZIP_ENTRIES + 1) {
                out.putNextEntry(ZipEntry("$it.mid"))
                out.closeEntry()
            }
        }
        try {
            ZipSource(file).close()
            fail("20,001 entries")
        } catch (e: IOException) {
            assertEquals("The zip holds 20001 entries; at most 20000 are read.", e.message)
        }
    }

    @Test
    fun `an INDEX csv over 2 MB is ignored, and the music still lists`() {
        val file = tmp.newFile("big-index.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("a.mid"))
            out.write(byteArrayOf(0x4D, 0x54, 0x68, 0x64))
            out.closeEntry()
            out.putNextEntry(ZipEntry("INDEX.csv"))
            val row = "c,Bach,Air,1,a.mid\n".toByteArray()
            repeat(ImportLimits.INDEX_BYTES / row.size + 1) { out.write(row) }
            out.closeEntry()
        }
        ZipSource(file).use { zip ->
            assertNull(zip.readIndex())
            assertEquals(listOf("a.mid"), zip.items().map { it.relativePath })
        }
    }

    @Test
    fun `stale import copies and partial files are swept, fresh and other files are kept`() {
        val cache = tmp.newFolder("cache")
        val files = tmp.newFolder("files")
        fun make(dir: File, name: String, modified: Long) = File(dir, name).apply {
            parentFile.mkdirs()
            writeText("x")
            setLastModified(modified)
        }
        val start = 1_800_000_000_000L
        val staleZip = make(cache, "import-123.zip", start - 60_000)
        val freshZip = make(cache, "import-456.zip", start + 1_000)
        val stalePiece = make(File(files, "pieces"), "abc.mid.part", start - 1)
        val staleArt = make(File(files, "art"), "composer-bach-1.jpg.part", start - 1)
        val staleCard = make(File(cache, "rollcards"), "v1-3.a8.gz.part", start - 1)
        val piece = make(File(files, "pieces"), "abc.mid", start - 60_000)
        val other = make(cache, "other.zip", start - 60_000)
        val staleUpload = make(File(cache, "web"), "upload-8812.zip", start - 5_000)
        val staleTemp = make(File(cache, "web"), "nano-1.tmp", start - 5_000)
        val freshUpload = make(File(cache, "web"), "upload-9001.zip", start + 500)
        assertEquals(6, ImportLimits.sweepStale(cache, files, before = start))
        listOf(staleZip, stalePiece, staleArt, staleCard, staleUpload, staleTemp).forEach { assertFalse(it.path, it.exists()) }
        listOf(freshZip, piece, other, freshUpload).forEach { assertTrue(it.path, it.exists()) }
    }
}
