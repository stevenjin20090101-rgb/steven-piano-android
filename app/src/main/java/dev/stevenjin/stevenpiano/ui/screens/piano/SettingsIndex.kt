// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.runtime.Immutable
import dev.stevenjin.stevenpiano.piano.PianoFold
import dev.stevenjin.stevenpiano.piano.PianoRow
import dev.stevenjin.stevenpiano.piano.PianoSection
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.ui.SettingsPage
import java.text.Normalizer
import java.util.Locale

/** A screen outside the Piano tab that a setting moved to (v1.13 — M31b), and the path its result names. */
enum class Elsewhere(val path: List<String>) {
    /** Now playing's View menu (v1.12 — M31a): what shows (the score, the notes or both), the notes' style and their options. */
    NowPlayingView(listOf("Now playing", "View")),

    /** The Studio tab (v1.12 — M30): its models (in a sheet there), composing and transcribing. */
    Studio(listOf("Studio")),

    /** The Library's channels: a channel's own volume (long-press its card). */
    LibraryChannels(listOf("Library", "Channels")),
}

/** Where a search result leads. */
@Immutable
sealed interface SettingsTarget {
    /** [page] of the Piano tab, scrolled to [anchor] (null: its top) with [fold] opened first. */
    data class Row(val page: SettingsPage, val anchor: String?, val fold: PianoFold? = null) : SettingsTarget

    /** A screen elsewhere in the app. */
    data class Away(val place: Elsewhere) : SettingsTarget
}

/** One thing search can find: its [label] as its row shows it, its [path] (the result's eyebrow), where it leads, and its [synonyms]. */
@Immutable
data class SettingsEntry(val label: String, val path: List<String>, val target: SettingsTarget, val synonyms: List<String> = emptyList()) {
    /** "The piano › Sound and touch › Fine tuning" (the eyebrow sets it in capitals). */
    val eyebrow: String get() = path.joinToString(" › ")

    internal val labelWords: Set<String> = SettingsIndex.words(label).toSet()
    internal val otherWords: Set<String> = synonyms.flatMap(SettingsIndex::words).toSet()
}

/**
 * The Piano tab's search (DESIGN.md › v1.13 — M31b), pure: every page (its hub row), every row of every
 * page, and what moved elsewhere, built from the same tables the pages draw from ([HubGroups],
 * [PianoSettings]' rows, [PageRows]) so a row can't be on a page and missing here. Matching is on folded
 * words (case, accents and "²" set aside; "Wi-Fi" is "wi", "fi" and "wifi") and prefixes: every word typed
 * must begin a word of the label or of a synonym. Hits on the label come before hits on a synonym, each in
 * the hub's order.
 */
object SettingsIndex {
    /** What a search with no hit says. */
    const val NO_MATCH = "No setting matches."

    /** The search field's placeholder: one search, its scope in its words. */
    const val PLACEHOLDER = "Search settings"

    /** Results shown at most: a word of two letters can match a great many rows. */
    const val MAX_RESULTS = 50

    /** Where a row of the piano's pages is anchored: its setting's or fact's name, or the row's own; null for a note. */
    fun anchorOf(row: PianoRow): String? = when (row) {
        is PianoRow.Control -> row.setting.name
        is PianoRow.Reading -> row.fact.name
        PianoRow.Presets -> "presets"
        PianoRow.StrikeTest -> "strike-test"
        PianoRow.TestLed -> "test-led"
        PianoRow.ReadStatus -> "read-status"
        PianoRow.SaveNow -> "save-now"
        PianoRow.KeyForceNote -> null
    }

