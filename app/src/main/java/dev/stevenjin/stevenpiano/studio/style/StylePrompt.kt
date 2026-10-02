// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.style

import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.PromptBuilder
import java.util.Locale
import kotlin.math.roundToInt

/** A word of an idea: [text] as typed (to echo it under "Not used"), [key] folded as the tables are. */
class StyleWord(val text: String, val key: String)

/** Cuts an idea into words (v1.12 — M30). Pure; no regular expression is ever built from what is typed. */
object StyleWords {
    /**
     * [raw] cleaned (control characters become spaces, format characters such as direction marks go), cut to
     * [StylePrompt.MAX_CHARS] code points, then into at most [max] words: letters and digits, with `#`, `♯` and
     * `♭` kept, an apostrophe between letters, and `:` or `.` between digits ("2:30", "2.5").
     */
    fun cut(raw: String, max: Int = Int.MAX_VALUE, maxChars: Int = StylePrompt.MAX_CHARS): List<StyleWord> {
        val clean = StringBuilder()
        var i = 0
        var points = 0
        while (i < raw.length && points < maxChars) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) || Character.getType(cp) == Character.CONTROL.toInt() -> clean.append(' ')
                Character.getType(cp) == Character.FORMAT.toInt() || Character.getType(cp) == Character.SURROGATE.toInt() -> continue
                else -> clean.appendCodePoint(cp)
            }
            points++
        }
        val text = clean.toString()
        val words = ArrayList<StyleWord>()
        var start = -1
        var at = 0
        fun close(end: Int) {
            if (start >= 0) {
                val original = text.substring(start, end).trimEnd('\'', '’', ':', '.')
                val key = keyOf(original)
                if (key.isNotEmpty() && words.size < max) words += StyleWord(original, key)
                start = -1
            }
        }
        while (at < text.length) {
            val cp = text.codePointAt(at)
            val width = Character.charCount(cp)
            val prev = if (at > 0) text.codePointBefore(at) else -1
            val next = if (at + width < text.length) text.codePointAt(at + width) else -1
            val inWord = Character.isLetterOrDigit(cp) || isMark(cp) || cp == '#'.code || cp == '♯'.code || cp == '♭'.code ||
                ((cp == '\''.code || cp == '’'.code) && prev >= 0 && Character.isLetter(prev) && next >= 0 && Character.isLetter(next)) ||
                ((cp == ':'.code || cp == '.'.code) && prev >= 0 && Character.isDigit(prev) && next >= 0 && Character.isDigit(next))
            if (inWord) {
                if (start < 0) start = at
            } else {
                close(at)
            }
            at += width
        }
        close(text.length)
        return words
    }

    private fun isMark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
    }

    /** A word folded: lowercase, accents gone, ♯ and ♭ as `#` and `b`, a possessive's "'s" and other apostrophes dropped. */
    fun keyOf(word: String): String {
        var key = TextKeys.fold(word).replace('♯', '#').replace('♭', 'b').replace('’', '\'')
        if (key.endsWith("'s")) key = key.dropLast(2)
        return key.replace("'", "")
    }
}

/** A tempo asked for: [Exact] bpm, a [Class] of tempo (its target bpm, held near the seed's own), or a [Scale] of the last turn's. */
sealed interface TempoAsk {
    data class Exact(val bpm: Int) : TempoAsk

    data class Class(val bpm: Int, val word: String) : TempoAsk

    data class Scale(val factor: Double) : TempoAsk
}

/**
 * Where the seed comes from: a [Title] (the folded words found in titles), a [Pool] of the library ([source]
 * is `composer:<key>`, `artist:<words>`, `form:<key>`, `composer:<key>+form:<key>`, `channel:<key>`,
 * `list:<key>` or `mood:<mood>`; [label] is how the line says it), a [Piece] chosen in the sheet, or the
 * [Default] (the piece played last).
 */
sealed interface SeedAsk {
    data class Title(val words: String, val label: String) : SeedAsk

    data class Pool(val source: String, val label: String) : SeedAsk

    data class Piece(val pieceId: Long) : SeedAsk

    data object Default : SeedAsk
}

/**
 * What an idea asks for: a [mood]; a [key] (null: the seed's own), or only a mode ([minor]); a [tempo] (null:
 * the seed's own); a length in [minutes] (1–5); the [seed]; and [variant], how many times "different" asked
 * for another seed piece.
 */
