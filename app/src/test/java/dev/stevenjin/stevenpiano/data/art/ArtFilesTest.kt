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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    @Test
    fun `names are readable, sanitised and never shared by two keys`() {
        val name = ArtFiles.nameFor("composer:saint-saens", "jpg")
        assertTrue(name, Regex("composer-saint-saens-[0-9a-f]{8}\\.jpg").matches(name))
        assertTrue(Regex("playlist-12-[0-9a-f]{8}\\.jpg").matches(ArtFiles.nameFor("playlist:12", "jpg")))
        assertTrue(Regex("composer-bach-cpe-[0-9a-f]{8}\\.png").matches(ArtFiles.nameFor("composer:bach cpe", "png")))
        // Keys in other scripts flatten alike; the checksum keeps them apart.
        val a = ArtFiles.nameFor("composer:чайковский", "jpg")
        val b = ArtFiles.nameFor("composer:рахманинов", "jpg")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("composer-"))
        assertFalse(ArtFiles.nameFor("composer:../../etc", "jpg").contains('/'))
        assertEquals(ArtFiles.nameFor("composer:x", "jpg"), ArtFiles.nameFor("composer:x", "jpg"))
    }

    @Test
    fun `the extension comes from the bytes`() {
        assertEquals("jpg", ArtFiles.extensionOf(jpeg))
        assertEquals("png", ArtFiles.extensionOf(png))
        assertEquals("gif", ArtFiles.extensionOf("GIF89a".toByteArray()))
        assertEquals("webp", ArtFiles.extensionOf("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals("img", ArtFiles.extensionOf("<html>".toByteArray()))
        assertEquals("img", ArtFiles.extensionOf(ByteArray(0)))
    }

    @Test
    fun `files live under art, by relative path, and are replaced whole`() {
        val files = ArtFiles(tmp.root)
        val path = files.write("composer:debussy", jpeg)
        assertTrue(path.startsWith("art/composer-debussy-"))
        assertArrayEquals(jpeg, files.file(path).readBytes())
        val again = files.write("composer:debussy", jpeg + jpeg)
        assertEquals(path, again)
        assertArrayEquals(jpeg + jpeg, files.file(path).readBytes())
        assertFalse(tmp.root.resolve("art").listFiles()!!.any { it.name.endsWith(".part") })
        files.delete(path)
        assertFalse(files.file(path).exists())
    }

    @Test
    fun `decoding samples by powers of two until the shorter side is within the size`() {
        assertEquals(2, BitmapCache.sampleSize(1024, 1300, ArtSize.Tile.px))
        assertEquals(2, BitmapCache.sampleSize(960, 1245, ArtSize.Tile.px))
        assertEquals(1, BitmapCache.sampleSize(500, 700, ArtSize.Tile.px))
        assertEquals(1, BitmapCache.sampleSize(512, 4000, ArtSize.Tile.px))
        assertEquals(8, BitmapCache.sampleSize(4000, 3000, ArtSize.Tile.px))
        assertEquals(8, BitmapCache.sampleSize(960, 1245, ArtSize.Row.px))
        assertEquals(1, BitmapCache.sampleSize(960, 1245, ArtSize.Full.px))
        assertEquals(1, BitmapCache.sampleSize(0, 0, ArtSize.Tile.px))
    }
}
