// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import android.content.ContentResolver
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * A zip read with [ZipFile]: random access, so an INDEX.csv stored after the music is still
 * found first (ZipInputStream is sequential). Entry names are read as UTF-8, flagged or not
 * (the library's zip does not set the flag); a zip whose names are not UTF-8 is read as Latin-1.
 */
class ZipSource(private val file: File, private val deleteWhenClosed: Boolean = false) : Closeable {
    private val zip: ZipFile
    private val entries: List<ZipEntry>

    init {
        // A name that is not UTF-8 fails either when the zip opens (ZipException) or while
        // listing (IllegalArgumentException), depending on the runtime.
        val listed = try {
            list(file, Charsets.UTF_8)
        } catch (e: ZipException) {
            list(file, Charsets.ISO_8859_1)
        } catch (e: IllegalArgumentException) {
            list(file, Charsets.ISO_8859_1)
        }
        zip = listed.first
        entries = listed.second.filter { !it.isDirectory && !isHiddenPath(it.name) }
    }

    private val indexEntry: ZipEntry? = entries
        .filter { it.name.substringAfterLast('/').equals(IndexCsv.FILE_NAME, ignoreCase = true) }
        .minByOrNull { it.name.count { c -> c == '/' } }

    /** The folder INDEX.csv sits in; its paths are relative to it. */
    val indexBase: String = indexEntry?.name?.let(::folderOf).orEmpty()

    fun items(): List<ImportItem> = entries.filter { isMidiName(it.name) }.map { entry ->
        ImportItem(entry.name.substringAfterLast('/'), entry.name) { zip.getInputStream(entry) }
    }

    fun readIndex(): IndexCsv? = indexEntry?.let { entry ->
        IndexCsv.parse(zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) })
    }

    override fun close() {
        zip.close()
        if (deleteWhenClosed) file.delete()
    }

    companion object {
        private fun list(file: File, charset: Charset): Pair<ZipFile, List<ZipEntry>> {
            val zip = ZipFile(file, charset)
            return try {
                zip to zip.entries().toList()
            } catch (e: IllegalArgumentException) {
                zip.close()
                throw e
            }
        }

        /** ZipFile needs a real file, so the picked document is copied into the cache first. */
        fun copyToCache(resolver: ContentResolver, uri: Uri, cacheDir: File): File {
            val copy = File.createTempFile("import-", ".zip", cacheDir)
            try {
                (resolver.openInputStream(uri) ?: throw FileNotFoundException("Can't open $uri")).use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                copy.delete()
                throw e
            }
            return copy
        }
    }
}