data class StyleSpec(
    val mood: Mood = Mood.Calm,
    val key: MusicKey? = null,
    val minor: Boolean? = null,
    val tempo: TempoAsk? = null,
    val minutes: Int = DEFAULT_MINUTES,
    val seed: SeedAsk = SeedAsk.Default,
    val variant: Int = 0,
) {
    /** The spec as one line of `name=value` pairs, as the history keeps it (no typed text but a title's folded words). */
    fun encode(): String = buildList {
        add("mood=${mood.name}")
        key?.let { add("key=${it.tonic}${if (it.minor) "m" else ""}") }
        minor?.let { add("mode=${if (it) "minor" else "major"}") }
        when (val t = tempo) {
            is TempoAsk.Exact -> add("bpm=${t.bpm}")
            is TempoAsk.Class -> add("class=${t.bpm}:${t.word}")
            is TempoAsk.Scale -> add("scale=${t.factor}")
            null -> Unit
        }
        add("min=$minutes")
        when (val s = seed) {
            is SeedAsk.Title -> add("title=${s.words.replace(SEP, ' ')}|${s.label.replace(SEP, ' ')}")
            is SeedAsk.Pool -> add("pool=${s.source.replace(SEP, ' ')}|${s.label.replace(SEP, ' ')}")
            is SeedAsk.Piece -> add("piece=${s.pieceId}")
            SeedAsk.Default -> Unit
        }
        if (variant != 0) add("variant=$variant")
    }.joinToString(SEP.toString())

    companion object {
        const val DEFAULT_MINUTES = 2
        private const val SEP = ';'

        /** [encode]'s line back; null when it is not one (a row from before, or damaged). */
        fun decode(line: String?): StyleSpec? {
            if (line.isNullOrBlank()) return null
            var spec = StyleSpec()
            for (pair in line.split(SEP)) {
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                spec = when (name) {
                    "mood" -> Mood.entries.firstOrNull { it.name == value }?.let { spec.copy(mood = it) } ?: spec
                    "key" -> value.removeSuffix("m").toIntOrNull()?.takeIf { it in 0..11 }?.let { spec.copy(key = MusicKey(it, value.endsWith("m"))) } ?: spec
                    "mode" -> spec.copy(minor = value == "minor")
                    "bpm" -> value.toIntOrNull()?.let { spec.copy(tempo = TempoAsk.Exact(it.coerceIn(PromptBuilder.MIN_BPM, PromptBuilder.MAX_BPM))) } ?: spec
                    "class" -> value.substringBefore(':').toIntOrNull()?.let { spec.copy(tempo = TempoAsk.Class(it, value.substringAfter(':'))) } ?: spec
                    "scale" -> value.toDoubleOrNull()?.takeIf { it in 0.1..10.0 }?.let { spec.copy(tempo = TempoAsk.Scale(it)) } ?: spec
                    "min" -> value.toIntOrNull()?.let { spec.copy(minutes = it.coerceIn(PromptBuilder.MIN_MINUTES, PromptBuilder.MAX_MINUTES)) } ?: spec
                    "title" -> spec.copy(seed = SeedAsk.Title(value.substringBefore('|'), value.substringAfter('|')))
                    "pool" -> spec.copy(seed = SeedAsk.Pool(value.substringBefore('|'), value.substringAfter('|')))
                    "piece" -> value.toLongOrNull()?.let { spec.copy(seed = SeedAsk.Piece(it)) } ?: spec
                    "variant" -> value.toIntOrNull()?.let { spec.copy(variant = it.coerceAtLeast(0)) } ?: spec
                    else -> spec
                }
            }
            return spec
        }
    }
}

/** The turn an idea may refine: what it asked ([spec]) and what it came to ([bpm], the piece's tempo; [seedPieceId], its seed). */
data class PreviousTurn(val spec: StyleSpec, val bpm: Int? = null, val seedPieceId: Long? = null)

/** One fact understood, with the words it came from (their places in the idea). */
data class Understood(val kind: String, val label: String, val words: List<Int>)

/**
 * What an idea came to: its [spec]; the seed [candidates] (at most eight, best first); the facts [understood];
 * the [line] the screen shows ("Calm · D minor · slow · 2 min · in the manner of Clair de lune (Debussy)"; for
 * a refinement, what changed: "Slower: 52 bpm"); the person's own words it did not use ([unused], in order, at
 * most twelve); whether it refines the last turn ([refinement]) and asks for it [again].
 */
data class StyleResult(
    val spec: StyleSpec,
    val candidates: List<Long>,
    val understood: List<Understood>,
    val line: String,
    val unused: List<String>,
    val refinement: Boolean,
    val again: Boolean = false,
)

