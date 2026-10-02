// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

/**
 * A composition's pace (v1.12 — M30), pure: when the job's state is next worth publishing (at most every
 * [PUBLISH_MS], so the notification, the web panel's state and the screen are not rebuilt ten times a second),
 * and how long is left. Time left is the smaller of the tokens left at the token rate and the music left at the
 * music rate (a piece ends at whichever comes first), shown after [SHOW_AFTER_MS] or [SHOW_AFTER_SHARE] of the way;
 * before that, from [calibration] (this tablet's milliseconds a token over its last jobs) when there is one.
 */
class ProgressMeter(
    private val budget: Int,
    private val targetTicks: Int,
    private val startedMs: Long,
    private val calibration: Double? = null,
) {
    private var publishedAt = Long.MIN_VALUE

    /** Whether to publish at [nowMs]: the first time, then at most every [PUBLISH_MS]. */
    fun due(nowMs: Long): Boolean {
        if (publishedAt != Long.MIN_VALUE && nowMs - publishedAt < PUBLISH_MS) return false
        publishedAt = nowMs
        return true
    }

    /** Milliseconds left with [tokens] made and [ticks] of music written at [nowMs]; null while too early to say. */
    fun etaMs(nowMs: Long, tokens: Int, ticks: Int, fraction: Float): Long? {
        val elapsed = (nowMs - startedMs).coerceAtLeast(0L)
        if (elapsed < SHOW_AFTER_MS && fraction < SHOW_AFTER_SHARE) {
            return calibration?.let { ((budget - tokens).coerceAtLeast(0) * it).toLong() }?.takeIf { tokens == 0 || it > 0 }
        }
        if (elapsed == 0L) return null
        val byTokens = if (tokens > 0) (budget - tokens).coerceAtLeast(0) * elapsed / tokens.toDouble() else Double.MAX_VALUE
        val byMusic = if (ticks > 0) (targetTicks - ticks).coerceAtLeast(0) * elapsed / ticks.toDouble() else Double.MAX_VALUE
        val left = minOf(byTokens, byMusic)
        return if (left == Double.MAX_VALUE) null else left.toLong()
    }

    companion object {
        const val PUBLISH_MS = 500L
        const val SHOW_AFTER_MS = 3_000L
        const val SHOW_AFTER_SHARE = 0.05f
    }
}
