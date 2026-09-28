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
 * - A composer is looked up by the full name the library knows for their key
 *   ([ComposerNames.canonical]), else by the name as shown. No name, or "Traditional" and the
 *   like, is not a person: not found, without a request. A disambiguation page is retried once as
 *   "{name} (composer)". For names outside the library's list of composers, a page is only taken
 *   when its description or text is about music, so a namesake's photograph never appears.
 * - A piece is searched for as "title composer"; the first hit that is not the composer's own
 *   page and whose extract names the composer's surname is its page. Text only: no piece images.
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
        val name = pageName(known ?: key.display) ?: return Fetched.NotFound
        var summary = call { api.summary(name) } ?: return Fetched.NotFound
        if (summary.isDisambiguation) {
            summary = call { api.summary("$name (composer)") }?.takeUnless { it.isDisambiguation } ?: return Fetched.NotFound
        }
        if (known == null && !summary.aboutMusic()) return Fetched.NotFound
        val image = summary.imageUrl?.let { url -> call { api.download(url, WikipediaClient.IMAGE_CAP) } }
        return Fetched.Found(summary.extract, summary.pageUrl, summary.title, image)
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

    /** Whether this page is about music: a composer's page always is; a namesake's is not. */
    private fun WikiSummary.aboutMusic(): Boolean {
        val text = TextKeys.fold("${description.orEmpty()} ${extract.orEmpty()}")
        return MUSIC_WORDS.any { it in text }
    }

    companion object {
        /** How long to wait when Wikimedia asks without saying for how long. */
        const val DEFAULT_WAIT_MS = 5_000L

        /** A longer wait is not waited out: the key is left for a later run. */
        const val MAX_WAIT_MS = 60_000L

        /** Composer names that are not a person with a page; "Made in Studio" is the app's own (v1.7 — M23). */
        private val NOT_PEOPLE = setOf("traditional", "anonymous", "anon", "unknown", "unknown composer", "various", "made in studio")

        private val MUSIC_WORDS = listOf(
            "compos", "music", "pianist", "songwriter", "conductor", "organist", "harpsichord", "violinist", "cellist", "singer",
        )

        /** The page name to ask for, or null when [name] is blank or not a person. */
        fun pageName(name: String): String? {
            val trimmed = name.trim()
            return trimmed.takeUnless { it.isEmpty() || TextKeys.fold(it) in NOT_PEOPLE }
        }
    }
}
