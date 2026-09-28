// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.studio.JobKind
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.JobStep
import dev.stevenjin.stevenpiano.studio.ModelEntry
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.studio.StudioSupport
import java.util.Locale
import kotlin.math.roundToInt

/** What Studio says (DESIGN.md › v1.7 — M23): its page, the hub's row, the Library's + sheet, the notification, the panel. */
object StudioCopy {
    const val TRANSCRIBE = "Transcribe a recording…"
    const val TRANSCRIBE_NOTE = "Any piano recording. About a minute per three minutes of audio."

    /** Under the + sheet's entry and the page's button while the model isn't here yet. */
    fun downloadsFirst(model: ModelEntry): String = "Downloads the transcription model (${size(model.sizeBytes)}) first."

    /** The hub's line in CONTROL where Studio's row would be, on a device that can't run it. */
    fun unsupported(support: StudioSupport): String? = when (support) {
        StudioSupport.NoRuntime -> "Studio isn't available on this device"
        StudioSupport.TooLittleMemory -> "This tablet doesn't have enough memory for Studio"
        else -> null
    }

    /**
     * The hub row's value: the job running ("Transcribing 42%", "Downloading 42%"), "Waiting" while
     * one waits, else how many models are installed ("2 models", "1 model", "No models").
     */
    fun hub(installed: Int, jobs: List<StudioJob>): String {
        val running = jobs.firstOrNull { it.state == JobState.Running }
        if (running != null) {
            val verb = if (running.kind == JobKind.Download) "Downloading" else "Transcribing"
            val pct = running.progress?.takeIf { running.step == JobStep.Downloading || running.step == JobStep.Transcribing }
            return if (pct == null) verb else "$verb ${Format.percent(percentOf(pct))}"
        }
        if (jobs.any { it.state == JobState.Queued }) return "Waiting"
        return when (installed) {
            0 -> "No models"
            1 -> "1 model"
            else -> "$installed models"
        }
    }

    /** "125 MB · CC BY 4.0". */
    fun modelLine(model: ModelEntry): String = "${size(model.sizeBytes)} · ${model.licenceLabel}"

    /** Whole decimal megabytes, as Android counts them: "125 MB". */
    fun size(bytes: Long): String = "${(bytes / 1_000_000.0).roundToInt()} MB"

    /**
     * A job's line: what it is doing ("Downloading · 42 of 125 MB", "Reading the recording…",
     * "Transcribing · 42%", "Adding it to the library…"), or how it ended: a model "Installed"; a piece
     * waiting for Keep or Discard ([undecided]), "Kept as <title>", or "Discarded" ([discarded]); the
     * failure's own line; "Cancelled".
     */
    fun jobLine(job: StudioJob, undecided: Set<Long> = emptySet(), discarded: Set<Long> = emptySet(), locale: Locale = Locale.getDefault()): String =
        when (job.state) {
            JobState.Queued -> "Waiting"
            JobState.Cancelled -> "Cancelled"
            JobState.Failed -> job.error ?: "It didn't finish."
            JobState.Running -> when (job.step) {
                JobStep.Downloading -> "Downloading · ${megabytes(job.bytes, job.total, locale)}"
                JobStep.Reading -> "Reading the recording…"
                JobStep.Transcribing -> "Transcribing · ${Format.percent(percentOf(job.progress ?: 0f))}"
                JobStep.Saving -> "Adding it to the library…"
                JobStep.Waiting -> if (job.kind == JobKind.Download) "Downloading" else "Starting…"
            }
            JobState.Done -> when {
                job.kind == JobKind.Download -> "Installed"
                job.pieceId in discarded -> "Discarded"
                job.pieceId in undecided -> "Ready: listen, then keep it or discard it"
                else -> "Kept as ${job.title}"
            }
        }

    /** The notification's title for the job running. */
    fun notificationTitle(job: StudioJob): String =
        if (job.kind == JobKind.Download) "Downloading the ${job.name.lowercase(Locale.ROOT)}" else "Transcribing ${job.name}"

    /** The notification left when the last job has ended: how it went. */
    fun ended(job: StudioJob): String = when {
        job.state == JobState.Done && job.kind == JobKind.Download -> "The ${job.name.lowercase(Locale.ROOT)} is installed"
        job.state == JobState.Done -> "${job.title} is in the library"
        job.state == JobState.Failed && job.kind == JobKind.Download -> "The ${job.name.lowercase(Locale.ROOT)} didn't download"
        job.state == JobState.Failed -> "The transcription didn't finish"
        else -> "Cancelled"
    }

    /** Under a finished piece's notification: what to do next. */
    const val LISTEN = "Listen, then keep it or discard it."

    /** Now playing's banner. */
    const val REVIEW_TITLE = "Keep this piece?"
    const val REVIEW_LINE = "Made in Studio from a recording. Discard deletes it."

    /** A share as a whole percentage, rounded down (100% only when it is done), a float's last bit forgiven. */
    fun percentOf(fraction: Float): Int = (fraction * 100 + 0.001f).toInt().coerceIn(0, 100)

    /** "42 of 125 MB", whole decimal megabytes. */
    fun megabytes(bytes: Long, total: Long, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%d of %d MB", (bytes.coerceAtLeast(0) / 1_000_000.0).roundToInt(), (total.coerceAtLeast(0) / 1_000_000.0).roundToInt())
}
