// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** What a Studio job does: bring a model onto the tablet, or turn a recording into a piece. */
enum class JobKind { Download, Transcribe }

/** Where a job is. */
enum class JobState {
    Queued,
    Running,
    Done,
    Failed,
    Cancelled,
    ;

    val finished: Boolean get() = this == Done || this == Failed || this == Cancelled
}

/** What a running job is doing now. */
enum class JobStep { Waiting, Downloading, Reading, Transcribing, Saving }

/**
 * One of Studio's jobs, as the Studio page, its notification and the web panel show it (v1.7 — M23).
 * [name] is the model's title ("Transcription") or the recording's name; [progress] is 0–1 while its
 * [step] has a measure (null otherwise); [error] is the failure's line. A download names its [model]
 * and counts its [bytes] of [total]; a finished transcription names the piece it made ([pieceId],
 * [title]).
 */
data class StudioJob(
    val id: Long,
    val kind: JobKind,
    val name: String,
    val state: JobState = JobState.Queued,
    val step: JobStep = JobStep.Waiting,
    val progress: Float? = null,
    val error: String? = null,
    val model: String? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val pieceId: Long? = null,
    val title: String? = null,
)

/**
 * Studio's jobs as one list ([jobs], oldest first): those waiting and the one running, then at most
 * [keep] finished ones (the oldest go first). In memory: a restart of the app starts an empty list
 * (what a job made stays in the library). Safe from any thread.
 */
class StudioJobs(private val keep: Int = KEEP) {
    private val state = MutableStateFlow<List<StudioJob>>(emptyList())
    val jobs: StateFlow<List<StudioJob>> = state.asStateFlow()
    private val nextId = AtomicLong(1)

    /** A new job, queued. */
    fun add(kind: JobKind, name: String, model: String? = null): StudioJob {
        val job = StudioJob(nextId.getAndIncrement(), kind, name, model = model)
        state.update { trimmed(it + job) }
        return job
    }

    /** Changes job [id] (nothing when it is gone). */
    fun update(id: Long, change: (StudioJob) -> StudioJob) {
        state.update { list -> trimmed(list.map { if (it.id == id) change(it) else it }) }
    }

    fun get(id: Long): StudioJob? = state.value.firstOrNull { it.id == id }

    /** The job running now, if any. */
    val running: StudioJob? get() = state.value.firstOrNull { it.state == JobState.Running }

    /** Whether any job waits or runs. */
    val busy: Boolean get() = state.value.any { !it.state.finished }

    private fun trimmed(list: List<StudioJob>): List<StudioJob> {
        val finished = list.filter { it.state.finished }
        if (finished.size <= keep) return list
        val gone = finished.take(finished.size - keep).mapTo(HashSet()) { it.id }
        return list.filterNot { it.id in gone }
    }

    companion object {
        const val KEEP = 20
    }
}
