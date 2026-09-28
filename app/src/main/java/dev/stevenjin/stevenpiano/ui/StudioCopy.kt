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
import dev.stevenjin.stevenpiano.studio.StudioPieces
import dev.stevenjin.stevenpiano.studio.StudioSupport
import java.util.Locale
import kotlin.math.roundToInt

/** What Studio says (DESIGN.md › v1.7 — M23 and M24): its page, the hub's row, the Library's + sheet, the notification, the panel. */
object StudioCopy {
    const val TRANSCRIBE = "Transcribe a recording…"
    const val TRANSCRIBE_NOTE = "Any piano recording. About a minute per three minutes of audio."

    /** Composing (v1.7 — M24): the page's button, the + sheet's entry, and what it takes. */
    const val COMPOSE = "Compose a piece…"
    const val COMPOSE_NOTE = "Runs on this tablet. About a minute for a two-minute piece."

    /** Under the + sheet's entry and the page's button while the model isn't here yet: "Downloads the transcription model (125 MB) first." */
    fun downloadsFirst(model: ModelEntry): String = "Downloads the ${model.title.lowercase(Locale.ROOT)} model (${size(model.sizeBytes)}) first."

    /** [note], and that the model downloads first when it isn't [installed]. */
    fun withDownload(note: String, model: ModelEntry, installed: Set<String>): String =
        if (model.name in installed) note else "$note ${downloadsFirst(model)}"

    /** The hub's line in CONTROL where Studio's row would be, on a device that can't run it. */
    fun unsupported(support: StudioSupport): String? = when (support) {
        StudioSupport.NoRuntime -> "Studio isn't available on this device"
        StudioSupport.TooLittleMemory -> "This tablet doesn't have enough memory for Studio"
        else -> null
    }

    /**
     * The hub row's value: the job running ("Transcribing 42%", "Composing 42%", "Downloading 42%"),
     * "Waiting" while one waits, else how many models are installed ("2 models", "1 model", "No models").
     */
    fun hub(installed: Int, jobs: List<StudioJob>): String {
        val running = jobs.firstOrNull { it.state == JobState.Running }
        if (running != null) {
            val verb = when (running.kind) {
                JobKind.Download -> "Downloading"
                JobKind.Transcribe -> "Transcribing"
                JobKind.Compose -> "Composing"
            }
            val pct = running.progress?.takeIf { running.step in MEASURED }
            return if (pct == null) verb else "$verb ${Format.percent(percentOf(pct))}"
        }
        if (jobs.any { it.state == JobState.Queued }) return "Waiting"
        return when (installed) {
            0 -> "No models"
            1 -> "1 model"
            else -> "$installed models"
        }
    }

    /** The steps that have a measure: their percentage shows. */
    private val MEASURED = setOf(JobStep.Downloading, JobStep.Transcribing, JobStep.Composing)

    /** "125 MB · CC BY 4.0". */
    fun modelLine(model: ModelEntry): String = "${size(model.sizeBytes)} · ${model.licenceLabel}"

    /** Whole decimal megabytes, as Android counts them: "125 MB". */
    fun size(bytes: Long): String = "${(bytes / 1_000_000.0).roundToInt()} MB"

    /**
     * A job's line: what it is doing ("Downloading · 42 of 125 MB", "Reading the recording…",
     * "Transcribing · 42%", "Composing · 42%", "Adding it to the library…"), or how it ended: a model
     * "Installed"; a piece waiting for Keep or Discard ([undecided]), "Kept as <title>", or "Discarded"
     * ([discarded]); the failure's own line; "Cancelled".
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
                JobStep.Composing -> "Composing · ${Format.percent(percentOf(job.progress ?: 0f))}"
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

    /** What a job is called while it waits or runs: a recording's name, a model's, or for a composition the piece it is in the manner of. */
    fun jobName(job: StudioJob): String = if (job.kind == JobKind.Compose) "In the manner of ${job.name}" else job.name

