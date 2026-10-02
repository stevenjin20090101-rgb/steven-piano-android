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
 * Modest composer normalization, so "chopin", "Chopin, F" and "Frédéric Chopin" group as one
 * composer. Pieces group by [Name.key], the ASCII-folded surname; rows show [Name.short].
 * Short forms (a bare surname, "Surname, I", "Surname IJ") of well-known composers take the
 * full name; full names are kept as written. MAESTRO lists transcriptions as
 * "Composer Arranger" ("Franz Schubert Franz Liszt"): the first person is the composer.
 */
object ComposerNames {
    data class Name(val display: String, val short: String, val key: String) {
        companion object {
            val Unknown = Name("", "", "")
        }
    }

    /** Full names by folded surname: the library's composers and the piano-midi.de folder names. */
    private val CANONICAL: Map<String, String> = listOf(
        "Isaac Albéniz", "Charles-Valentin Alkan", "Johann Sebastian Bach", "Mily Balakirev", "Béla Bartók",
        "Ludwig van Beethoven", "Alban Berg", "Georges Bizet", "Alexander Borodin", "Johannes Brahms",
        "Friedrich Burgmüller", "Ferruccio Busoni", "Frédéric Chopin", "Muzio Clementi", "Claude Debussy",
        "Antonín Dvořák", "Gabriel Fauré", "César Franck", "George Gershwin", "Mikhail Glinka",
        "Leopold Godowsky", "Enrique Granados", "Edvard Grieg", "George Frideric Handel", "Joseph Haydn",
        "Leoš Janáček", "Scott Joplin", "Franz Liszt", "Felix Mendelssohn", "Moritz Moszkowski",
        "Wolfgang Amadeus Mozart", "Modest Mussorgsky", "Johann Pachelbel", "Sergei Prokofiev", "Henry Purcell",
        "Sergei Rachmaninoff", "Jean-Philippe Rameau", "Maurice Ravel", "Camille Saint-Saëns", "Erik Satie",
        "Domenico Scarlatti", "Franz Schubert", "Robert Schumann", "Alexander Scriabin", "Christian Sinding",
        "Pyotr Ilyich Tchaikovsky", "Traditional",
    ).associateBy { TextKeys.fold(it.substringAfterLast(' ')) }

    /** Other spellings of a folded surname. */
    private val VARIANTS = mapOf(
        "rachmaninow" to "rachmaninoff", "rachmaninov" to "rachmaninoff", "rakhmaninov" to "rachmaninoff",
        "balakirew" to "balakirev", "burgmueller" to "burgmuller", "tschaikowsky" to "tchaikovsky",
        "tschaikowski" to "tchaikovsky", "tchaikowsky" to "tchaikovsky", "chaikovsky" to "tchaikovsky",
        "skrjabin" to "scriabin", "skriabin" to "scriabin", "mussorgski" to "mussorgsky",
        "moussorgsky" to "mussorgsky", "musorgsky" to "mussorgsky", "haendel" to "handel", "xmas" to "traditional",
    )

    /** Pianists and transcribers who appear after the composer in MAESTRO's composer field. */
    private val ARRANGERS = listOf(
        "Vladimir Horowitz", "Egon Petri", "Myra Hess", "Alfred Grünfeld", "Mikhail Pletnev",
        "György Cziffra", "Vyacheslav Gryaznov",
    )

    private val KNOWN_PEOPLE: List<List<String>> =
        (CANONICAL.values + ARRANGERS).map { TextKeys.fold(it).split(' ') }.filter { it.size >= 2 }

    private val INITIALS = Regex("^(?:[A-Z]{1,3}|(?:[A-Z]\\.){1,3})$")
    private val ROMAN_NUMERALS = setOf("II", "III", "IV", "VI", "VII", "VIII", "IX")
    private val SUFFIXES = setOf("jr", "jr.", "sr", "sr.")
    private val NOT_KEY_CHARS = Regex("[^\\p{L}\\p{N} \\-]")
    private val SPACES = Regex(" {2,}")

    /** Given names written as initials ("W.", "J.S.", "JS"): each letter must begin one of the composer's given names. */
    private val GIVEN_INITIALS = Regex("^(?:\\p{L}\\.?){1,3}$")

