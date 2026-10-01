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
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.stevenjin.stevenpiano.data.TextLimits
import java.io.Closeable
import java.io.File
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

    /**
     * A zip the app saved in its own storage (v1.10 — M27: Steven's library, downloaded and checked by
     * `LibraryPack`): read where it lies, never copied, and deleted when the import closes it
     * ([openLocalZip]). Pieces whose INDEX.csv row gives a SHA-256 in [skipShas] are left out: an update
     * of the library brings only the pieces it never offered before.
     */
    class LocalZip(val file: File, val skipShas: Set<String> = emptySet()) : ImportSource
}

/** One file to import. [relativePath] is its path inside the chosen folder or zip. */
class ImportItem(val name: String, val relativePath: String, val open: () -> InputStream)

/**
 * Where an import puts its pieces besides the playlists its INDEX.csv names (DESIGN.md › v1.10.1, D2):
 * every piece of the batch that no INDEX row places, those already there included, in path order.
 */
sealed interface ImportBatch {
    /** Files picked one by one on the tablet, a piece kept from Studio, Steven's library: no playlist. */
    data object None : ImportBatch

    /** A zip or a folder: the playlist named after its root folder ([ImportFolders.root]), else [name], the zip's or the folder's own. */
    data class Named(val name: String) : ImportBatch

    /** A loose MIDI file sent through the web panel: the standing playlist [UPLOADS], made when first needed. */
    data object Uploads : ImportBatch

    companion object {
        /** The standing playlist of loose uploads, a playlist of the person's like any other (renamed or deleted, it is made again). */
        const val UPLOADS = "Uploads"

        private val ZIP_EXTENSION = Regex("\\.zip$", RegexOption.IGNORE_CASE)

        /** The batch of a zip called [fileName]: named after the zip without its extension (or its root folder). */
        fun zip(fileName: String): Named = Named(fileName.trim().replace(ZIP_EXTENSION, ""))
    }
}

/**
 * A source opened for reading: its MIDI files, and its INDEX.csv when it has one. [indexBase]
 * is the folder the index sits in; the index lists paths relative to it. [limited] says what cut
 * a folder's listing short, when a cap did (see [TreeWalk]). [batch] says which playlist the pieces
 * go to (v1.10.1). Close when done.
 */
class OpenedSource(
    val items: List<ImportItem>,
    private val index: IndexCsv? = null,
    private val indexBase: String = "",
    private val release: () -> Unit = {},
    val limited: String? = null,
    val batch: ImportBatch = ImportBatch.None,
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
    path.startsWith("__MACOSX/") || path.split('/').any { it.startsWith('.') || it == MAC_FOLDER }

/**
 * True for what a Mac adds to a zip or a folder beside the music (DESIGN.md › v1.10.1, D1): anything
 * in a `__MACOSX` folder, and the AppleDouble `._name` files that copy a file's resource fork. The
 * importer skips them before it counts anything, wherever they come from (a zip, a folder, files
 * picked or sent through the web panel): never a piece, never a failure.
 */
fun isMacMetadata(path: String): Boolean {
    val segments = path.split('/')
    return segments.any { it == MAC_FOLDER } || segments.last().startsWith("._")
}

private const val MAC_FOLDER = "__MACOSX"

/** The folder part of [path] with its trailing slash, "" at the root. */
fun folderOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

/**
 * Lists what [source] holds. Blocking I/O; SAF grants belong to this process, so run it in-app.
 * [cancelled] is asked as a folder tree is walked. An INDEX.csv over [ImportLimits.INDEX_BYTES]
 * is ignored.
 */
fun openSource(context: Context, source: ImportSource, cancelled: () -> Boolean = { false }): OpenedSource {
    val resolver = context.contentResolver
    return when (source) {
        is ImportSource.Uris -> OpenedSource(
            source.uris.map { uri ->
                val name = displayName(resolver, uri)
                ImportItem(name, name) { resolver.openStream(uri) }
            },
        )
        is ImportSource.Tree -> {
            val listing = TreeWalker(resolver).walk(source.treeUri, cancelled)
            val index = listing.index?.let { entry ->
                resolver.openStream(entry.uri).use { ImportLimits.readCapped(it, ImportLimits.INDEX_BYTES) }?.let { IndexCsv.parse(it.toString(Charsets.UTF_8)) }
            }
            OpenedSource(
                items = listing.files.map { entry -> ImportItem(entry.name, entry.relativePath) { resolver.openStream(entry.uri) } },
                index = index,
                indexBase = listing.index?.relativePath?.let(::folderOf).orEmpty(),
                limited = listing.limited?.let { "The folder holds $it; the rest was left out." },
                batch = ImportBatch.Named(treeName(resolver, source.treeUri)),
            )
        }
        is ImportSource.Zip -> {
            val name = displayName(resolver, source.uri)
            val zip = ZipSource(ZipSource.copyToCache(resolver, source.uri, context.cacheDir), deleteWhenClosed = true)
            try {
                OpenedSource(zip.items(), zip.readIndex(), zip.indexBase, zip::close, batch = ImportBatch.zip(name))
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }
        is ImportSource.LocalZip -> openLocalZip(source)
    }
}

/**
 * [source]'s zip opened where it lies (v1.10 — M27), with the caps every zip has ([ZipSource]: the
 * entry count, the index's size), its MIDI files less those whose index row gives a SHA-256 in
 * [ImportSource.LocalZip.skipShas] (a file with no row, or no SHA-256 in it, is always read: the
 * importer's own hash still keeps one copy of each piece). Closing it deletes the zip; a zip that
 * can't be opened is left for the caller. Plain java.io, so it is unit-tested.
 */
fun openLocalZip(source: ImportSource.LocalZip): OpenedSource {
    val zip = ZipSource(source.file, deleteWhenClosed = true)
    try {
        val index = zip.readIndex()
        val base = zip.indexBase
        val items = zip.items().filter { item ->
            source.skipShas.isEmpty() || index == null || !item.relativePath.startsWith(base, ignoreCase = true) ||
                index.lookup(item.relativePath.substring(base.length))?.sha256 !in source.skipShas
        }
        return OpenedSource(items, index, base, zip::close)
    } catch (e: Exception) {
        zip.close()
        throw e
    }
}

private fun ContentResolver.openStream(uri: Uri): InputStream =
    openInputStream(uri) ?: throw FileNotFoundException("Can't open $uri")

/**
 * The name of the folder [treeUri] chose, as its provider gives it, else the last part of its document id
 * ("primary:Music/MIDI" is "MIDI"); cut to [TextLimits.DISPLAY_NAME] characters. A folder import's playlist
 * takes it when its files share no root folder (v1.10.1, D2).
 */
private fun treeName(resolver: ContentResolver, treeUri: Uri): String {
    val id = DocumentsContract.getTreeDocumentId(treeUri)
    val named = try {
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (e: RuntimeException) {   // a provider that cannot answer: the id's last part will do
        null
    }
    return TextLimits.clip(named ?: id.substringAfterLast(':').substringAfterLast('/'), TextLimits.DISPLAY_NAME)
}

/** The name the sending app gives the file, cut to [TextLimits.DISPLAY_NAME] characters (another app chose it). */
private fun displayName(resolver: ContentResolver, uri: Uri): String {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled.mid"
    return TextLimits.clip(name, TextLimits.DISPLAY_NAME)
}
