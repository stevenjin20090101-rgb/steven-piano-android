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
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.InputStream

/** What the person chose to import. */
sealed interface ImportSource {
    /** Files from the picker, the share sheet or "Open with". */
    data class Uris(val uris: List<Uri>) : ImportSource

    /** A folder from ACTION_OPEN_DOCUMENT_TREE, read recursively. */
    data class Tree(val treeUri: Uri) : ImportSource

    /** A zip file. */
    data class Zip(val uri: Uri) : ImportSource
}

/** One file to import. [relativePath] is its path inside the chosen folder or zip. */
class ImportItem(val name: String, val relativePath: String, val open: () -> InputStream)

/**
 * A source opened for reading: its MIDI files, and its INDEX.csv when it has one. [indexBase]
 * is the folder the index sits in; the index lists paths relative to it. Close when done.
 */
class OpenedSource(
    val items: List<ImportItem>,
    private val index: IndexCsv? = null,
    private val indexBase: String = "",
    private val release: () -> Unit = {},
) : Closeable {
    fun rowFor(item: ImportItem): IndexCsv.Row? {
        val csv = index ?: return null
        if (!item.relativePath.startsWith(indexBase, ignoreCase = true)) return null
        return csv.lookup(item.relativePath.substring(indexBase.length))
    }

    override fun close() = release()
}

fun isMidiName(name: String): Boolean = name.endsWith(".mid", ignoreCase = true) || name.endsWith(".midi", ignoreCase = true)

/** True for macOS resource forks and other hidden files that zips and folders carry along. */
fun isHiddenPath(path: String): Boolean =
    path.startsWith("__MACOSX/") || path.split('/').any { it.startsWith('.') }

/** The folder part of [path] with its trailing slash, "" at the root. */
fun folderOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

/** Lists what [source] holds. Blocking I/O; SAF grants belong to this process, so run it in-app. */
fun openSource(context: Context, source: ImportSource): OpenedSource {
    val resolver = context.contentResolver
    return when (source) {
        is ImportSource.Uris -> OpenedSource(
            source.uris.map { uri ->
                val name = displayName(resolver, uri)
                ImportItem(name, name) { resolver.openStream(uri) }
            },
        )
        is ImportSource.Tree -> {
            val listing = TreeWalker(resolver).walk(source.treeUri)
            val index = listing.index?.let { entry ->
                IndexCsv.parse(resolver.openStream(entry.uri).use { it.readBytes().toString(Charsets.UTF_8) })
            }
            OpenedSource(
                items = listing.files.map { entry -> ImportItem(entry.name, entry.relativePath) { resolver.openStream(entry.uri) } },
                index = index,
                indexBase = listing.index?.relativePath?.let(::folderOf).orEmpty(),
            )
        }
        is ImportSource.Zip -> {
            val zip = ZipSource(ZipSource.copyToCache(resolver, source.uri, context.cacheDir), deleteWhenClosed = true)
            try {
                OpenedSource(zip.items(), zip.readIndex(), zip.indexBase, zip::close)
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }
    }
}

private fun ContentResolver.openStream(uri: Uri): InputStream =
    openInputStream(uri) ?: throw FileNotFoundException("Can't open $uri")

private fun displayName(resolver: ContentResolver, uri: Uri): String =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled.mid"