/**
 * Studio's idea box, understood by keywords (v1.12 — M30; there is no text model): an idea is cut into words
 * ([StyleWords]) and claimed in passes, a word never twice: fixed patterns (a length, a tempo in bpm, a key),
 * the library's titles (six words down to two, then a distinctive single word), the library's names
 * (composers, then performers), forms and the catalogue (channels and built-in lists), moods, tempo words and
 * comparatives; words that carry nothing go silently; the rest are "Not used". A negator ("not", "no",
 * "without", "less") sends the next content word there. An idea that names no title, name, form or catalogue
 * word refines the last turn ([PreviousTurn]); one that does starts afresh from Calm and two minutes.
 * Deterministic, bounded (200 code points, 40 words, n-grams of six), no recursion, and nothing typed ever
 * becomes a regular expression.
 */
object StylePrompt {
    const val MAX_CHARS = 200
    const val MAX_WORDS = 40
    const val MAX_NGRAM = 6
    const val MAX_CANDIDATES = 8
    const val MAX_UNUSED = 12

    /** A single title word counts only this long, and only if it is in at most this many titles. */
    private const val TITLE_WORD_MIN = 5
    private const val TITLE_WORD_MAX_TITLES = 12

    fun parse(text: String, library: StyleLibrary, previous: PreviousTurn? = null): StyleResult = Parse(StyleWords.cut(text, MAX_WORDS), library, previous).run()

    private class Parse(val words: List<StyleWord>, val library: StyleLibrary, val previous: PreviousTurn?) {
        val n = words.size
        val claimed = BooleanArray(n)
        val blocked = BooleanArray(n)
        val understood = ArrayList<Understood>()
        val keys = words.map { it.key }

        var minutes: Double? = null
        var clamped: String? = null
        var tempo: TempoAsk? = null
        var key: MusicKey? = null
        var minor: Boolean? = null
        var mood: Mood? = null
        var moodStep = 0   // −1 calmer, +1 wilder
        var lengthStep = 0
        var title: Pair<String, List<PieceEntity>>? = null
        var composer: String? = null
        var artist: String? = null
        var form: StyleForm? = null
        var catalogue: CatalogueEntry? = null
        var again = false
        var different = false
        val changes = ArrayList<String>()

        fun claim(kind: String, label: String, from: Int, count: Int = 1) {
            for (i in from until from + count) claimed[i] = true
            understood += Understood(kind, label, (from until from + count).toList())
        }

        fun free(i: Int, count: Int = 1): Boolean = (i until i + count).all { it in 0 until n && !claimed[it] && !blocked[it] }

        fun run(): StyleResult {
            patterns()
            titles()
            negators()
            names()
            forms()
            catalogue()
            moods()
            return result()
        }

        // ---- P1: lengths, bpm, keys ------------------------------------------------------------------------

        fun setMinutes(value: Double, from: Int, count: Int) {
            if (minutes != null) return   // the first stands; a later one is not used
            val whole = (value + 0.5).toInt()
            val held = whole.coerceIn(PromptBuilder.MIN_MINUTES, PromptBuilder.MAX_MINUTES)
            clamped = when {
                value > PromptBuilder.MAX_MINUTES -> "the longest"
                value < PromptBuilder.MIN_MINUTES -> "the shortest"
                else -> null
            }
            minutes = held.toDouble()
            claim("length", "$held min", from, count)
        }

        fun patterns() {
            var i = 0
            while (i < n) {
                val w = keys[i]
                val next = keys.getOrNull(i + 1)
                when {
                    MSS.matches(w) -> MSS.find(w)!!.let { m -> setMinutes(m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / 60.0, i, 1) }
                    NUMBER_MIN.matches(w) -> setMinutes(NUMBER_MIN.find(w)!!.groupValues[1].toDouble(), i, 1)
                    NUMBER_SEC.matches(w) -> setMinutes(NUMBER_SEC.find(w)!!.groupValues[1].toDouble() / 60.0, i, 1)
                    NUMBER_BPM.matches(w) -> setTempo(TempoAsk.Exact(NUMBER_BPM.find(w)!!.groupValues[1].toInt()), i, 1)
                    numberOf(w) != null && next != null && (next in StyleVocabulary.minuteWords || next in StyleVocabulary.secondWords || next in StyleVocabulary.hourWords) -> {
                        val value = numberOf(w)!!
                        val minutesValue = when (next) {
                            in StyleVocabulary.minuteWords -> value
                            in StyleVocabulary.secondWords -> value / 60.0
                            else -> value * 60.0
                        }
                        setMinutes(minutesValue, i, 2)
                        i++
                    }
                    PLAIN.matches(w) && next == "bpm" -> {
                        setTempo(TempoAsk.Exact(w.toInt()), i, 2)
                        i++
                    }
                    w in StyleVocabulary.lengths -> setMinutes(StyleVocabulary.lengths.getValue(w).toDouble(), i, 1)
                    else -> i += keyAt(i) - 1
                }
                i++
            }
            // A mode on its own: "something minor".
            for (j in 0 until n) if (!claimed[j] && keys[j] in StyleVocabulary.modes && minor == null && key == null) {
                minor = StyleVocabulary.modes.getValue(keys[j])
                claim("mode", if (minor == true) "minor" else "major", j)
            }
        }