    /**
     * The full name of a well-known composer from their [key] (a folded surname, or another
     * spelling of one): "debussy" is "Claude Debussy", "rachmaninow" "Sergei Rachmaninoff", "xmas"
     * "Traditional". Null for anyone else, and for keys with initials that did not fit ("bach
     * cpe"). Artwork asks Wikipedia for this name.
     */
    fun canonical(key: String): String? = CANONICAL[VARIANTS[key] ?: key]

    /**
     * The composer of a piece made in the app's Studio (v1.7 — M23): not a person, so it keeps its
     * whole name everywhere, rows included ("Made in Studio · 3:05", not "Studio · 3:05").
     */
    const val STUDIO = "Made in Studio"
    private val STUDIO_KEY = TextKeys.fold(STUDIO)

    /**
     * The composer of a recording made on the tablet (v1.11 — M29): not a person either, so it keeps its whole
     * name everywhere, as [STUDIO] does ("Recorded live · 0:42").
     */
    const val RECORDED_LIVE = "Recorded live"
    private val RECORDED_LIVE_KEY = TextKeys.fold(RECORDED_LIVE)

    fun normalize(raw: String): Name {
        val text = TitleHeuristics.cleanText(raw)
        if (text.isEmpty()) return Name.Unknown
        if (TextKeys.fold(text) == STUDIO_KEY) return Name(STUDIO, STUDIO, STUDIO_KEY)
        if (TextKeys.fold(text) == RECORDED_LIVE_KEY) return Name(RECORDED_LIVE, RECORDED_LIVE, RECORDED_LIVE_KEY)
        val parsed = parse(text)
        val surnameKey = keyOf(parsed.surname)
        val key = VARIANTS[surnameKey] ?: surnameKey
        val canonical = CANONICAL[key]
        return when {
            key.isEmpty() -> Name(text, text, keyOf(text))
            canonical != null && parsed.shortForm && initialsFit(parsed.initials, canonical) -> canonicalName(canonical)
            canonical != null && parsed.shortForm -> Name(text, titleCase(parsed.surname), "$key ${keyOf(parsed.initials)}")
            canonical != null && TextKeys.fold(text) == TextKeys.fold(canonical) -> canonicalName(canonical)
            else -> Name(if (text == text.lowercase()) titleCase(text) else text, titleCase(parsed.surname), key)
        }
    }

    /**
     * An artist's name (v1.10.1 — M28, D3): the folder an upload keeps an artist's pieces in, or the known
     * side of a `Title - Artist` file name. A canonical composer's name is that composer ([canonicalOf]:
     * "Claude Debussy" and "Erik Satie" join Debussy and Satie). Anyone else keeps the name as written
     * (mojibake repaired, NFC, trimmed, spaces collapsed) with no surname logic: the key is the whole
     * folded name ([artistKey]: "ed sheeran", "louis armstrong" apart from "craig armstrong", "coldplay",
     * "c418") and so is the short name rows show ("Ed Sheeran · 3:54", as "Made in Studio · 3:05"), so
     * "Twenty One Pilots", "The Weeknd" and "Lady Gaga & Bradley Cooper" stay whole.
     */
    fun artist(raw: String): Name {
        val text = TitleHeuristics.cleanText(raw)
        if (text.isEmpty()) return Name.Unknown
        if (TextKeys.fold(text) == STUDIO_KEY) return Name(STUDIO, STUDIO, STUDIO_KEY)
        if (TextKeys.fold(text) == RECORDED_LIVE_KEY) return Name(RECORDED_LIVE, RECORDED_LIVE, RECORDED_LIVE_KEY)
        canonicalOf(text)?.let { return it }
        return Name(text, text, artistKey(text))
    }

    /**
     * A composer named by a `Composer - Title` file name or typed in Rename (D3): [normalize]'s name as
     * always, unless it is no canonical composer's and its whole folded name ([artistKey]) is already a key
     * ([isKey]: in the library, or among the artist folders of the same import), when it is that artist's
     * ([artist]): so one artist never gets two keys ("Ed Sheeran - Perfect.mid" joins the pieces from an
     * "Ed Sheeran" folder rather than starting a "sheeran" of its own). A name whose whole key is its
     * surname key anyway ("Anonymous") is [normalize]'s, so no 1.10 library reads differently.
     */
    suspend fun resolve(raw: String, isKey: suspend (String) -> Boolean): Name {
        val name = normalize(raw)
        if (name.key.isEmpty() || canonicalOf(raw) != null) return name
        val whole = artistKey(raw)
        return if (whole != name.key && isKey(whole)) artist(raw) else name
    }

