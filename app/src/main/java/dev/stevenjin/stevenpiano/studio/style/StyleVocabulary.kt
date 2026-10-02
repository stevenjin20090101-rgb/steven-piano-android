// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.style

import dev.stevenjin.stevenpiano.studio.compose.Mood

/**
 * A musical form an idea can name (v1.12 — M30): the [words] that name it (folded, singular and plural), the
 * [titles] pattern that finds it in a piece's folded title (`PieceEntity.titleKey`), how the understood line
 * says it ([label]: "a nocturne") and a pool of them ([plural]: "nocturnes"), and what it hints when nothing
 * else says ([mood], [bpm]: a lullaby is calm and slow). [fallback] is the composer key a form is found by
 * when no title has it (a rag: Joplin).
 */
class StyleForm(
    val key: String,
    val words: Set<String>,
    val titles: Regex,
    val label: String,
    val plural: String,
    val mood: Mood? = null,
    val bpm: Int? = null,
    val fallback: String? = null,
)

/**
 * The words Studio understands without a text model (v1.12 — M30, `plans/plan-studio.md` (a)): moods, tempo
 * words, lengths, keys, forms, comparatives and the words that ask again. Every word is stored folded
 * (`TextKeys.fold`), as the parser folds what is typed; no word sits in two tables but where [StylePrompt]
 * declares the order (a mood word is never a catalogue word: "calm" is the mood, whose pool is the Calm channel).
 */
object StyleVocabulary {
    val moods: Map<String, Mood> = buildMap {
        for (w in listOf("calm", "peaceful", "gentle", "soft", "quiet", "relaxing", "relaxed", "soothing", "dreamy", "sleepy", "tender", "serene", "tranquil", "mellow")) put(w, Mood.Calm)
        for (w in listOf("bright", "happy", "cheerful", "joyful", "sunny", "playful", "uplifting", "merry", "hopeful")) put(w, Mood.Bright)
        for (w in listOf("wild", "dramatic", "stormy", "furious", "intense", "fiery", "energetic", "heroic")) put(w, Mood.Wild)
        for (w in listOf("melancholy", "melancholic", "sad", "sorrowful", "wistful", "lonely", "nostalgic", "dark", "gloomy", "mournful", "bittersweet", "rainy")) put(w, Mood.Melancholy)
    }

    /** Tempo words to a target in quarter notes a minute; the two-word ones are matched first. */
    val tempos: Map<String, Int> = mapOf(
        "very slow" to 48, "largo" to 48, "grave" to 48,
        "slow" to 60, "slowly" to 60, "adagio" to 60, "lento" to 60,
        "andante" to 76, "walking" to 76,
        "moderate" to 96, "moderato" to 96, "medium" to 96,
        "allegretto" to 112, "lively" to 112,
        "fast" to 132, "quick" to 132, "quickly" to 132, "allegro" to 132, "upbeat" to 132,
        "very fast" to 168, "presto" to 168, "vivace" to 168,
    )

    /** Words for a length in minutes on their own. */
    val lengths: Map<String, Int> = mapOf("short" to 1, "brief" to 1, "long" to 4, "lengthy" to 4)

    val minuteWords = setOf("min", "mins", "minute", "minutes")
    val secondWords = setOf("s", "sec", "secs", "second", "seconds")
    val hourWords = setOf("hour", "hours", "hr", "hrs")