        fun numberOf(w: String): Double? = w.toDoubleOrNull()?.takeIf { DECIMAL.matches(w) } ?: StyleVocabulary.numbers[w]?.toDouble()

        fun setTempo(ask: TempoAsk, from: Int, count: Int) {
            if (tempo != null) return
            tempo = when (ask) {
                is TempoAsk.Exact -> TempoAsk.Exact(ask.bpm.coerceIn(PromptBuilder.MIN_BPM, PromptBuilder.MAX_BPM))
                else -> ask
            }
            claim("tempo", tempoLabel(tempo!!), from, count)
        }

        /** A key at word [i] ("D minor", "f# minor", "C♯m", "B flat", "in E"): the words it took (1 when none). */
        fun keyAt(i: Int): Int {
            val m = TONIC.find(keys[i]) ?: return 1
            if (claimed[i]) return 1
            val letter = m.groupValues[1][0]
            var accidental = when (m.groupValues[2]) {
                "#" -> 1
                "b" -> -1
                else -> 0
            }
            var minorAsked: Boolean? = if (m.groupValues[3] == "m") true else null
            var used = 1
            if (accidental == 0 && i + used < n && keys[i + used] in StyleVocabulary.accidentals && free(i + used)) {
                accidental = StyleVocabulary.accidentals.getValue(keys[i + used])
                used++
            }
            if (minorAsked == null && i + used < n && keys[i + used] in StyleVocabulary.modes && free(i + used)) {
                minorAsked = StyleVocabulary.modes.getValue(keys[i + used])
                used++
            }
            val afterIn = i > 0 && keys[i - 1] == "in"
            val valid = minorAsked != null || accidental != 0 || (afterIn && letter != 'a')
            // "am" is a word, not A minor; a bare "b" or "a" is a word too.
            if (!valid || (keys[i] == "am" && used == 1)) return 1
            if (key != null) return used   // the first key stands
            val tonic = Math.floorMod(PITCH.getValue(letter) + accidental, 12)
            key = MusicKey(tonic, minorAsked ?: false)
            claim("key", key!!.label, i, used)
            return used
        }

        // ---- P2: titles ------------------------------------------------------------------------------------

        fun titles() {
            val mood = moodHint()
            for (size in minOf(MAX_NGRAM, n) downTo 2) {
                var i = 0
                while (i + size <= n) {
                    if (title == null && free(i, size)) {
                        val gram = (i until i + size).map { keys[it] }
                        // A title needs a word of its own: not a vocabulary word, a number or a name ("chopin nocturne" is a composer and a form).
                        val content = gram.any { it !in StyleVocabulary.allWords && it !in library.composerKeys && !library.isComposerWord(it) }
                        if (content && gram.all(library::isTitleWord)) {
                            val found = library.byTitle(gram.joinToString(" "), mood, composerHint())
                            if (found.isNotEmpty()) {
                                title = gram.joinToString(" ") to found
                                claim("title", titleLabel(found.first()), i, size)
                                return
                            }
                        }
                    }
                    i++
                }
            }
            for (i in 0 until n) {
                val w = keys[i]
                if (!free(i) || w.length < TITLE_WORD_MIN || w in StyleVocabulary.allWords || w.any(Char::isDigit)) continue
                if (w in library.composerKeys || library.isComposerWord(w)) continue   // a name is P3's
                val count = library.titleWordCount(w)
                if (count in 1..TITLE_WORD_MAX_TITLES) {
                    val found = library.byTitle(w, mood, composerHint())
                    if (found.isNotEmpty()) {
                        title = w to found
                        claim("title", titleLabel(found.first()), i)
                        return
                    }
                }
            }
        }

        /** The mood the words name, before they are claimed: it orders title matches by density. */
        fun moodHint(): Mood = keys.firstNotNullOfOrNull { StyleVocabulary.moods[it] } ?: Mood.Calm

