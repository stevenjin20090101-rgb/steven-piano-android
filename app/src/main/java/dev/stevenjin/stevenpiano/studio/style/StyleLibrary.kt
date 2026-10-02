// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.style

import dev.stevenjin.stevenpiano.channels.Channel
import dev.stevenjin.stevenpiano.channels.PoolMatcher
import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.data.builtin.BuiltInList
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.studio.compose.Mood

/** A channel or a built-in list an idea can name: its [key], its [name], its pieces ([ids]); [list] for a built-in list. */
data class CatalogueEntry(val key: String, val name: String, val ids: List<Long>, val list: Boolean)

/**
 * The library as the parser reads it (v1.12 — M30): built once per screen (and again when the library
 * changes), then every keystroke's parse reads it. Studio's own pieces, the tablet's recordings, pieces
 * under 15 seconds and pieces under 16 notes are left out: none of them is a seed. Pure, deterministic:
 * the pieces are taken in id order, so a shuffled library gives the same answers.
 */
class StyleLibrary(pieces: List<PieceEntity>, val catalogue: List<CatalogueEntry>) {
    val pieces: List<PieceEntity> = pieces
        .filter { it.composerKey != STUDIO_KEY && it.composerKey != RECORDED_KEY && it.durationMs >= MIN_MS && it.noteCount >= MIN_NOTES }
        .sortedBy { it.id }

    val byId: Map<Long, PieceEntity> = this.pieces.associateBy { it.id }

    /** Each piece's folded title as words between single spaces, padded (" clair de lune "): an n-gram is found on word boundaries. */
    private val titles: List<Pair<PieceEntity, String>> = this.pieces.map { it to padded(it.titleKey) }

    /** Each piece's folded composer text the same way, for performers named beside a composer (MAESTRO's "Georges Bizet Vladimir Horowitz"). */
    private val composerTexts: List<Pair<PieceEntity, String>> = this.pieces.map { it to padded(TextKeys.fold(it.composer)) }

    /** How many titles hold each word. */
    private val titleWords: Map<String, Int> = HashMap<String, Int>().also { counts ->
        for ((_, padded) in titles) padded.trim().split(' ').filter { it.isNotEmpty() }.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }

    private val composerWords: Set<String> = composerTexts.flatMapTo(HashSet()) { (_, text) -> text.trim().split(' ') }

    val composerKeys: Set<String> = this.pieces.mapTo(HashSet()) { it.composerKey }.apply { remove("") }

    /** Pieces in the Popular and Recognisable lists: they come first among equals. */
    private val famous: Set<Long> = catalogue.filter { it.list && (it.key == "popular" || it.key == "recognisable") }.flatMapTo(HashSet()) { it.ids }

    fun titleWordCount(word: String): Int = titleWords[word] ?: 0

    fun isTitleWord(word: String): Boolean = word in titleWords

    fun isComposerWord(word: String): Boolean = word in composerWords

    /** The pieces whose title holds [ngram] (folded words, single spaces) on word boundaries, best first for [mood]. */
    fun byTitle(ngram: String, mood: Mood, composer: String? = null): List<PieceEntity> {
        val needle = " $ngram "
        val rest = order(mood, composer)
        return titles.filter { (_, t) -> needle in t }
            .sortedWith(compareBy<Pair<PieceEntity, String>> { (_, t) -> if (t == needle) 0 else if (t.startsWith(needle)) 1 else 2 }.thenComparator { a, b -> rest.compare(a.first, b.first) })
            .map { it.first }
    }

    /** The pieces whose composer text holds [ngram] on word boundaries without it being their composer key (a performer). */
    fun byArtist(ngram: String): List<PieceEntity> {
        val needle = " $ngram "
        return composerTexts.filter { (p, t) -> needle in t && p.composerKey != ngram }.map { it.first }
    }

    fun byComposer(key: String): List<PieceEntity> = pieces.filter { it.composerKey == key }

    /** The library's own short name for composer [key] ("Chopin"). */
    fun composerName(key: String): String = pieces.firstOrNull { it.composerKey == key }?.let { it.composerShort.ifBlank { it.composer } } ?: key

    fun inCatalogue(key: String): List<PieceEntity> = catalogue.firstOrNull { it.key == key }?.ids.orEmpty().mapNotNull(byId::get)

    /**
     * The total order among candidates (plans/plan-studio.md (a); a title's exact and prefix matches come
     * first in [byTitle]): the composer also named; Popular or Recognisable; the note density nearest the
     * mood's; played last; then title and id.
     */
    fun order(mood: Mood, composer: String? = null): Comparator<PieceEntity> =
        compareBy<PieceEntity>(
            { p -> if (composer != null && p.composerKey == composer) 0 else 1 },
            { p -> if (p.id in famous) 0 else 1 },
            { p -> kotlin.math.abs(density(p) - targetDensity(mood)) },
            { p -> -(p.lastPlayedAt ?: 0L) },
            { p -> p.titleKey },
            { p -> p.id },
        )

    companion object {
        const val MIN_MS = 15_000L
        const val MIN_NOTES = 16
        val STUDIO_KEY: String = TextKeys.fold(ComposerNames.STUDIO)
        val RECORDED_KEY: String = TextKeys.fold(ComposerNames.RECORDED_LIVE)

        /** The library's channels and built-in lists for the parser: each pool over [pieces], the whole library's channel left out. */
        fun of(pieces: List<PieceEntity>, channels: List<Channel>, lists: List<BuiltInList>): StyleLibrary {
            val sorted = pieces.sortedBy { it.id }
            val entries = ArrayList<CatalogueEntry>()
            for (channel in channels) {
                if (channel.pool is PoolMatcher.All || TextKeys.fold(channel.key) in StyleVocabulary.notCatalogue) continue
                entries += CatalogueEntry(channel.key, channel.name, channel.pool(sorted), list = false)
            }
            for (list in lists) entries += CatalogueEntry(list.key, list.name, list.matches(sorted), list = true)
            return StyleLibrary(sorted, entries)
        }

        /** Notes a second: how busy a piece is, without reading its file. */
        fun density(p: PieceEntity): Double = if (p.durationMs <= 0) 0.0 else p.noteCount * 1000.0 / p.durationMs

        fun targetDensity(mood: Mood): Double = when (mood) {
            Mood.Calm -> 3.0
            Mood.Melancholy -> 3.5
            Mood.Bright -> 6.0
            Mood.Wild -> 9.0
        }

        /** [folded] text's words (as the parser cuts them) between single spaces, padded with a space at each end. */
        fun padded(folded: String): String = " " + StyleWords.cut(folded, maxChars = Int.MAX_VALUE).joinToString(" ") { it.key } + " "
    }
}
