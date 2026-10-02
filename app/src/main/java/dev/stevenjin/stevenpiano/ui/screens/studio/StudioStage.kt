// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.studio

/**
 * Studio's stage (DESIGN.md › v1.13.1 — the stage): at most one card under the idea box. The running turn, else the
 * next one waiting; else the latest turn's result while it is fresh: made since the app started (its job is still
 * known), not cancelled, and not set aside by the one-minute rule ([StageMemory]); else nothing, and the stage is idle.
 * A new idea sent becomes the latest turn, so the result before it leaves the stage by itself. History is every turn,
 * newest first. Pure.
 */
object StudioStage {
    /** How long the tab must have been left for the result on the stage to be set aside. */
    const val AWAY_MS = 60_000L

    /** The one card on the stage, from [turns] (oldest first, as [StudioTurns.of] gives them), or null: idle. */
    fun card(turns: List<Turn>, memory: StageMemory = StageMemory()): Turn? {
        turns.firstOrNull { it.state == TurnState.Running }?.let { return it }
        turns.firstOrNull { it.state == TurnState.Waiting }?.let { return it }
        val latest = turns.lastOrNull() ?: return null
        return latest.takeIf { it.finished && it.job != null && it.state != TurnState.Cancelled && !memory.isSetAside(it) }
    }

    /** History: every turn, newest first. */
    fun history(turns: List<Turn>): List<Turn> = turns.asReversed()

    /** A turn's state as History's eyebrow says it. */
    fun eyebrow(state: TurnState): String = when (state) {
        TurnState.Waiting -> "Waiting"
        TurnState.Running -> "Working"
        TurnState.Made -> "Undecided"
        TurnState.Kept -> "Kept"
        TurnState.Discarded -> "Discarded"
        TurnState.Gone -> "Deleted"
        TurnState.Failed, TurnState.Interrupted -> "Failed"
        TurnState.Cancelled -> "Cancelled"
    }

    /** The names a turn goes by: its key, and its job's (a turn shown from its job alone becomes its history row). */
    internal fun names(turn: Turn): Set<String> = setOfNotNull(turn.key, turn.job?.let { "job:${it.id}" })
}

/**
 * The stage's one-minute rule, remembered across visits (times on one monotonic clock, injected): the results set
 * aside, and when the tab was last left with which result on the stage. A result that was on the stage when the tab
 * was left a minute or more ago is set aside when the tab is shown again; a result that came while the tab was away
 * (its turn was still running when it was left) waits on the stage. Pure.
 */
data class StageMemory(
    private val aside: Set<String> = emptySet(),
    val leftAt: Long? = null,
    private val showing: Set<String> = emptySet(),
) {
    fun isSetAside(turn: Turn): Boolean = StudioStage.names(turn).any { it in aside }

    /** The tab left at [now], [card] on the stage. */
    fun left(card: Turn?, now: Long): StageMemory =
        copy(leftAt = now, showing = card?.takeIf { it.finished }?.let(StudioStage::names).orEmpty())

    /** The tab shown again at [now]. */
    fun returned(now: Long): StageMemory {
        val at = leftAt ?: return this
        return StageMemory(if (now - at >= StudioStage.AWAY_MS) aside + showing else aside)
    }

    /**
     * [turn] removed in History, [card] on the stage: the removed turn and every other finished one but the card are
     * set aside, so an older result never comes back to the stage in the removed one's place.
     */
    fun removed(turn: Turn, turns: List<Turn>, card: Turn?): StageMemory {
        val older = turns.filter { it.finished && it.key != card?.key }.flatMap(StudioStage::names)
        return copy(aside = aside + StudioStage.names(turn) + older)
    }
}
