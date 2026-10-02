// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.style

import dev.stevenjin.stevenpiano.studio.compose.ComposeRequest
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.PromptBuilder
import dev.stevenjin.stevenpiano.studio.compose.SeedFacts
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An idea's seed piece and what is asked of it (v1.12 — M30), on the job's thread: it reads the key and tempo of
 * at most [LOOK] candidates ([PromptBuilder.facts], one file each). A title or a chosen piece is taken as found;
 * from a pool it prefers a seed whose own mode is the one asked for (a transposition can't change a mode) and
 * whose tempo is nearest. "Different" ([StyleSpec.variant]) starts that many places further down the list.
 */
object SeedPicker {
    const val LOOK = 4

    /** The seed [pieceId], what the composition asks of it ([request]), and the seed's own [facts]. */
    data class Pick(val pieceId: Long, val request: ComposeRequest, val facts: SeedFacts)

    /** The seed among [candidates] (best first), [avoid] passed over when another will do; null when none can be read. */
    fun pick(spec: StyleSpec, candidates: List<Long>, facts: (Long) -> SeedFacts?, avoid: Long? = null): Pick? {
        if (candidates.isEmpty()) return null
        val start = Math.floorMod(spec.variant, candidates.size)
        val rotated = candidates.drop(start) + candidates.take(start)
        val looked = rotated.filter { it != avoid }.ifEmpty { rotated }
        if (spec.seed is SeedAsk.Title || spec.seed is SeedAsk.Piece || spec.variant > 0) {
            for (id in looked.take(LOOK)) facts(id)?.let { return Pick(id, request(spec, it), it) }
            return null
        }
        val read = looked.take(LOOK).mapNotNull { id -> facts(id)?.let { id to it } }
        val minorWanted = spec.key?.minor ?: spec.minor ?: (spec.mood == Mood.Melancholy).takeIf { it }
        val target = targetBpm(spec)
        val best = read.withIndex().minWithOrNull(
            compareBy<IndexedValue<Pair<Long, SeedFacts>>>(
                { (_, p) -> if (minorWanted == null || p.second.key.minor == minorWanted) 0 else 1 },
                { (_, p) -> if (target == null) 0 else abs(p.second.bpm - target) },
                { it.index },
            ),
        )?.value ?: return null
        return Pick(best.first, request(spec, best.second), best.second)
    }

    /** What [spec] asks of a seed whose own key and tempo are [facts]. */
    fun request(spec: StyleSpec, facts: SeedFacts): ComposeRequest {
        val key = spec.key ?: spec.minor?.let { minor ->
            when {
                facts.key.minor == minor -> facts.key
                minor -> facts.key.relativeMinor
                else -> MusicKey(facts.key.relativeMajor, false)
            }
        } ?: PromptBuilder.suggestedKey(spec.mood, facts.key)
        val bpm = when (val t = spec.tempo) {
            is TempoAsk.Exact -> t.bpm
            // A tempo word is held near the seed's own, so a seed is never stretched absurdly.
            is TempoAsk.Class -> t.bpm.coerceIn((facts.bpm * MIN_STRETCH).roundToInt(), (facts.bpm * MAX_STRETCH).roundToInt())
            is TempoAsk.Scale -> (facts.bpm * t.factor).roundToInt()
            null -> null
        }?.coerceIn(PromptBuilder.MIN_BPM, PromptBuilder.MAX_BPM)
        return ComposeRequest(spec.mood, key, bpm, spec.minutes.coerceIn(PromptBuilder.MIN_MINUTES, PromptBuilder.MAX_MINUTES))
    }

    private fun targetBpm(spec: StyleSpec): Int? = when (val t = spec.tempo) {
        is TempoAsk.Exact -> t.bpm
        is TempoAsk.Class -> t.bpm
        else -> null
    }

    private const val MIN_STRETCH = 0.67
    private const val MAX_STRETCH = 1.5
}