    /**
     * The key an artist's name groups by ([artist]): the whole name folded, without punctuation, its
     * spaces collapsed ("Lady Gaga & Bradley Cooper" is "lady gaga bradley cooper"); a name of
     * punctuation alone keeps its folded self rather than no key.
     */
    fun artistKey(raw: String): String {
        val text = TitleHeuristics.cleanText(raw)
        return keyOf(text).replace(SPACES, " ").ifEmpty { TextKeys.fold(text) }
    }

    /**
     * The canonical composer [raw] names, as [normalize] gives them, or null when it names someone else:
     * the full name ("Claude Debussy"), a short form ("Debussy", "Chopin, F", "Bach JS"), or the surname
     * (or another spelling of it) after given names of the composer's own or their initials ("Pyotr
     * Tchaikovsky", "W. A. Mozart", "Sergei Rachmaninov"). "Andrew Berg" and "Janis Joplin" are not
     * Alban Berg and Scott Joplin, though [normalize] groups them by surname.
     */
    fun canonicalOf(raw: String): Name? {
        val text = TitleHeuristics.cleanText(raw)
        if (text.isEmpty()) return null
        val name = normalize(text)
        val full = CANONICAL[name.key] ?: return null
        if (name.display == full) return name
        val tokens = text.split(' ')
        if (tokens.size < 2) return null
        val surname = keyOf(tokens.last())
        if ((VARIANTS[surname] ?: surname) != name.key) return null   // "Franz Schubert Franz Liszt": the last word is not the composer
        val given = TextKeys.fold(full).split(' ').dropLast(1)
        val fits = tokens.dropLast(1).all { token ->
            val folded = TextKeys.fold(token)
            folded in given || (GIVEN_INITIALS.matches(token) && folded.filter(Char::isLetter).all { letter -> given.any { it.first() == letter } })
        }
        return if (fits) canonicalName(full) else null
    }

    private class Parsed(val surname: String, val initials: String, val shortForm: Boolean)

    private fun parse(text: String): Parsed {
        val comma = text.indexOf(',')
        if (comma > 0) {   // "Abt, F"
            return Parsed(text.substring(0, comma).trim(), text.substring(comma + 1).filter(Char::isLetter), shortForm = true)
        }
        val tokens = text.split(' ')
        val rest = tokens.drop(1)
        if (rest.isEmpty() || rest.all { INITIALS.matches(it) && it !in ROMAN_NUMERALS }) {   // "chopin", "Bach JS"
            return Parsed(tokens[0], rest.joinToString("").filter(Char::isLetter), shortForm = true)
        }
        val person = withoutArranger(tokens).dropLastWhile { it in ROMAN_NUMERALS || it.lowercase() in SUFFIXES }
        return Parsed(person.lastOrNull() ?: tokens.last(), "", shortForm = false)
    }

    /** "Franz Schubert Franz Liszt" -> "Franz Schubert", when at least two words remain. */
    private fun withoutArranger(tokens: List<String>): List<String> {
        val folded = tokens.map(TextKeys::fold)
        val arranger = KNOWN_PEOPLE.firstOrNull { it.size <= tokens.size - 2 && folded.takeLast(it.size) == it }
        return if (arranger == null) tokens else tokens.dropLast(arranger.size)
    }

    /** "JS" fits Johann Sebastian Bach; "CPE" does not (that is C. P. E. Bach). */
    private fun initialsFit(initials: String, canonical: String): Boolean =
        initials.isEmpty() || initials.first().equals(canonical.first(), ignoreCase = true)

    private fun canonicalName(canonical: String): Name {
        val surname = canonical.substringAfterLast(' ')
        return Name(canonical, surname, keyOf(surname))
    }

    private fun keyOf(text: String): String = NOT_KEY_CHARS.replace(TextKeys.fold(text), "").trim()

    private fun titleCase(text: String): String =
        if (text != text.lowercase()) text
        else text.split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }
}