    /** One to ten in words ("a two-minute piece"), and "a" or "an" as one. */
    val numbers: Map<String, Int> = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )

    val modes: Map<String, Boolean> = mapOf("major" to false, "maj" to false, "minor" to true, "min" to true)
    val accidentals: Map<String, Int> = mapOf("sharp" to 1, "#" to 1, "flat" to -1)

    /** Slower and faster, by how much: "a bit" and "much" change the step. */
    val tempoSteps: Map<String, Double> = mapOf("slower" to 0.85, "faster" to 1.18, "quicker" to 1.18)
    val smallSteps: Map<String, Double> = mapOf("slower" to 0.93, "faster" to 1.08, "quicker" to 1.08)
    val bigSteps: Map<String, Double> = mapOf("slower" to 0.7, "faster" to 1.4, "quicker" to 1.4)
    val small = setOf("bit", "little", "slightly", "touch")
    val big = setOf("much", "lot", "way", "far", "lots")

    /** Longer and shorter: a minute more or less. */
    val lengthSteps: Map<String, Int> = mapOf("longer" to 1, "shorter" to -1)

    /** Mood comparatives: one step towards calm or wild, or a mood outright. */
    val towardsCalm = setOf("calmer", "softer", "gentler", "quieter")
    val towardsWild = setOf("wilder", "louder", "stormier", "fiercer")
    val moodComparatives: Map<String, Mood> = mapOf(
        "sadder" to Mood.Melancholy, "darker" to Mood.Melancholy, "gloomier" to Mood.Melancholy,
        "happier" to Mood.Bright, "brighter" to Mood.Bright, "cheerier" to Mood.Bright, "sunnier" to Mood.Bright,
    )

    /** "more dramatic" and the like: the word after "more" names the mood the step goes towards. */
    const val MORE = "more"

    /** The same idea again, a new random seed ("another", "again", "one more", "another like it"). */
    val again = setOf("another", "again")

    /** Another seed piece. */
    val different = setOf("different", "else", "surprise", "other")

    /** Words that send the next content word to "Not used". */
    val negators = setOf("not", "no", "without", "less", "never", "dont", "isnt", "nothing")

    /** Skipped when looking for the word a negator takes ("not too fast": fast). */
    val intensifiers = setOf("too", "so", "very", "that", "overly", "really", "a", "an", "the", "any", "at", "all", "much", "more", "be", "it", "is")

    /** Words that carry nothing for the music: dropped silently. */
    val stopWords: Set<String> = setOf(
        "a", "an", "the", "and", "or", "but", "of", "in", "on", "at", "to", "for", "with", "by", "from", "into", "like", "as",
        "it", "its", "is", "be", "am", "are", "was", "me", "my", "i", "we", "us", "you", "your", "that", "this", "these", "those",
        "please", "piece", "pieces", "song", "songs", "music", "something", "some", "make", "write", "play", "compose", "create",
        "give", "want", "would", "could", "can", "will", "should", "kind", "sort", "style", "manner", "mood", "feel", "feeling",
        "tune", "tunes", "melody", "just", "really", "very", "too", "so", "quite", "bit", "little", "more", "much", "lot", "lots",
        "slightly", "touch", "way", "far", "key", "tempo", "length", "about", "around", "approximately", "roughly", "over", "under",
        "piano", "solo", "thanks", "thank", "maybe", "perhaps", "kinda", "one", "same", "again", "another", "now", "here", "there",
        "then", "than", "also", "let", "lets", "get", "got", "have", "has", "had", "do", "does", "did", "what", "how", "where",
        "when", "who", "which", "anything", "everything", "else", "other", "surprise", "not", "no",
        "without", "less", "never", "dont", "isnt", "nothing", "overly", "any", "all", "time", "need", "needs",
    )

    /** The catalogue's aliases (folded) for the built-in lists, beside their keys and names. */
    val listAliases: Map<String, String> = mapOf(
        "recognizable" to "recognisable", "famous" to "recognisable", "well known" to "recognisable", "wellknown" to "recognisable",
        "popular" to "popular", "favourite" to "popular", "favorite" to "popular",
    )

    /** The catalogue's aliases (folded) for the genre channels (v1.14 — M37), beside their keys and names. */
    val channelAliases: Map<String, String> = mapOf(
        "classical" to "classical", "classic" to "classical",
        "modern" to "modern", "pop" to "modern", "contemporary" to "modern",
    )

    /** Catalogue entries never named by an idea (they are the whole library). */
    val notCatalogue = setOf("everything", "all")

    private fun form(
        key: String,
        words: List<String>,
        titles: String,
        label: String,
        plural: String,
        mood: Mood? = null,
        bpm: Int? = null,
        fallback: String? = null,
    ) = StyleForm(key, words.toSet(), Regex(titles), label, plural, mood, bpm, fallback)

    val forms: List<StyleForm> = listOf(
        form("nocturne", listOf("nocturne", "nocturnes", "notturno"), "\\bnocturn|\\bnotturn", "a nocturne", "nocturnes"),
        form("waltz", listOf("waltz", "waltzes", "valse", "valses", "walzer"), "\\bwaltz|\\bvalse|\\bwalzer", "a waltz", "waltzes"),
        form("lullaby", listOf("lullaby", "lullabies", "berceuse", "berceuses", "cradle"), "\\blullab|\\bberceuse|\\bwiegenlied|\\bcradle", "a lullaby", "lullabies", Mood.Calm, 60),
        form("etude", listOf("etude", "etudes", "study", "studies"), "\\betude|\\bstud(?:y|ies|ie)\\b", "an étude", "études"),
        form("prelude", listOf("prelude", "preludes", "preludio"), "\\bprelud", "a prelude", "preludes"),
        form("fugue", listOf("fugue", "fugues", "fuga"), "\\bfug(?:ue|a)", "a fugue", "fugues"),
        form("sonata", listOf("sonata", "sonatas", "sonatina", "sonatinas"), "\\bsonat", "a sonata", "sonatas"),
        form("march", listOf("march", "marches", "marche", "marcia"), "\\bmarch(?:e|es)?\\b|\\bmarcia", "a march", "marches"),
        form("rag", listOf("rag", "rags", "ragtime"), "\\brag\\b|\\bragtime", "a rag", "rags", fallback = "joplin"),
        form("minuet", listOf("minuet", "minuets", "menuet", "minuetto"), "\\bminuet|\\bmenuet", "a minuet", "minuets"),
        form("mazurka", listOf("mazurka", "mazurkas"), "\\bmazurk", "a mazurka", "mazurkas"),
        form("polonaise", listOf("polonaise", "polonaises"), "\\bpolonais", "a polonaise", "polonaises"),
        form("ballade", listOf("ballade", "ballades", "ballad", "ballads"), "\\bballad", "a ballade", "ballades"),
        form("impromptu", listOf("impromptu", "impromptus"), "\\bimpromptu", "an impromptu", "impromptus"),
        form("scherzo", listOf("scherzo", "scherzos", "scherzi"), "\\bscherz", "a scherzo", "scherzos"),
        form("rhapsody", listOf("rhapsody", "rhapsodies", "rhapsodie"), "\\brhapsod", "a rhapsody", "rhapsodies"),
        form("barcarolle", listOf("barcarolle", "barcarolles", "barcarole"), "\\bbarcarol", "a barcarolle", "barcarolles", Mood.Calm),
        form("gymnopedie", listOf("gymnopedie", "gymnopedies"), "\\bgymnop", "a gymnopédie", "gymnopédies", Mood.Calm, 66),
        form("arabesque", listOf("arabesque", "arabesques"), "\\barabesk|\\barabesque", "an arabesque", "arabesques"),
        form("toccata", listOf("toccata", "toccatas"), "\\btoccat", "a toccata", "toccatas", Mood.Wild),
        form("invention", listOf("invention", "inventions"), "\\binvention", "an invention", "inventions"),
        form("variations", listOf("variation", "variations"), "\\bvariation", "variations", "variations"),
        form("serenade", listOf("serenade", "serenades", "serenata", "standchen"), "\\bserenad|\\bserenata|\\bstandchen", "a serenade", "serenades", Mood.Calm),
        form("romance", listOf("romance", "romances", "romanze"), "\\bromanc|\\bromanze", "a romance", "romances", Mood.Calm),
        form("fantasy", listOf("fantasy", "fantasies", "fantasia", "fantaisie", "fantasie"), "\\bfantas|\\bfantais", "a fantasy", "fantasies"),
        form("bagatelle", listOf("bagatelle", "bagatelles"), "\\bbagatel", "a bagatelle", "bagatelles"),
        form("intermezzo", listOf("intermezzo", "intermezzi", "intermezzos"), "\\bintermezz", "an intermezzo", "intermezzi"),
        form("carol", listOf("carol", "carols", "christmas", "xmas", "noel"), "\\bcarol|\\bchristmas|\\bxmas|\\bnoel|silent night|jingle bells", "a carol", "carols", fallback = "traditional"),
    )

    /** The form a folded word names, or null. */
    fun form(word: String): StyleForm? = forms.firstOrNull { word in it.words }

    /** Every word of every table (a title's single word must be in none of them to count). */
    val allWords: Set<String> by lazy {
        buildSet {
            addAll(moods.keys)
            tempos.keys.forEach { addAll(it.split(' ')) }
            addAll(lengths.keys)
            addAll(minuteWords)
            addAll(secondWords)
            addAll(hourWords)
            addAll(numbers.keys)
            addAll(modes.keys)
            addAll(accidentals.keys)
            addAll(tempoSteps.keys)
            addAll(small)
            addAll(big)
            addAll(lengthSteps.keys)
            addAll(towardsCalm)
            addAll(towardsWild)
            addAll(moodComparatives.keys)
            addAll(again)
            addAll(different)
            addAll(negators)
            addAll(stopWords)
            forms.forEach { addAll(it.words) }
            listAliases.keys.forEach { addAll(it.split(' ')) }
            channelAliases.keys.forEach { addAll(it.split(' ')) }
        }
    }
}