    /**
     * Every entry: each page, then its rows, page by page in the hub's order; then what moved elsewhere. A piano
     * page's rows come from the table, section by section, with the rows its page draws itself ([PageRows]: Check
     * for piano updates) after their section's.
     */
    val entries: List<SettingsEntry> by lazy {
        buildList {
            for (group in HubGroups.all) {
                for (page in group.rows.filterIsInstance<HubRow.Page>().map { it.page }) {
                    add(SettingsEntry(page.title, listOf(group.title), SettingsTarget.Row(page, anchor = null), PAGE_SYNONYMS[page].orEmpty()))
                    val piano = page.piano
                    if (piano != null) {
                        for (section in PianoSettings.sections(piano)) {
                            for (row in PianoSettings.rows(section)) pianoEntry(row, section, page, group.title)?.let(::add)
                            if (section.title != null) addAll(appRows(page, group.title, section.title))
                        }
                    } else {
                        addAll(appRows(page, group.title, null))
                    }
                }
            }
            for ((label, place, synonyms) in MOVED) add(SettingsEntry(label, place.path, SettingsTarget.Away(place), synonyms))
        }
    }

    /** What [query] finds, best first; none for a blank query. [midiPiano]: Steven Piano's own pages are hidden, and so are their rows. */
    fun search(query: String, midiPiano: Boolean = false): List<SettingsEntry> {
        val typed = words(query)
        if (typed.isEmpty()) return emptyList()
        val shown = if (midiPiano) entries.filterNot { (it.target as? SettingsTarget.Row)?.page?.piano != null } else entries
        val onLabel = shown.filter { entry -> typed.all { word -> entry.labelWords.any { it.startsWith(word) } } }
        val onSynonym = shown.filter { entry -> entry !in onLabel && typed.all { word -> (entry.labelWords + entry.otherWords).any { it.startsWith(word) } } }
        return (onLabel + onSynonym).take(MAX_RESULTS)
    }

    /** [text] folded for matching: lower case, accents and superscripts set aside ("I²C" is "i2c", "Krüger" is "kruger"). */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(Locale.ROOT)

    /** [text]'s words, folded: split at anything not a letter or digit, and each hyphenated or apostrophed word also whole ("wifi", "stevens"). */
    fun words(text: String): List<String> {
        val folded = fold(text)
        val parts = folded.split(NOT_WORD).filter { it.isNotEmpty() }
        val joined = folded.split(SPACE).map { it.replace(NOT_WORD, "") }.filter { it.isNotEmpty() && it !in parts }
        return (parts + joined).distinct()
    }

    private fun appRows(page: SettingsPage, group: String, section: String?): List<SettingsEntry> =
        PageRows.all.filter { it.page == page && (page.piano == null || it.section == section) }
            .map { row -> SettingsEntry(row.label, listOfNotNull(group, page.title, row.section), SettingsTarget.Row(page, row.anchor), row.synonyms) }

    private fun pianoEntry(row: PianoRow, section: PianoSection, page: SettingsPage, group: String): SettingsEntry? {
        val anchor = anchorOf(row) ?: return null
        val label = when (row) {
            is PianoRow.Control -> row.setting.label
            is PianoRow.Reading -> row.fact.label
            PianoRow.Presets -> "Presets"
            PianoRow.StrikeTest -> "Strike test"
            PianoRow.TestLed -> "Test LED"
            PianoRow.ReadStatus -> READ_STATUS
            PianoRow.SaveNow -> SAVE_TO_PIANO
            PianoRow.KeyForceNote -> return null
        }
        val path = listOfNotNull(group, page.title, section.fold?.title ?: section.title)
        return SettingsEntry(label, path, SettingsTarget.Row(page, anchor, section.fold), PIANO_SYNONYMS[anchor].orEmpty())
    }

    /** The piano's two rows that act, as their buttons say. */
    const val READ_STATUS = "Read status"
    const val SAVE_TO_PIANO = "Save to the piano now"

