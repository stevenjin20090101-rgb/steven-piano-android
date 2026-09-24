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
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Lists a folder tree from ACTION_OPEN_DOCUMENT_TREE with one DocumentsContract query per
 * folder. (DocumentFile costs two queries per file, which takes minutes for 1,727 files.)
 */
class TreeWalker(private val resolver: ContentResolver) {
    class Entry(val uri: Uri, val name: String, val relativePath: String)

    /** The MIDI files, and the shallowest INDEX.csv. */
    class Listing(val files: List<Entry>, val index: Entry?)

    fun walk(treeUri: Uri): Listing {
        val files = mutableListOf<Entry>()
        val indexes = mutableListOf<Entry>()
        val folders = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri) to ""))
        while (folders.isNotEmpty()) {
            val (folderId, prefix) = folders.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
            resolver.query(children, COLUMNS, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val path = prefix + name
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    when {
                        isHiddenPath(path) -> Unit
                        cursor.getString(2) == Document.MIME_TYPE_DIR -> folders += id to "$path/"
                        name.equals(IndexCsv.FILE_NAME, ignoreCase = true) -> indexes += Entry(uri, name, path)
                        isMidiName(name) -> files += Entry(uri, name, path)
                    }
                }
            }
        }
        return Listing(files, indexes.minByOrNull { it.relativePath.count { c -> c == '/' } })
    }

    private companion object {
        val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    }
}
