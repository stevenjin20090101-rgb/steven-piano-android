// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.net.WikiApi
import dev.stevenjin.stevenpiano.net.WikiBusyException
import dev.stevenjin.stevenpiano.net.WikiSummary
import dev.stevenjin.stevenpiano.net.WikipediaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/** How one fetch ended. */
sealed interface Fetched {
    /** A page was found: its text, where it came from, and (composers only) the portrait's bytes. */
    class Found(val description: String?, val sourceUrl: String?, val sourceTitle: String?, val image: ByteArray?) : Fetched

    /** Looked for and not there (or nothing to look for). */
    data object NotFound : Fetched

    /** A request failed; retried after a day. */
    data class Failed(val reason: String?) : Fetched

    /** Wikimedia asked to slow down twice running: nothing is recorded, and the worker waits [retryAfterMs]. */
    data class Busy(val retryAfterMs: Long) : Fetched
}

/**
 * Finds a composer's portrait and blurb, or a piece's notes, on English Wikipedia through [api]
 * (every call one paced request). Pure: no Android, so it is tested against a fake Wikipedia.
 *
 * - A composer the library knows by name ([ComposerNames.canonical]) is looked up by that full name.
 *   No name, or "Traditional" and the like, is not a person: not found, without a request. A
 *   disambiguation page is retried once as "{name} (composer)".
 * - Anyone else, an artist (v1.10.1 — M28, D5), by the name as shown; a page is only taken when it is
 *   about a band or a performer ([aboutPerformer]), so a namesake's photograph never appears. On a
 *   disambiguation page or a page about something else, "{name} (band)", "{name} (singer)", "{name}
 *   (musician)", then "{name} (composer)": the first page about music wins ("Queen" is "Queen (band)",
 *   "Passenger" "Passenger (singer)"). A joint name ("A & B", "A and B", "A feat. B", "A, B") whose own
 *   page finds nothing is looked up as A, the same way. At most [MAX_LOOKUPS] pages an artist; a
 *   company or anything else not about music ("Nintendo") is not found, and keeps its roll cards.
 * - A piece is searched for as "title composer"; the first hit that is not the composer's own
 *   page and whose extract names the composer's surname is its page. Text only: no piece images
 *   (album covers are not free).
 * - A failed request is a failure. Asked to wait (HTTP 429/503), the fetch waits as asked, once
 *   (up to [MAX_WAIT_MS]), then leaves the key for later ([Fetched.Busy]).
 */
