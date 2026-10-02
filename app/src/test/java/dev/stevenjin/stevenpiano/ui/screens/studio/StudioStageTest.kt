// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.studio

import dev.stevenjin.stevenpiano.data.db.GenerationEntity
import dev.stevenjin.stevenpiano.data.db.GenerationEntity.Companion.CANCELLED
import dev.stevenjin.stevenpiano.data.db.GenerationEntity.Companion.DISCARDED
import dev.stevenjin.stevenpiano.data.db.GenerationEntity.Companion.FAILED
import dev.stevenjin.stevenpiano.data.db.GenerationEntity.Companion.KEPT
import dev.stevenjin.stevenpiano.data.db.GenerationEntity.Companion.MADE
import dev.stevenjin.stevenpiano.data.db.GenerationEntity.Companion.PENDING
import dev.stevenjin.stevenpiano.studio.JobKind
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.JobStep
import dev.stevenjin.stevenpiano.studio.StudioJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Studio's stage and History (v1.13.1, DESIGN.md › v1.13.1 — the stage): one card at most (the running turn, else
 * the next waiting, else the latest result while it is fresh, else nothing), History every turn newest first, and the
 * one-minute rule on an injected clock.
 */
class StudioStageTest {
    private fun row(id: Long, outcome: String, pieceId: Long? = null) = GenerationEntity(
        id = id,
        createdAt = id * 1_000,
        kind = GenerationEntity.COMPOSE,
        prompt = "calm and slow",
        understood = "Calm · slow",
        outcome = outcome,
        pieceId = pieceId,
        title = pieceId?.let { "Calm, after Clair de lune" },
    )

    private fun job(id: Long, turnId: Long?, state: JobState, pieceId: Long? = null) = StudioJob(
        id,
        JobKind.Compose,
        "Clair de lune",
        state = state,
        step = if (state == JobState.Running) JobStep.Composing else JobStep.Waiting,
        turnId = turnId,
        pieceId = pieceId,
    )

    /** The turns as the view model has them: the history newest first, as the database gives it. */
    private fun turns(rows: List<GenerationEntity>, jobs: List<StudioJob>, undecided: Set<Long> = emptySet()) =
        StudioTurns.of(rows.sortedByDescending { it.createdAt }, jobs, undecided, emptySet())

    @Test
    fun `the stage shows the running turn, else the next waiting, else the latest result, else nothing`() {
        val made = row(1, MADE, pieceId = 11)
        val doneJob = job(1, 1, JobState.Done, pieceId = 11)
        assertEquals("turn:2", StudioStage.card(turns(listOf(made, row(2, PENDING), row(3, PENDING)), listOf(doneJob, job(2, 2, JobState.Running), job(3, 3, JobState.Queued)), setOf(11)))?.key)
        assertEquals("turn:3", StudioStage.card(turns(listOf(made, row(3, PENDING)), listOf(doneJob, job(3, 3, JobState.Queued)), setOf(11)))?.key)
        assertEquals("turn:1", StudioStage.card(turns(listOf(made), listOf(doneJob), setOf(11)))?.key)
        assertEquals("a job not written yet is on the stage from itself", "job:4", StudioStage.card(turns(listOf(made), listOf(doneJob, job(4, null, JobState.Running)), setOf(11)))?.key)
        // Idle: nothing made since the app started (no job known), nothing at all, or the latest turn cancelled (the
        // result before it doesn't come back).
        assertNull(StudioStage.card(turns(listOf(made), emptyList(), setOf(11))))
        assertNull(StudioStage.card(emptyList()))
        assertNull(StudioStage.card(turns(listOf(made, row(2, CANCELLED)), listOf(doneJob, job(2, 2, JobState.Cancelled)), setOf(11))))
    }