        /** A composer named anywhere in the idea, to break ties between titles. */
        fun composerHint(): String? = keys.firstOrNull { it in library.composerKeys }

        // ---- negators ------------------------------------------------------------------------------------

        fun negators() {
            for (i in 0 until n) {
                if (claimed[i] || keys[i] !in StyleVocabulary.negators) continue
                var j = i + 1
                while (j < n && keys[j] in StyleVocabulary.intensifiers && !claimed[j]) j++
                if (j < n && !claimed[j]) blocked[j] = true
            }
        }

        // ---- P3: composers and performers ------------------------------------------------------------------

        fun names() {
            for (size in minOf(3, n) downTo 1) {
                for (i in 0..n - size) {
                    if (!free(i, size)) continue
                    val gram = (i until i + size).map { keys[it] }
                    if (size == 1 && (gram[0] in StyleVocabulary.allWords || gram[0].any(Char::isDigit))) continue
                    if (size > 1 && gram.any { it in StyleVocabulary.allWords || it.any(Char::isDigit) }) continue
                    val text = (i until i + size).joinToString(" ") { words[it].text }
                    val composerKey = ComposerNames.normalize(text).key
                    if (composer == null && composerKey.isNotEmpty() && composerKey in library.composerKeys) {
                        composer = composerKey
                        claim("composer", library.composerName(composerKey), i, size)
                        continue
                    }
                    val joined = gram.joinToString(" ")
                    val longEnough = size >= 2 || joined.length >= 5
                    if (artist == null && composer == null && longEnough && gram.none { it in StyleVocabulary.allWords } && library.byArtist(joined).isNotEmpty()) {
                        artist = joined
                        claim("artist", text, i, size)
                    }
                }
            }
        }

        // ---- P4: forms and the catalogue -------------------------------------------------------------------

        fun forms() {
            for (i in 0 until n) {
                if (!free(i)) continue
                val found = StyleVocabulary.form(keys[i]) ?: continue
                if (form == null) {
                    form = found
                    claim("form", found.plural, i)
                } else if (form == found) {
                    claim("form", found.plural, i)
                }
            }
        }

        fun catalogue() {
            for (size in minOf(3, n) downTo 1) {
                for (i in 0..n - size) {
                    if (catalogue != null || !free(i, size)) continue
                    val joined = (i until i + size).joinToString(" ") { keys[it] }
                    if (joined in StyleVocabulary.moods || joined in StyleVocabulary.stopWords || joined in StyleVocabulary.notCatalogue) continue
                    val listKey = StyleVocabulary.listAliases[joined]
                    val entry = library.catalogue.firstOrNull { e ->
                        (listKey != null && e.list && e.key == listKey) || TextKeys.fold(e.key) == joined || TextKeys.fold(e.name) == joined
                    } ?: continue
                    catalogue = entry
                    claim("catalogue", entry.name, i, size)
                }
            }
        }

        // ---- P5: moods, tempo words, comparatives, again ---------------------------------------------------

