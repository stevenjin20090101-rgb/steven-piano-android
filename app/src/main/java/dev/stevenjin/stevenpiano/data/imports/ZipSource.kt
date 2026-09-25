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
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * A zip read with [ZipFile]: random access, so an INDEX.csv stored after the music is still
 * found first (ZipInputStream is sequential). Entry names are read as UTF-8, flagged or not
 * (the library's zip does not set the flag); a zip whose names are not UTF-8 is read as Latin-1.
 * A zip with more than [ImportLimits.ZIP_ENTRIES] entries is refused before any is listed, and its
 * INDEX.csv is read only up to [ImportLimits.INDEX_BYTES].
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

    /** The INDEX.csv, or null when there is none or it is larger than [ImportLimits.INDEX_BYTES] (then it is ignored). */
    fun readIndex(): IndexCsv? = indexEntry?.let { entry ->
        zip.getInputStream(entry).use { ImportLimits.readCapped(it, ImportLimits.INDEX_BYTES) }?.let { IndexCsv.parse(it.toString(Charsets.UTF_8)) }
    }

    override fun close() {
        zip.close()
        if (deleteWhenClosed) file.delete()
    }

    companion object {
        private const val CHECK_SPACE_EVERY = 8L * 1024 * 1024

        private fun list(file: File, charset: Charset): Pair<ZipFile, List<ZipEntry>> {
            val zip = ZipFile(file, charset)
            return try {
                // The count comes from the central directory: nothing is listed yet.
                if (zip.size() > ImportLimits.ZIP_ENTRIES) throw IOException("The zip holds ${zip.size()} entries; at most ${ImportLimits.ZIP_ENTRIES} are read.")
                zip to zip.entries().toList()
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }

        /**
         * ZipFile needs a real file, so the picked document is copied into the cache first: refused
         * when it is (or turns out to be) larger than [ImportLimits.ZIP_BYTES], or when the copy would
         * leave less than [ImportLimits.SPACE_MARGIN_BYTES] free.
         */
        fun copyToCache(resolver: ContentResolver, uri: Uri, cacheDir: File): File {
            val declared = sizeOf(resolver, uri)
            return (resolver.openInputStream(uri) ?: throw FileNotFoundException("Can't open $uri")).use { copyToCache(it, cacheDir, declared) }
        }

        /** [copyToCache] from an open stream whose size may be known ([declaredSize]). Plain java.io, so it is unit-tested. */
        fun copyToCache(
            input: InputStream,
            cacheDir: File,
            declaredSize: Long?,
            maxBytes: Long = ImportLimits.ZIP_BYTES,
            usableSpace: () -> Long = { cacheDir.usableSpace },
        ): File {
            if (declaredSize != null && declaredSize > maxBytes) throw IOException(tooLarge(maxBytes))
            if (usableSpace() < (declaredSize ?: 0L) + ImportLimits.SPACE_MARGIN_BYTES) throw IOException(NO_SPACE)
            val copy = File.createTempFile("import-", ".zip", cacheDir)
            try {
                copy.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var sinceCheck = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException(tooLarge(maxBytes))
                        sinceCheck += n
                        if (sinceCheck >= CHECK_SPACE_EVERY) {
                            sinceCheck = 0
                            if (usableSpace() < ImportLimits.SPACE_MARGIN_BYTES) throw IOException(NO_SPACE)
                        }
                        out.write(buffer, 0, n)
                    }
                }
            } catch (e: Exception) {
                copy.delete()
                throw e
            }
            return copy
        }

        private const val NO_SPACE = "Not enough free space to read the zip."

        private fun tooLarge(maxBytes: Long) = "The zip is larger than ${maxBytes / (1024 * 1024)} MB."

        /** The document's size as its provider states it, or null when it does not say. */
        private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? = try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
            }
        } catch (e: RuntimeException) {   // a provider that cannot answer: the copy's own count still applies
            null
        }
    }
}
