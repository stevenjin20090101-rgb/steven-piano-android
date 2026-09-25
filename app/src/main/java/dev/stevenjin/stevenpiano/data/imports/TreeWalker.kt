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
import dev.stevenjin.stevenpiano.data.TextLimits
import java.util.concurrent.CancellationException

/**
 * Lists a folder tree from ACTION_OPEN_DOCUMENT_TREE with one DocumentsContract query per
 * folder. (DocumentFile costs two queries per file, which takes minutes for 1,727 files.)
 * The walk itself is [TreeWalk], bounded against a provider that loops or never ends.
 */
class TreeWalker(private val resolver: ContentResolver) {
    class Entry(val uri: Uri, val name: String, val relativePath: String)

    /** The MIDI files, and the shallowest INDEX.csv; [limited] says which cap cut the walk short, if one did. */
    class Listing(val files: List<Entry>, val index: Entry?, val limited: String? = null)

    /** Walks [treeUri]; [cancelled] is asked before each folder, and a cancelled walk throws [CancellationException]. */
    fun walk(treeUri: Uri, cancelled: () -> Boolean = { false }): Listing {
        val found = TreeWalk.walk(DocumentsContract.getTreeDocumentId(treeUri), cancelled) { folderId, visit ->
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
            resolver.query(children, COLUMNS, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (!visit(TreeWalk.Child(id, name, isFolder = cursor.getString(2) == Document.MIME_TYPE_DIR))) break
                }
            }
        }
        fun entry(doc: TreeWalk.Found) =
            Entry(DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.id), TextLimits.clip(doc.name, TextLimits.DISPLAY_NAME), doc.path)
        return Listing(found.files.map(::entry), found.index?.let(::entry), found.limited)
    }

    private companion object {
        val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    }
}

/**
 * A folder tree walked breadth first, without Android, over whatever lists a folder's children
 * (the documents provider; a fake in tests). A provider is another app, so the walk is bounded:
 * each folder is visited once (a folder listed inside itself, or twice, is not walked again), no
 * deeper than [ImportLimits.TREE_DEPTH], at most [ImportLimits.TREE_FILES] MIDI files and
 * [ImportLimits.TREE_FOLDERS] folders, [ImportLimits.TREE_ENTRIES] documents looked at in all,
 * and [cancelled] is asked before every folder. Past a cap the walk stops and says so.
 */
object TreeWalk {
    /** One document in a folder, as its provider lists it. */
    class Child(val id: String, val name: String, val isFolder: Boolean)

    /** A document found, with its path from the chosen folder. */
    class Found(val id: String, val name: String, val path: String)

    class Result(val files: List<Found>, val index: Found?, val limited: String?)

    /**
     * [list] calls its visitor with each child of a folder and stops early when the visitor
     * returns false. Returns the MIDI files and the shallowest INDEX.csv.
     */
    fun walk(rootId: String, cancelled: () -> Boolean, list: (folderId: String, visit: (Child) -> Boolean) -> Unit): Result {
        val files = ArrayList<Found>()
        val indexes = ArrayList<Found>()
        val visited = hashSetOf(rootId)
        val queue = ArrayDeque<Folder>()
        queue += Folder(rootId, "", 0)
        var folders = 1
        var looked = 0
        var limited: String? = null
        while (queue.isNotEmpty() && limited == null) {
            if (cancelled()) throw CancellationException("The import was cancelled")
            val folder = queue.removeFirst()
            list(folder.id) { child ->
                if (++looked > ImportLimits.TREE_ENTRIES) {
                    limited = "more than ${ImportLimits.TREE_ENTRIES} documents"
                    return@list false
                }
                val path = folder.prefix + child.name
                when {
                    isHiddenPath(path) -> Unit
                    child.isFolder -> when {
                        folder.depth + 1 > ImportLimits.TREE_DEPTH -> Unit   // too deep: not walked
                        !visited.add(child.id) -> Unit   // walked already: a loop, or a folder listed twice
                        folders >= ImportLimits.TREE_FOLDERS -> {
                            limited = "more than ${ImportLimits.TREE_FOLDERS} folders"
                            return@list false
                        }
                        else -> {
                            folders++
                            queue += Folder(child.id, "$path/", folder.depth + 1)
                        }
                    }
                    child.name.equals(IndexCsv.FILE_NAME, ignoreCase = true) -> indexes += Found(child.id, child.name, path)
                    isMidiName(child.name) -> {
                        if (files.size >= ImportLimits.TREE_FILES) {
                            limited = "more than ${ImportLimits.TREE_FILES} MIDI files"
                            return@list false
                        }
                        files += Found(child.id, child.name, path)
                    }
                }
                true
            }
        }
        return Result(files, indexes.minByOrNull { it.path.count { c -> c == '/' } }, limited)
    }

    private class Folder(val id: String, val prefix: String, val depth: Int)
}