        fun moods() {
            for (i in 0 until n - 1) {
                if (!free(i, 2)) continue
                val pair = "${keys[i]} ${keys[i + 1]}"
                StyleVocabulary.tempos[pair]?.let { setTempo(TempoAsk.Class(it, pair), i, 2) }
            }
            for (i in 0 until n) {
                if (!free(i)) continue
                val w = keys[i]
                val before = keys.getOrNull(i - 1)
                val step = when {
                    before != null && before in StyleVocabulary.small -> StyleVocabulary.smallSteps[w]
                    before != null && before in StyleVocabulary.big -> StyleVocabulary.bigSteps[w]
                    else -> StyleVocabulary.tempoSteps[w]
                }
                when {
                    w in StyleVocabulary.moods -> {
                        val found = StyleVocabulary.moods.getValue(w)
                        if (mood == null || mood == found) {
                            mood = found
                            claim("mood", found.label, i)
                        }
                    }
                    w in StyleVocabulary.tempos -> setTempo(TempoAsk.Class(StyleVocabulary.tempos.getValue(w), w), i, 1)
                    step != null -> if (tempo == null) {
                        tempo = TempoAsk.Scale(step)
                        claim("tempo", if (step < 1) "slower" else "faster", i)
                        val size = if (before in StyleVocabulary.small) "A bit " else if (before in StyleVocabulary.big) "Much " else ""
                        changes += "${size.ifEmpty { "" }}${if (size.isEmpty()) (if (step < 1) "Slower" else "Faster") else (if (step < 1) "slower" else "faster")}"
                    }
                    w in StyleVocabulary.lengthSteps -> if (lengthStep == 0) {
                        lengthStep = StyleVocabulary.lengthSteps.getValue(w)
                        claim("length", w, i)
                    }
                    w in StyleVocabulary.moodComparatives -> if (mood == null) {
                        mood = StyleVocabulary.moodComparatives.getValue(w)
                        claim("mood", mood!!.label, i)
                        changes += "${w.replaceFirstChar { it.titlecase(Locale.ROOT) }}: ${mood!!.label}"
                    }
                    w in StyleVocabulary.towardsCalm -> if (moodStep == 0 && mood == null) {
                        moodStep = -1
                        claim("mood", "calmer", i)
                    }
                    w in StyleVocabulary.towardsWild -> if (moodStep == 0 && mood == null) {
                        moodStep = 1
                        claim("mood", "wilder", i)
                    }
                    w == StyleVocabulary.MORE && i + 1 < n && free(i + 1) && keys[i + 1] in StyleVocabulary.moods && mood == null && moodStep == 0 -> {
                        val toward = StyleVocabulary.moods.getValue(keys[i + 1])
                        moodStep = if (toward == Mood.Wild || toward == Mood.Bright) 1 else -1
                        if (toward == Mood.Melancholy) {
                            moodStep = 0
                            mood = Mood.Melancholy
                        }
                        claim("mood", "more ${keys[i + 1]}", i, 2)
                    }
                    w in StyleVocabulary.again -> {
                        again = true
                        claim("again", "again", i)
                    }
                    w in StyleVocabulary.different && (w == "different" || before == "something" || before == "surprise" || keys.getOrNull(i + 1) == "me") -> {
                        different = true
                        claim("different", "different", i)
                    }
                    w == "one" && keys.getOrNull(i + 1) == "more" && free(i + 1) -> {
                        again = true
                        claim("again", "one more", i, 2)
                    }
                }
            }
            if (form != null && mood == null && form!!.mood != null) mood = form!!.mood
            if (form != null && tempo == null && form!!.bpm != null) tempo = TempoAsk.Class(form!!.bpm!!, form!!.key)
        }

        // ---- the result -------------------------------------------------------------------------------------

        fun result(): StyleResult {
            val seedBearing = title != null || composer != null || artist != null || form != null || catalogue != null
            val refine = previous != null && !seedBearing
            val base = if (refine) previous!!.spec else StyleSpec()
            val moodNow = mood ?: if (moodStep != 0) stepped(base.mood, moodStep) else base.mood
            val minutesNow = minutes?.toInt() ?: (base.minutes + lengthStep).coerceIn(PromptBuilder.MIN_MINUTES, PromptBuilder.MAX_MINUTES)
            val tempoNow = when (val t = tempo) {
                is TempoAsk.Scale -> previous?.bpm?.takeIf { refine }?.let { TempoAsk.Exact((it * t.factor).roundToInt().coerceIn(PromptBuilder.MIN_BPM, PromptBuilder.MAX_BPM)) } ?: t
                null -> if (refine) base.tempo else null
                else -> t
            }
            val keyNow = key ?: if (refine && minor == null) base.key else null
            val minorNow = minor ?: if (refine && key == null) base.minor else null
            val seed: SeedAsk
            val candidates: List<PieceEntity>
            if (refine) {
                seed = base.seed
                candidates = candidatesOf(seed, moodNow)
            } else {
                val pick = seedOf(moodNow)
                seed = pick.first
                candidates = pick.second
            }
            val variant = when {
                refine && different -> base.variant + 1
                refine -> base.variant
                else -> 0
            }
            val spec = StyleSpec(moodNow, keyNow, minorNow, tempoNow, minutesNow, seed, variant)
            val unused = (0 until n).filter { !claimed[it] && (blocked[it] || keys[it] !in StyleVocabulary.stopWords) }.map { words[it].text }.take(MAX_UNUSED)
            val line = if (refine) refinementLine(spec) else line(spec)
            return StyleResult(spec, candidates.take(MAX_CANDIDATES).map { it.id }, understood, line, unused, refine, again && refine)
        }

