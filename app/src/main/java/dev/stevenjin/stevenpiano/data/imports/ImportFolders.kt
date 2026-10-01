// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import dev.stevenjin.stevenpiano.data.TextKeys

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

/**
 * Path order (DESIGN.md › v1.10.1, D2): the order an import's pieces go into its playlist, folder by
 * folder ("MIDI/Adele/…" before "MIDI/Coldplay/…"), then by the file's name without its extension, as a
 * file manager lists them by name: case and accents ignored ([TextKeys.fold]), numbers by their value
 * ("No. 2" before "No. 10"), a name before the longer ones it begins ("Fix You" before "Fix You (Live)").
 */
object PathOrder : Comparator<String> {
    private val MIDI_EXTENSION = Regex("\\.midi?$", RegexOption.IGNORE_CASE)

    override fun compare(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val c = natural(left[i], right[i])
            if (c != 0) return c
        }
        return if (left.size != right.size) left.size.compareTo(right.size) else a.compareTo(b)
    }

    private fun segments(path: String): List<String> {
        val parts = path.split('/')
        return parts.mapIndexed { i, part -> TextKeys.fold(if (i == parts.lastIndex) part.replace(MIDI_EXTENSION, "") else part) }
    }

    /** [a] against [b], their runs of digits compared by value. */
    private fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val endA = digitsEnd(a, i)
                val endB = digitsEnd(b, j)
                val numberA = a.substring(i, endA).trimStart('0')
                val numberB = b.substring(j, endB).trimStart('0')
                val c = if (numberA.length != numberB.length) numberA.length.compareTo(numberB.length) else numberA.compareTo(numberB)
                if (c != 0) return c
                i = endA
                j = endB
            } else {
                if (a[i] != b[j]) return a[i].compareTo(b[j])
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun digitsEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && text[end].isDigit()) end++
        return end
    }
}
