// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

/**
 * What one import's paths say about its folders (DESIGN.md › v1.10.1, D1 and D2), its MIDI files' paths
 * relative to the zip or the folder chosen, '/'-separated: its [root], the single top-level folder every
 * file shares ("MIDI" in MIDI.zip), or null when they share none; and its artist folders, the folder that
 * directly holds a file when that lies below the root ("Coldplay" for `MIDI/Coldplay/Sparks.mid`; a file in
 * the root itself has none). Pure, so it is unit-tested; the repair of older uploads reads a root's pieces
 * through it too.
 */
class ImportFolders(paths: Collection<String>) {
    /** The single top-level folder every path lies in, or null. */
    val root: String? = paths.map { if ('/' in it) it.substringBefore('/') else "" }
        .let { firsts -> firsts.firstOrNull()?.takeIf { first -> first.isNotEmpty() && firsts.all { it == first } } }

    private val folders: List<String> = paths.mapNotNull(::artistFolderOf).distinct()

    /** The artist folders by their folded whole names ([ComposerNames.artistKey]): what [artistNamed] compares. */
    private val byName: Map<String, String> = folders.associateBy(ComposerNames::artistKey)

    /** The keys [ComposerNames.artist] gives the artist folders ("debussy" for "Claude Debussy", "coldplay"). */
    val artistKeys: Set<String> = folders.mapTo(HashSet()) { ComposerNames.artist(it).key }

    /** The artist folder that directly holds [path] below the root, or null (a file in the root, or no folder at all). */
    fun artistFolderOf(path: String): String? {
        val dirs = path.split('/').dropLast(1)
        val below = when {
            root == null -> dirs
            dirs.firstOrNull() == root -> dirs.drop(1)
            else -> return null
        }
        return below.lastOrNull()?.let(TitleHeuristics::cleanText)?.ifEmpty { null }
    }

    /**
     * The artist folder [name] names, spelt as the folder is ("Hans Zimmer" for "hans  zimmer"), or null
     * when it is none of this import's; names compare as [ComposerNames.artistKey] folds them.
     */
    fun artistNamed(name: String): String? = if (byName.isEmpty()) null else byName[ComposerNames.artistKey(name)]

    /** Whether [name] is one of this import's artist folders ([artistNamed]). */
    fun isArtist(name: String): Boolean = artistNamed(name) != null
}