    /** What else a page is called, or what people look for on it. */
    private val PAGE_SYNONYMS: Map<SettingsPage, List<String>> = mapOf(
        SettingsPage.Feel to listOf("feel", "touch", "dynamics"),
        SettingsPage.Lighting to listOf("lighting", "LEDs", "lights"),
        SettingsPage.Firmware to listOf("version", "status"),
        SettingsPage.Remote to listOf("remote control", "web control", "browser"),
        SettingsPage.Guests to listOf("requests", "visitors"),
        SettingsPage.Display to listOf("theme", "look"),
        SettingsPage.Updates to listOf("upgrade", "new version"),
        SettingsPage.Artwork to listOf("Wikipedia", "portraits"),
        SettingsPage.Help to listOf("credits", "licences", "diagnostics"),
    )

    /** Words people use for the piano's rows, by anchor. */
    private val PIANO_SYNONYMS: Map<String, List<String>> = mapOf(
        "presets" to PianoSettings.presets.map { it.label },
        "fullpower" to listOf("no dynamics", "loudest"),
        "volume" to listOf("loudness", "master volume"),
        "velcurve" to listOf("dynamics", "touch curve"),
        "velmult" to listOf("dynamics", "louder", "softer"),
        "min" to listOf("minimum", "quietest"),
        "minblack" to listOf("minimum", "quietest"),
        "max" to listOf("maximum", "loudest"),
        "keyforce_white" to listOf("key force"),
        "keyforce_black" to listOf("key force"),
        "humanvel" to listOf("humanise", "humanize", "random"),
        "humantime" to listOf("humanise", "humanize", "random"),
        "burstboost" to listOf("fast notes", "runs"),
        "hold" to listOf("coils", "sustain"),
        "restrike" to listOf("repeat", "held notes"),
        "freq" to listOf("PWM", "hum", "coils"),
        "leds" to listOf("LEDs", "lights"),
        "ledmode" to listOf("rainbow", "reactive", "static"),
        "ledbright" to listOf("LEDs", "lights"),
        "reactcolor" to listOf("colours", "colors", "fire", "ocean"),
        "ledcount" to listOf("LEDs", "length"),
        "dimsecs" to listOf("display", "dim"),
        "dimfloor" to listOf("display", "dim"),
        "pedalon" to listOf("damper"),
        "pedalhalf" to listOf("half pedal", "damper"),
        "fw" to listOf("version"),
        "boards" to listOf("drivers", "octaves"),
        "i2cfails" to listOf("errors", "bus"),
        "uptime" to listOf("running time"),
        "read-status" to listOf("health", "report", "diagnostics"),
        "save-now" to listOf("save", "store", "keep settings"),
        "test-led" to listOf("line up", "align"),
        "strike-test" to listOf("test a key", "floor", "ceiling"),
    )

    /**
     * What moved off the Piano tab (v1.12), so a search for the old place still finds the new one: the View menu's
     * Show (which, with the divider, replaced Wide layout), the notes' style, Fingering, Chord names, Hand colours;
     * Studio's models, composing and transcribing; a channel's volume.
     */
    private val MOVED: List<Triple<String, Elsewhere, List<String>>> = listOf(
        Triple("Show", Elsewhere.NowPlayingView, listOf("score and notes", "notes only", "score only", "wide layout", "split", "divider")),
        Triple("Note display", Elsewhere.NowPlayingView, listOf("paper roll", "falling notes", "waterfall", "score", "sheet music")),
        Triple("Fingering", Elsewhere.NowPlayingView, listOf("finger numbers")),
        Triple("Chord names", Elsewhere.NowPlayingView, listOf("chords", "harmony")),
        Triple("Hand colours", Elsewhere.NowPlayingView, listOf("hand colors", "left hand", "right hand")),
        Triple("Models", Elsewhere.Studio, listOf("AI", "transcription", "composing", "download")),
        Triple("Compose a piece", Elsewhere.Studio, listOf("AI", "generate", "write music")),
        Triple("Transcribe a recording", Elsewhere.Studio, listOf("AI", "audio", "recording")),
        Triple("Channel volume", Elsewhere.LibraryChannels, listOf("channels", "set volume")),
    )

    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACE = Regex("\\s+")
}
