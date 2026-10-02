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
import dev.stevenjin.stevenpiano.studio.JobKind
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.ui.StudioCopy

/** Where a turn is. */
enum class TurnState {
    Waiting,
    Running,

    /** Made: the piece waits for Keep or Discard. */
    Made,
    Kept,
    Discarded,

    /** Made, then deleted from the library. */
    Gone,
    Failed,
    Cancelled,
    Interrupted,
}

/**
 * One turn of Studio (v1.12 — M30; on the stage or in History since v1.13.1): what was asked ([asked], [typed] when
 * it is a person's own words), and the card: [title], what was understood, the words not used, the live [job] while
 * it runs, the piece it made, the seed for the credits, and what the cover is drawn from.
 */
data class Turn(
    val key: String,
    val rowId: Long?,
    val job: StudioJob?,
    val transcription: Boolean,
    val asked: String,
    val typed: Boolean,
    val title: String?,
    val understood: String,
    val unused: List<String>,
    val state: TurnState,
    val pieceId: Long?,
    val seedPieceId: Long?,
    val seedTitle: String?,
    val seedComposer: String?,
    val mood: Mood?,
    val musicKey: MusicKey?,
    val bpm: Int?,
    val minutes: Int?,
    val coverSeed: Long,
    val note: String?,
    val spec: String?,
    val createdAt: Long,
) {
    val finished: Boolean get() = state != TurnState.Waiting && state != TurnState.Running

    /** The piece can be heard: made and still in the library. */
    val listenable: Boolean get() = pieceId != null && (state == TurnState.Made || state == TurnState.Kept)
}

/**
 * Studio's turns from its history ([rows], newest first as the history gives them) and its live [jobs]: a
 * job's live state wins over its turn's row while it waits or runs; the review's in-memory sets ([undecided],
 * [discarded]) win over a row the database has not caught up with. Oldest first ([StudioStage] picks the stage's card
 * and turns them round for History). Pure.
 */
object StudioTurns {
    fun of(rows: List<GenerationEntity>, jobs: List<StudioJob>, undecided: Set<Long>, discarded: Set<Long>): List<Turn> {
        val studioJobs = jobs.filter { it.kind != JobKind.Download }
        val byTurn = studioJobs.filter { it.turnId != null }.associateBy { it.turnId }
        val out = ArrayList<Turn>()
        for (row in rows) out += fromRow(row, byTurn[row.id], undecided, discarded)
        val shown = rows.mapTo(HashSet()) { it.id }
        // A job whose turn is not written yet (or can't be): shown from the job alone. A finished job whose turn was
        // written and has left the history (removed in History) stays out: it doesn't come back from memory.
        for (job in studioJobs) if (job.turnId == null || job.turnId !in shown) if (!job.state.finished || job.pieceId != null && job.turnId == null) out += fromJob(job, undecided, discarded)
        return out.sortedWith(compareBy({ it.createdAt }, { it.rowId ?: Long.MAX_VALUE }, { it.job?.id ?: 0L }))
    }

    private fun fromRow(row: GenerationEntity, job: StudioJob?, undecided: Set<Long>, discarded: Set<Long>): Turn {
        val live = job?.takeIf { !it.state.finished }
        val pieceId = row.pieceId ?: job?.pieceId?.takeIf { row.outcome == GenerationEntity.PENDING }
        val state = when {
            live != null -> if (live.state == JobState.Running) TurnState.Running else TurnState.Waiting
            pieceId != null && pieceId in discarded -> TurnState.Discarded
            pieceId != null && pieceId in undecided -> TurnState.Made
            row.outcome == GenerationEntity.MADE || row.outcome == GenerationEntity.KEPT -> if (pieceId == null) TurnState.Gone else TurnState.Kept
            row.outcome == GenerationEntity.DISCARDED -> TurnState.Discarded
            row.outcome == GenerationEntity.FAILED -> TurnState.Failed
            row.outcome == GenerationEntity.CANCELLED -> TurnState.Cancelled
            row.outcome == GenerationEntity.INTERRUPTED -> TurnState.Interrupted
            job != null && job.state == JobState.Done && job.pieceId != null -> if (job.pieceId in undecided) TurnState.Made else TurnState.Kept
            job != null && job.state == JobState.Failed -> TurnState.Failed
            job != null && job.state == JobState.Cancelled -> TurnState.Cancelled
            else -> TurnState.Interrupted
        }
        val transcription = row.kind == GenerationEntity.TRANSCRIBE
        val typed = !row.prompt.isNullOrBlank()
        val note = when {
            state == TurnState.Failed -> row.error ?: job?.error ?: "It didn't finish."
            state == TurnState.Interrupted -> "It stopped when the app closed."
            state == TurnState.Cancelled -> "Cancelled."
            state == TurnState.Gone -> "Deleted from the library."
            row.stop == "budget" && row.musicMs != null && row.minutes != null -> StudioCopy.budgetStop(row.musicMs, row.minutes * 60_000L)
            else -> null
        }
        return Turn(
            key = "turn:${row.id}",
            rowId = row.id,
            job = job,
            transcription = transcription,
            asked = row.prompt?.takeIf { typed } ?: row.understood,
            typed = typed,
            title = row.title ?: job?.title,
            understood = row.understood,
            unused = row.unused.split('\n').filter { it.isNotBlank() },
            state = state,
            pieceId = pieceId,
            seedPieceId = row.seedPieceId,
            seedTitle = row.seedTitle,
            seedComposer = row.seedComposer,
            mood = row.mood?.let { name -> Mood.entries.firstOrNull { it.name == name } },
            musicKey = if (row.keyTonic != null && row.keyMinor != null && row.keyTonic in 0..11) MusicKey(row.keyTonic, row.keyMinor) else null,
            bpm = row.bpm,
            minutes = row.minutes,
            coverSeed = row.coverSeed ?: row.randomSeed ?: row.id,
            note = note,
            spec = row.spec,
            createdAt = row.createdAt,
        )
    }

    private fun fromJob(job: StudioJob, undecided: Set<Long>, discarded: Set<Long>): Turn {
        val state = when (job.state) {
            JobState.Queued -> TurnState.Waiting
            JobState.Running -> TurnState.Running
            JobState.Done -> when (job.pieceId) {
                null -> TurnState.Kept
                in discarded -> TurnState.Discarded
                in undecided -> TurnState.Made
                else -> TurnState.Kept
            }
            JobState.Failed -> TurnState.Failed
            JobState.Cancelled -> TurnState.Cancelled
        }
        return Turn(
            key = "job:${job.id}",
            rowId = null,
            job = job,
            transcription = job.kind == JobKind.Transcribe,
            asked = StudioCopy.jobName(job),
            typed = false,
            title = job.title,
            understood = "",
            unused = emptyList(),
            state = state,
            pieceId = job.pieceId,
            seedPieceId = null,
            seedTitle = if (job.kind == JobKind.Compose) job.name else null,
            seedComposer = null,
            mood = null,
            musicKey = null,
            bpm = null,
            minutes = null,
            coverSeed = job.id,
            note = if (job.state == JobState.Failed) job.error else null,
            spec = null,
            createdAt = Long.MAX_VALUE,
        )
    }
}
