// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import dev.stevenjin.stevenpiano.data.TextLimits
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * Title, composer and collection for an imported file, from the best source available:
 * its INDEX.csv row, else a `Composer - Title` file name, else the file name alone.
 * A stub title such as `mz_311_1` gives way to Track 0's name when that reads like a real one.
 * A file name is read to [TextLimits.DISPLAY_NAME] characters (a zip entry's can be 64 KB).
 */
object TitleHeuristics {
    data class Metadata(val title: String, val composer: String, val collection: String?)

    private val STUB = Regex("^[a-z0-9_\\-.]+$")
    private val MIDI_EXTENSION = Regex("\\.midi?$", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")
    private val NOT_LETTERS = Regex("[^a-z ]")

    /** What a regex's `.` does not match: the line terminators. */
    private const val LINE_BREAKS = "\n\r\u0085\u2028\u2029"
    private val GENERIC_NAMES = setOf(
        "control track", "conductor", "conductor track", "tempo", "tempo track", "track", "new track",
        "untitled", "unbenannt", "sequence", "staff", "instrument", "copyright",
        "piano", "grand piano", "acoustic grand piano", "bright acoustic piano", "acoustic piano", "pianoforte",
        "klavier", "solo piano", "piano right", "piano left", "upper", "lower", "right", "left",
        "right hand", "left hand", "rh", "lh", "treble", "bass", "melody",
    )

    fun metadata(fileName: String, row: IndexCsv.Row?, sequenceNames: List<String>): Metadata {
        val base = cleanText(TextLimits.clip(fileName.replace(MIDI_EXTENSION, ""), TextLimits.DISPLAY_NAME))
        val (composer, title) = when {
            row != null -> row.composer to row.title.ifBlank { base }
            else -> splitComposer(base) ?: ("" to base)
        }
        return Metadata(
            title = betterTitle(cleanText(title), sequenceNames),
            composer = cleanText(composer),
            collection = row?.collection?.let(::cleanText)?.ifEmpty { null },
        )
    }

    /**
     * `Composer - Title` at the first " - " with something on both sides, or null: what the regex
     * `^(.+?) - (.+)$` matched, found with one linear search instead (that regex took quadratic time
     * on long names full of dashes). Like the regex's `.`, a line break anywhere means no match.
     */
    fun splitComposer(name: String): Pair<String, String>? {
        if (name.any { it in LINE_BREAKS }) return null
        val at = name.indexOf(" - ", startIndex = 1)
        if (at < 0 || at + 3 >= name.length) return null
        return name.substring(0, at).trim() to name.substring(at + 3).trim()
    }

    fun isStub(title: String): Boolean = STUB.matches(title)

    /** Track 0's names without the generic ones, joined; null unless that reads like a title (has a space). */
    fun trackTitle(sequenceNames: List<String>): String? =
        sequenceNames.map(::cleanText).filter { it.isNotEmpty() && !isGeneric(it) }.distinct()
            .joinToString(" — ")
            .takeIf { ' ' in it }

    fun isGeneric(name: String): Boolean {
        val words = NOT_LETTERS.replace(name.lowercase(), " ").trim().replace(SPACES, " ")
        return words.isEmpty() || words in GENERIC_NAMES
    }

    /** Mojibake repaired, Unicode composed, whitespace collapsed. */
    fun cleanText(text: String): String =
        Normalizer.normalize(repairMojibake(text), Normalizer.Form.NFC).replace(SPACES, " ").trim()

    /**
     * Undoes UTF-8 that was decoded as Latin-1 ("EspaÃ±a" -> "España"), as in the ALL SONGS names.
     * Only runs of U+0080..U+00FF that form valid UTF-8 are touched, so correct characters
     * elsewhere in the name, such as a real em dash, survive.
     */
    fun repairMojibake(text: String): String {
        if ('Ã' !in text && 'â' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i].code !in 0x80..0xFF) {
                out.append(text[i++])
                continue
            }
            var end = i
            while (end < text.length && text[end].code in 0x80..0xFF) end++
            val run = text.substring(i, end)
            out.append(strictUtf8(run.toByteArray(Charsets.ISO_8859_1)) ?: run)
            i = end
        }
        return out.toString()
    }

    private fun betterTitle(title: String, sequenceNames: List<String>): String =
        if (isStub(title)) trackTitle(sequenceNames) ?: title else title

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
