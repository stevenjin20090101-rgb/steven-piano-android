// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.data.imports.ImportFolders
import dev.stevenjin.stevenpiano.data.imports.ImportedPlaylist
import dev.stevenjin.stevenpiano.data.imports.PathOrder
import dev.stevenjin.stevenpiano.data.imports.TitleHeuristics

/**
 * The one-time repair of uploads imported before 1.10.1 (DESIGN.md › v1.10.1, D4), beside [TextRepair]:
 * a zip sent through the web panel, or picked on the tablet, arrived as titles without artists, in no
 * playlist. Run once at start, after the database opens and before the built-in playlists refresh
 * (AppGraph, `uploadRepairDone`). It takes the pieces with no collection, in no playlist, whose
 * `sourceName` has a folder, and none of Studio's ([Store.loosePieces]); groups them by the first part of
 * their path, the root of the zip they came in; and for a root of two pieces or more reads each one as
 * an import now would (D1: its artist folder; a `Title - Artist` name against that root's artist folders
 * and the canonical composers), fills a blank composer with its artist folder, corrects a piece whose
 * composer is still the left side of a reversed name (its title too), and puts them all in the playlist
 * named after the root (found or made), in path order. Names go through `named()`, so `searchText`,
 * `titleKey` and `composerShort` follow. At most [CHUNK] pieces a transaction; a second run finds no
 * piece to touch (they are all in a playlist then). Pieces with a collection, in any playlist, Studio's
 * and those whose `sourceName` has no folder are never touched.
 */
object UploadRepair {
    /** What the repair needs from the library (LibraryRepository; a fake in tests). */
    interface Store {
        /** The pieces with no collection, in no playlist, whose `sourceName` has a folder; never one made in Studio. */
        suspend fun loosePieces(): List<PieceEntity>

        /**
         * In one transaction: the [renamed] pieces' names and keys written, then [ids] linked in that order at
         * the end of the playlist called [name] (found by name or made: an import named it). Returns the playlist.
         */
        suspend fun repairChunk(name: String, renamed: List<PieceEntity>, ids: List<Long>): ImportedPlaylist?
    }

    /** One root's repair: the playlist's [name], its pieces in path order ([order]), and those whose names change ([renamed]). */
    data class Plan(val name: String, val order: List<Long>, val renamed: List<PieceEntity>)

    /** What one root's repair did: its playlist, how many pieces went into it, how many artists were filled or corrected. */
    data class Done(val playlist: ImportedPlaylist?, val linked: Int, val filled: Int)

    /** Pieces a transaction at most: SQLite's variable limit, as the library's own chunks. */
    const val CHUNK = 500

    /** The plans for [pieces] (as [Store.loosePieces] gives them), root by root in path order. Pure. */
    fun plan(pieces: List<PieceEntity>): List<Plan> =
        pieces.filter(::loose)
            .groupBy { it.sourceName.substringBefore('/') }
            .toSortedMap(PathOrder)
            .mapNotNull { (root, group) ->
                val name = TextLimits.clip(TitleHeuristics.cleanText(root), TextLimits.COLLECTION)
                if (name.isEmpty() || group.size < 2) return@mapNotNull null
                val folders = ImportFolders(group.map { it.sourceName })
                val ordered = group.sortedWith(compareBy(PathOrder) { it.sourceName })
                Plan(name, ordered.map { it.id }, ordered.mapNotNull { repaired(it, folders) })
            }

    /**
     * [piece] named as an import of its root reads it now (D1), or null when it keeps its names: a blank
     * composer takes its artist folder; a piece read from a reversed name whose composer is still that
     * name's left side (what 1.10 read), or blank, takes the right side as its artist and the left as its
     * title, a trailing parenthetical with it. Anything else (a composer of the person's own, a name read
     * the usual way) stays as it is.
     */
    fun repaired(piece: PieceEntity, folders: ImportFolders): PieceEntity? {
        val fileName = piece.sourceName.substringAfterLast('/')
        val meta = TitleHeuristics.metadata(fileName, null, emptyList(), folders.artistFolderOf(piece.sourceName), folders::artistNamed)
        val artist = ComposerNames.artist(TextLimits.clip(meta.composer, TextLimits.COMPOSER))
        val renamed = when (meta.source) {
            TitleHeuristics.Source.FOLDER -> if (piece.composer.isBlank()) piece.named(piece.title, artist) else null
            TitleHeuristics.Source.REVERSED -> {
                val left = TitleHeuristics.splitComposer(TitleHeuristics.baseName(fileName))?.first
                val asRead = left?.let { ComposerNames.normalize(TextLimits.clip(TitleHeuristics.cleanText(it), TextLimits.COMPOSER)).display }
                if (piece.composer.isBlank() || piece.composer == asRead) piece.named(meta.title, artist) else null
            }
            else -> null
        }
        return renamed?.takeIf { it != piece }
    }

    /**
     * Repairs what [store] holds, root by root, [CHUNK] pieces a transaction, and says so in [log] (the
     * link's trail): "Library: 265 pieces put in the playlist MIDI, 264 artists filled" ([named] gives the
     * playlist's part: its name in debug builds only, as the trail never names the person's folders).
     */
    suspend fun run(store: Store, log: (String) -> Unit, named: (String) -> String = { " $it" }): List<Done> =
        plan(store.loosePieces()).map { plan ->
            val renamed = plan.renamed.associateBy { it.id }
            var playlist: ImportedPlaylist? = null
            for (ids in plan.order.chunked(CHUNK)) {
                playlist = store.repairChunk(plan.name, ids.mapNotNull { renamed[it] }, ids) ?: playlist
            }
            log("Library: ${plan.order.size} pieces put in the playlist${named(playlist?.name ?: plan.name)}, ${plan.renamed.size} artists filled")
            Done(playlist, plan.order.size, plan.renamed.size)
        }

    /** A piece the repair may touch, whatever the store gave: no collection, a folder in its path, not Studio's. */
    private fun loose(piece: PieceEntity): Boolean =
        piece.collection.isNullOrBlank() && piece.sourceName.indexOf('/') > 0 && piece.composer != ComposerNames.STUDIO
}