    @Test
    fun `History lists every turn newest first, its state in the eyebrow, and a removed turn stays out`() {
        val rows = listOf(row(1, KEPT, pieceId = 11), row(2, DISCARDED, pieceId = 12), row(3, FAILED), row(5, MADE, pieceId = 15))
        val history = StudioStage.history(turns(rows, listOf(job(4, null, JobState.Running)), undecided = setOf(15)))
        assertEquals(listOf("job:4", "turn:5", "turn:3", "turn:2", "turn:1"), history.map { it.key })
        assertEquals(listOf("Working", "Undecided", "Failed", "Discarded", "Kept"), history.map { StudioStage.eyebrow(it.state) })
        // Turn 1 removed in History while its finished job is still known: it doesn't come back from the job.
        val removed = StudioStage.history(turns(rows.drop(1), listOf(job(1, 1, JobState.Done, pieceId = 11))))
        assertEquals(listOf("turn:5", "turn:3", "turn:2"), removed.map { it.key })
        assertEquals(emptyList<Turn>(), StudioStage.history(emptyList()))

        // The stage's result removed in History: the result before it doesn't take its place; another turn removed
        // leaves the stage's card where it is.
        val both = turns(listOf(row(1, MADE, pieceId = 11), row(2, MADE, pieceId = 12)), listOf(job(1, 1, JobState.Done, pieceId = 11), job(2, 2, JobState.Done, pieceId = 12)), setOf(11, 12))
        val onStage = StudioStage.card(both)
        assertEquals("turn:2", onStage?.key)
        val afterRemoval = StageMemory().removed(onStage!!, both, onStage)
        assertNull(StudioStage.card(both.filter { it.key != "turn:2" }, afterRemoval))
        assertEquals("turn:2", StudioStage.card(both.filter { it.key != "turn:1" }, StageMemory().removed(both.first(), both, onStage))?.key)
    }

    @Test
    fun `a result is set aside once the tab has been left on it for a minute, and one that came while away waits`() {
        var now = 5_000L
        val clock = { now }
        val shown = turns(listOf(row(1, MADE, pieceId = 11)), listOf(job(1, 1, JobState.Done, pieceId = 11)), setOf(11))
        var memory = StageMemory()

        memory = memory.left(StudioStage.card(shown, memory), clock())
        now += StudioStage.AWAY_MS - 1
        memory = memory.returned(clock())
        assertEquals("away 59.999 s: still on the stage", "turn:1", StudioStage.card(shown, memory)?.key)

        memory = memory.left(StudioStage.card(shown, memory), clock())
        now += StudioStage.AWAY_MS
        memory = memory.returned(clock())
        assertNull("away a minute: set aside, the stage idle", StudioStage.card(shown, memory))

        // Left while turn 2 ran; it finished while the tab was away: it waits on the stage, however long that was.
        val running = turns(listOf(row(1, MADE, pieceId = 11), row(2, PENDING)), listOf(job(1, 1, JobState.Done, pieceId = 11), job(2, 2, JobState.Running)), setOf(11))
        memory = memory.left(StudioStage.card(running, memory), clock())
        now += 10 * StudioStage.AWAY_MS
        memory = memory.returned(clock())
        val finished = turns(listOf(row(1, MADE, pieceId = 11), row(2, MADE, pieceId = 12)), listOf(job(1, 1, JobState.Done, pieceId = 11), job(2, 2, JobState.Done, pieceId = 12)), setOf(11, 12))
        assertEquals("turn:2", StudioStage.card(finished, memory)?.key)

        // A result left as its job alone (its row not written yet) is set aside under its row too.
        val fromJob = turns(emptyList(), listOf(job(7, null, JobState.Done, pieceId = 17)), setOf(17))
        memory = StageMemory().left(StudioStage.card(fromJob, StageMemory()), clock())
        now += StudioStage.AWAY_MS
        memory = memory.returned(clock())
        assertNull(StudioStage.card(turns(listOf(row(7, MADE, pieceId = 17)), listOf(job(7, 7, JobState.Done, pieceId = 17)), setOf(17)), memory))
    }
}