    /** A job's name where it is listed: the piece it made once done, else [jobName]. */
    fun jobTitle(job: StudioJob): String = if (job.state == JobState.Done && job.title != null) job.title else jobName(job)

    /** The notification's title for the job running. */
    fun notificationTitle(job: StudioJob): String = when (job.kind) {
        JobKind.Download -> "Downloading the ${job.name.lowercase(Locale.ROOT)}"
        JobKind.Transcribe -> "Transcribing ${job.name}"
        JobKind.Compose -> "Composing in the manner of ${job.name}"
    }

    /**
     * The Library's line while a job runs (under its import bar), so a job started from the + sheet
     * shows where it was started: "Transcribing Clair de lune.m4a · 42%", "Composing in the manner of
     * Clair de lune · 42%", "Downloading the transcription model · 42 of 125 MB", or what the step is doing.
     */
    fun libraryLine(job: StudioJob, locale: Locale = Locale.getDefault()): String = when {
        job.kind == JobKind.Download && job.step == JobStep.Downloading -> "${notificationTitle(job)} · ${megabytes(job.bytes, job.total, locale)}"
        job.kind == JobKind.Download -> notificationTitle(job)
        job.step == JobStep.Transcribing || job.step == JobStep.Composing -> "${notificationTitle(job)} · ${Format.percent(percentOf(job.progress ?: 0f))}"
        job.step == JobStep.Reading -> "Reading ${job.name}…"
        job.step == JobStep.Saving && job.kind == JobKind.Compose -> "Adding the composition to the library…"
        job.step == JobStep.Saving -> "Adding ${job.name} to the library…"
        else -> notificationTitle(job)
    }

    /** The notification left when the last job has ended: how it went. */
    fun ended(job: StudioJob): String = when {
        job.state == JobState.Done && job.kind == JobKind.Download -> "The ${job.name.lowercase(Locale.ROOT)} is installed"
        job.state == JobState.Done -> "${job.title} is in the library"
        job.state == JobState.Failed && job.kind == JobKind.Download -> "The ${job.name.lowercase(Locale.ROOT)} didn't download"
        job.state == JobState.Failed && job.kind == JobKind.Compose -> "The composition didn't finish"
        job.state == JobState.Failed -> "The transcription didn't finish"
        else -> "Cancelled"
    }

    /** Under a finished piece's notification: what to do next. */
    const val LISTEN = "Listen, then keep it or discard it."

    /** About's credit for the models Studio downloads (their licences in AUTHORS and third_party/). */
    const val MODELS_CREDIT = "Studio models: ByteDance piano transcription (CC BY 4.0) · Anticipatory Music Transformer (Apache-2.0)"

    /** Now playing's banner. */
    const val REVIEW_TITLE = "Keep this piece?"
    const val REVIEW_LINE = "Made in Studio from a recording. Discard deletes it."

    /**
     * The banner's line for the piece whose sheet reads [description]: a composition's says what it is in
     * the manner of ("Composed in Studio in the manner of Clair de lune (Claude Debussy). Discard deletes
     * it."; [StudioPieces.compositionDescription] wrote it), a transcription's [REVIEW_LINE].
     */
    fun reviewLine(description: String?): String {
        val manner = StudioPieces.mannerOf(description) ?: return REVIEW_LINE
        return "Composed in Studio in the manner of $manner. Discard deletes it."
    }

    /** A share as a whole percentage, rounded down (100% only when it is done), a float's last bit forgiven. */
    fun percentOf(fraction: Float): Int = (fraction * 100 + 0.001f).toInt().coerceIn(0, 100)

    /** "42 of 125 MB", whole decimal megabytes. */
    fun megabytes(bytes: Long, total: Long, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%d of %d MB", (bytes.coerceAtLeast(0) / 1_000_000.0).roundToInt(), (total.coerceAtLeast(0) / 1_000_000.0).roundToInt())
}