        fun refinementLine(spec: StyleSpec): String {
            val parts = ArrayList<String>()
            val t = spec.tempo
            if (tempo is TempoAsk.Scale && t is TempoAsk.Exact) {
                val word = changes.firstOrNull { it.contains("lower") || it.contains("aster") } ?: "Tempo"
                parts += "$word: ${t.bpm} bpm"
            } else if (tempo != null) {
                parts += "Tempo: ${tempoLabel(spec.tempo ?: tempo!!)}"
            }
            if (lengthStep != 0 || minutes != null) parts += "${if (lengthStep > 0) "Longer" else if (lengthStep < 0) "Shorter" else "Length"}: ${spec.minutes} min${clamped?.let { " ($it)" }.orEmpty()}"
            if (key != null) parts += "In ${key!!.label}"
            if (minor != null && key == null) parts += if (minor == true) "Minor" else "Major"
            changes.firstOrNull { it.contains(':') && !it.contains("bpm") }?.let { parts += it }
            if (moodStep != 0 && mood == null) parts += "${if (moodStep < 0) "Calmer" else "Wilder"}: ${spec.mood.label}"
            if (mood != null && changes.none { it.contains(':') }) parts += spec.mood.label
            if (different) parts += "A different piece"
            if (parts.isEmpty()) parts += "Another like it"
            return parts.joinToString(" · ")
        }

        fun line(spec: StyleSpec): String = buildList {
            add(spec.mood.label)
            spec.key?.let { add(it.label) } ?: spec.minor?.let { add(if (it) "minor" else "major") }
            spec.tempo?.let { add(tempoLabel(it)) }
            add("${spec.minutes} min${clamped?.let { " ($it)" }.orEmpty()}")
            seedLabel(spec.seed)?.let { add("in the manner of $it") }
        }.joinToString(" · ")

        /** The seed (plans/plan-studio.md (a)): a title, then a composer or performer (with a form when both give pieces), a form, the catalogue, the mood's pool, the default. */
        fun seedOf(mood: Mood): Pair<SeedAsk, List<PieceEntity>> {
            title?.let { (words, found) -> return SeedAsk.Title(words, titleLabel(found.first())) to found }
            val order = library.order(mood, composer)
            composer?.let { c ->
                val pieces = library.byComposer(c)
                val f = form
                if (f != null) {
                    val both = pieces.filter { f.titles.containsMatchIn(it.titleKey) }
                    if (both.isNotEmpty()) return SeedAsk.Pool("composer:$c+form:${f.key}", "${library.composerName(c)}'s ${f.plural}") to both.sortedWith(order)
                }
                if (pieces.isNotEmpty()) return SeedAsk.Pool("composer:$c", library.composerName(c)) to pieces.sortedWith(order)
            }
            artist?.let { a ->
                val pieces = library.byArtist(a)
                if (pieces.isNotEmpty()) return SeedAsk.Pool("artist:$a", artistLabel(a)) to pieces.sortedWith(order)
            }
            form?.let { f ->
                val pieces = formPieces(f)
                if (pieces.isNotEmpty()) return SeedAsk.Pool("form:${f.key}", f.label) to pieces.sortedWith(order)
            }
            catalogue?.let { e ->
                val pieces = library.inCatalogue(e.key)
                if (pieces.isNotEmpty()) return SeedAsk.Pool("${if (e.list) "list" else "channel"}:${e.key}", catalogueLabel(e)) to pieces.sortedWith(order)
            }
            val moodPool = moodPool(mood)
            if (moodPool.isNotEmpty() && (mood != Mood.Calm || this.mood != null)) return SeedAsk.Pool("mood:${mood.name.lowercase(Locale.ROOT)}", "") to moodPool.sortedWith(order)
            return SeedAsk.Default to emptyList()
        }

        fun candidatesOf(seed: SeedAsk, mood: Mood): List<PieceEntity> = when (seed) {
            is SeedAsk.Title -> library.byTitle(seed.words, mood)
            is SeedAsk.Piece -> listOfNotNull(library.byId[seed.pieceId])
            SeedAsk.Default -> emptyList()
            is SeedAsk.Pool -> poolPieces(seed.source, mood).sortedWith(library.order(mood))
        }

        fun poolPieces(source: String, mood: Mood): List<PieceEntity> {
            val parts = source.split('+').associate { it.substringBefore(':') to it.substringAfter(':') }
            val form = parts["form"]?.let { key -> StyleVocabulary.forms.firstOrNull { it.key == key } }
            val named = parts["composer"]?.let(library::byComposer) ?: parts["artist"]?.let(library::byArtist)
            return when {
                named != null && form != null -> named.filter { form.titles.containsMatchIn(it.titleKey) }
                named != null -> named
                form != null -> formPieces(form)
                parts["channel"] != null -> library.inCatalogue(parts.getValue("channel"))
                parts["list"] != null -> library.inCatalogue(parts.getValue("list"))
                parts["mood"] != null -> Mood.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == parts["mood"] }?.let(::moodPool).orEmpty()
                else -> emptyList()
            }
        }