class ArtworkFetcher(private val api: WikiApi, private val wait: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun fetch(key: ArtKey): Fetched = try {
        when (key) {
            is ArtKey.Composer -> composer(key)
            is ArtKey.Piece -> piece(key)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: WikiBusyException) {
        Fetched.Busy(e.retryAfterMs ?: DEFAULT_WAIT_MS)
    } catch (e: IOException) {
        Fetched.Failed(e.message)
    }

    private suspend fun composer(key: ArtKey.Composer): Fetched {
        val known = ComposerNames.canonical(key.composerKey)
        val summary = if (known != null) {
            val name = pageName(known) ?: return Fetched.NotFound
            val page = call { api.summary(name) } ?: return Fetched.NotFound
            if (page.isDisambiguation) call { api.summary("$name (composer)") }?.takeUnless { it.isDisambiguation } ?: return Fetched.NotFound else page
        } else {
            artistPage(pageName(key.display) ?: return Fetched.NotFound) ?: return Fetched.NotFound
        }
        val image = summary.imageUrl?.let { url -> call { api.download(url, WikipediaClient.IMAGE_CAP) } }
        return Fetched.Found(summary.extract, summary.pageUrl, summary.title, image)
    }

    /**
     * An artist's page (D5): [name]'s own, when it is about a band or a performer; else, past a
     * disambiguation page or a page about something else, the first of its [SUFFIXES] that is. A joint
     * name whose own page finds nothing is looked up as its first name ([firstOfJoint]), the same way. At
     * most [MAX_LOOKUPS] summaries in all; null when none of them is about music.
     */
    private suspend fun artistPage(name: String): WikiSummary? {
        var left = MAX_LOOKUPS
        suspend fun lookup(title: String): WikiSummary? {
            if (left <= 0) return null
            left--
            return call { api.summary(title) }
        }
        suspend fun find(name: String, suffixed: Boolean): WikiSummary? {
            val page = lookup(name) ?: return null   // no such page: nothing more to try under this name
            if (!page.isDisambiguation && page.aboutPerformer()) return page
            if (!suffixed) return null
            for (suffix in SUFFIXES) {
                if (left <= 0) return null
                val other = lookup("$name ($suffix)") ?: continue
                if (!other.isDisambiguation && other.aboutPerformer()) return other
            }
            return null
        }
        val first = firstOfJoint(name)
        return find(name, suffixed = first == null) ?: first?.let { find(it, suffixed = true) }
    }

    private suspend fun piece(key: ArtKey.Piece): Fetched {
        val composer = ComposerNames.normalize(key.composer)
        val composerName = pageName(ComposerNames.canonical(composer.key) ?: composer.display) ?: return Fetched.NotFound
        val surname = TextKeys.fold(composer.short)
        val title = key.title.trim()
        if (title.isEmpty() || surname.isEmpty()) return Fetched.NotFound
        val composerPage = TextKeys.fold(composerName)
        val notComposer = setOf(composerPage, "$composerPage (composer)")
        for (hit in call { api.search("$title $composerName") }) {
            if (TextKeys.fold(hit) in notComposer) continue
            val summary = call { api.summary(hit) } ?: continue
            if (summary.isDisambiguation || TextKeys.fold(summary.title) in notComposer) continue
            val extract = summary.extract ?: continue
            if (surname !in TextKeys.fold(extract)) continue
            return Fetched.Found(extract, summary.pageUrl, summary.title, image = null)
        }
        return Fetched.NotFound
    }

    /** [request], and when Wikimedia asks to wait, once more after waiting as asked. */
    private suspend fun <T> call(request: suspend () -> T): T = try {
        request()
    } catch (e: WikiBusyException) {
        val ms = e.retryAfterMs ?: DEFAULT_WAIT_MS
        if (ms > MAX_WAIT_MS) throw e
        wait(ms)
        request()
    }

    /**
     * Whether this page is about a band or a performer (D5): its description names one ("British rock band",
     * "German film score composer", "American singer-songwriter"); else, a description naming a work (an
     * album, a song, a film, a company) and no person's years is not; else the extract's first sentence
     * decides ("American indie game developer (born 1991)": "… is an American indie game developer and
     * composer."). So a soundtrack's page whose extract names a singer never stands for the singer.
     */
    private fun WikiSummary.aboutPerformer(): Boolean {
        val description = description.orEmpty()
        val described = words(description)
        if (names(described, PERFORMERS)) return true
        if (!PERSON.containsMatchIn(description) && WORKS.any { it in described }) return false
        return names(words(firstSentence(extract.orEmpty())), PERFORMERS)
    }

    companion object {
        /** How long to wait when Wikimedia asks without saying for how long. */
        const val DEFAULT_WAIT_MS = 5_000L

        /** A longer wait is not waited out: the key is left for a later run. */
        const val MAX_WAIT_MS = 60_000L

        /** Composer names that are not a person with a page; "Made in Studio" and "Recorded live" are the app's own (v1.7 — M23, v1.14 — M37). */
        private val NOT_PEOPLE = setOf("traditional", "anonymous", "anon", "unknown", "unknown composer", "various", "made in studio", "recorded live")

        /** Summaries one artist may cost at most (D5): the name and its four suffixes, or a joint name, then its first. */
        const val MAX_LOOKUPS = 5

        /** What an artist's page may be called besides their name, in the order they are tried. */
        val SUFFIXES = listOf("band", "singer", "musician", "composer")

        /** Words and phrases (folded) that make a page a band's or a performer's. */
        private val PERFORMERS = listOf(
            "band", "duo", "trio", "quartet", "group", "girl group", "boy band", "singer", "songwriter", "singer-songwriter", "musician",
            "multi-instrumentalist", "rapper", "dj", "disc jockey", "record producer", "music producer", "composer", "pianist", "guitarist",
            "drummer", "bassist", "vocalist", "violinist", "cellist", "organist", "harpsichordist", "conductor", "orchestra", "ensemble",
            "choir", "recording artist", "musical artist",
        )

        /** Words (folded) that make a page a work's or a company's, unless a performer is named too. */
        private val WORKS = setOf(
            "album", "song", "single", "soundtrack", "ep", "film", "movie", "musical", "opera", "video", "game", "franchise", "company",
            "corporation", "label", "television", "series", "episode", "novel", "book", "character", "brand",
        )

        private val NOT_WORD = Regex("[^\\p{L}\\p{N}\\-]+")
        private val JOINT = Regex("\\s*(?:&|,|\\band\\b|\\bfeat\\b\\.?|\\bft\\.|\\bfeaturing\\b)\\s*", RegexOption.IGNORE_CASE)

        /** The page name to ask for, or null when [name] is blank or not a person. */
        fun pageName(name: String): String? {
            val trimmed = name.trim()
            return trimmed.takeUnless { it.isEmpty() || TextKeys.fold(it) in NOT_PEOPLE }
        }

        /** The first name of a joint name ("Lady Gaga" of "Lady Gaga & Bradley Cooper", "A and B", "A feat. B", "A, B"), or null for one name. */
        fun firstOfJoint(name: String): String? {
            val at = JOINT.find(name) ?: return null
            return name.substring(0, at.range.first).trim().takeIf { it.isNotEmpty() }
        }

        /** [text]'s words, folded; hyphenated ones also as their parts ("singer-songwriter", "singer", "songwriter"). */
        private fun words(text: String): List<String> =
            TextKeys.fold(text).split(NOT_WORD).filter { it.isNotEmpty() }.flatMap { word -> if ('-' in word) listOf(word) + word.split('-') else listOf(word) }

        /** Whether [words] hold one of [terms], a phrase as its words in a row. */
        private fun names(words: List<String>, terms: List<String>): Boolean {
            val line = words.joinToString(" ", prefix = " ", postfix = " ")
            return terms.any { " $it " in line }
        }

        /** A person's years in a description: "(born 1991)", "(1862–1918)", "(c. 1690 – 1750)". */
        private val PERSON = Regex("\\bborn\\b|\\b\\d{3,4}\\s*[–—-]\\s*\\d{2,4}\\b", RegexOption.IGNORE_CASE)

        /** Where a sentence may end: a full stop, question or exclamation mark before a space. */
        private val SENTENCE_END = Regex("[.!?](?=\\s)")

        /** Words a full stop follows without ending the sentence. */
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "jr", "sr", "mt", "no", "op", "vol", "co", "ltd", "inc", "vs", "etc")

        /**
         * The extract's first sentence: up to the first full stop, question or exclamation mark followed by a
         * space, past initials and abbreviations ("Robert F. "Toby" Fox is …", "Nintendo Co., Ltd. is …").
         */
        fun firstSentence(text: String): String {
            for (end in SENTENCE_END.findAll(text)) {
                val at = end.range.first
                if (text[at] == '.') {
                    val word = text.substring(0, at).takeLastWhile { it.isLetter() }
                    if (word.length == 1 || word.lowercase() in ABBREVIATIONS) continue
                }
                return text.substring(0, at + 1)
            }
            return text
        }
    }
}