        fun formPieces(f: StyleForm): List<PieceEntity> {
            val byTitle = library.pieces.filter { f.titles.containsMatchIn(it.titleKey) }
            return byTitle.ifEmpty { f.fallback?.let { library.byComposer(it) }.orEmpty() }
        }

        /** Calm: the Calm channel; Melancholy: the nocturnes; Wild: Epic on piano; Bright: Popular, at four to nine notes a second. */
        fun moodPool(mood: Mood): List<PieceEntity> = when (mood) {
            Mood.Calm -> library.inCatalogue("calm")
            Mood.Melancholy -> library.inCatalogue("nocturnes")
            Mood.Wild -> library.inCatalogue("epic")
            Mood.Bright -> library.inCatalogue("popular").filter { StyleLibrary.density(it) in 4.0..9.0 }
        }

        fun stepped(from: Mood, step: Int): Mood = when {
            from == Mood.Melancholy -> if (step < 0) Mood.Calm else Mood.Wild
            step < 0 -> if (from == Mood.Wild) Mood.Bright else Mood.Calm
            else -> if (from == Mood.Calm) Mood.Bright else Mood.Wild
        }

        fun titleLabel(p: PieceEntity): String {
            val who = p.composerShort.ifBlank { p.composer }
            return if (who.isBlank()) p.title else "${p.title} ($who)"
        }

        fun artistLabel(folded: String): String =
            library.pieces.asSequence().flatMap { StyleWords.cut(it.composer).windowed(folded.count { c -> c == ' ' } + 1) }
                .firstOrNull { gram -> gram.joinToString(" ") { it.key } == folded }?.joinToString(" ") { it.text } ?: folded

        fun catalogueLabel(e: CatalogueEntry): String = if (e.name.endsWith("s") || ' ' in e.name) e.name else "${e.name} pieces"

        fun seedLabel(seed: SeedAsk): String? = when (seed) {
            is SeedAsk.Title -> seed.label
            is SeedAsk.Pool -> seed.label.ifBlank { null }
            else -> null
        }
    }

    /** How the line says a tempo: the word typed ("slow", "andante"), "96 bpm", "slower" or "faster". */
    fun tempoLabel(t: TempoAsk): String = when (t) {
        is TempoAsk.Exact -> "${t.bpm} bpm"
        is TempoAsk.Class -> t.word
        is TempoAsk.Scale -> if (t.factor < 1) "slower" else "faster"
    }

    /**
     * A piece's title from what was understood, never from what was typed (v1.12 — M30): "Calm, after Clair de
     * lune" (a seed asked for by its title, or chosen in the sheet), "Calm, after Chopin" (a composer's pool),
     * "Calm, after a nocturne" (a form's), else "Calm piece". Cut to the library's title length.
     */
    fun title(mood: Mood, seed: SeedAsk, seedTitle: String?): String {
        val after = when (seed) {
            is SeedAsk.Title, is SeedAsk.Piece -> seedTitle?.trim()?.takeIf { it.isNotEmpty() }
            is SeedAsk.Pool -> poolName(seed)
            SeedAsk.Default -> null
        }
        return TextLimits.clip(if (after == null) "${mood.label} piece" else "${mood.label}, after $after", TextLimits.TITLE)
    }

    /** A pool's composer or form, for a title: "Chopin" for Chopin's nocturnes, "a nocturne", "Horowitz"; none for a channel, a list or a mood. */
    private fun poolName(pool: SeedAsk.Pool): String? {
        val parts = pool.source.split('+').map { it.substringBefore(':') }
        return when {
            "composer" in parts -> pool.label.substringBefore("'s ").ifBlank { null }
            "artist" in parts -> pool.label.ifBlank { null }
            "form" in parts -> pool.label.ifBlank { null }
            else -> null
        }
    }

    private val MSS = Regex("^(\\d{1,2}):([0-5]\\d)$")
    private val NUMBER_MIN = Regex("^(\\d{1,3}(?:\\.\\d{1,2})?)(?:min|mins|minute|minutes)$")
    private val NUMBER_SEC = Regex("^(\\d{1,4})(?:s|sec|secs|second|seconds)$")
    private val NUMBER_BPM = Regex("^(\\d{2,3})bpm$")
    private val PLAIN = Regex("^\\d{2,3}$")
    private val DECIMAL = Regex("^\\d{1,4}(?:\\.\\d{1,2})?$")
    private val TONIC = Regex("^([a-g])(#|b)?(m)?$")
    private val PITCH = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)
}
