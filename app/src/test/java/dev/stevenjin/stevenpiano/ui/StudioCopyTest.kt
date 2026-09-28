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
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.StudioFailures
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.studio.StudioJobs
import dev.stevenjin.stevenpiano.studio.StudioSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** What Studio says (v1.7 — M23): the hub's value, the models' lines, each job's line, the notification. */
class StudioCopyTest {
    private val transcribing = StudioJob(1, JobKind.Transcribe, "Clair de lune.m4a", JobState.Running, JobStep.Transcribing, 0.42f)
    private val downloading = StudioJob(2, JobKind.Download, "Transcription model", JobState.Running, JobStep.Downloading, 0.34f, bytes = 42_300_000, total = 124_511_036)

    @Test
    fun `the hub reads the job running, else the models installed`() {
        assertEquals("Transcribing 42%", StudioCopy.hub(1, listOf(transcribing)))
        assertEquals("Downloading 34%", StudioCopy.hub(0, listOf(downloading)))
        assertEquals("Transcribing", StudioCopy.hub(1, listOf(transcribing.copy(step = JobStep.Reading, progress = null))))
        assertEquals("Waiting", StudioCopy.hub(1, listOf(transcribing.copy(state = JobState.Queued))))
        assertEquals("No models", StudioCopy.hub(0, emptyList()))
        assertEquals("1 model", StudioCopy.hub(1, listOf(transcribing.copy(state = JobState.Done))))
        assertEquals("2 models", StudioCopy.hub(2, emptyList()))
    }

    @Test
    fun `a model reads its size and licence`() {
        assertEquals("125 MB · CC BY 4.0", StudioCopy.modelLine(ModelCatalogue.transcription))
        assertEquals("173 MB · Apache 2.0", StudioCopy.modelLine(ModelCatalogue.composer))
        assertEquals("Downloads the transcription model (125 MB) first.", StudioCopy.downloadsFirst(ModelCatalogue.transcription))
        assertEquals("Studio isn't available on this device", StudioCopy.unsupported(StudioSupport.NoRuntime))
        assertEquals("This tablet doesn't have enough memory for Studio", StudioCopy.unsupported(StudioSupport.TooLittleMemory))
        assertNull(StudioCopy.unsupported(StudioSupport.Available))
        assertNull(StudioCopy.unsupported(StudioSupport.Checking))
        assertEquals(
            "Studio models: ByteDance piano transcription (CC BY 4.0) · Anticipatory Music Transformer (Apache-2.0)",
            StudioCopy.MODELS_CREDIT,
        )
    }

    @Test
    fun `each job says what it is doing, or how it ended`() {
        assertEquals("Downloading · 42 of 125 MB", StudioCopy.jobLine(downloading, locale = Locale.US))
        assertEquals("Transcribing · 42%", StudioCopy.jobLine(transcribing))
        assertEquals("Reading the recording…", StudioCopy.jobLine(transcribing.copy(step = JobStep.Reading)))
        assertEquals("Adding it to the library…", StudioCopy.jobLine(transcribing.copy(step = JobStep.Saving)))
        assertEquals("Waiting", StudioCopy.jobLine(transcribing.copy(state = JobState.Queued)))
        assertEquals("Installed", StudioCopy.jobLine(downloading.copy(state = JobState.Done)))
        val made = transcribing.copy(state = JobState.Done, pieceId = 9, title = "Clair de lune")
        assertEquals("Ready: listen, then keep it or discard it", StudioCopy.jobLine(made, undecided = setOf(9L)))
        assertEquals("Kept as Clair de lune", StudioCopy.jobLine(made))
        assertEquals("Discarded", StudioCopy.jobLine(made, discarded = setOf(9L)))
        assertEquals(StudioFailures.BUSY, StudioCopy.jobLine(transcribing.copy(state = JobState.Failed, error = StudioFailures.BUSY)))
        assertEquals("Cancelled", StudioCopy.jobLine(transcribing.copy(state = JobState.Cancelled)))
        assertEquals("Transcribing Clair de lune.m4a", StudioCopy.notificationTitle(transcribing))
        assertEquals("Downloading the transcription model", StudioCopy.notificationTitle(downloading))
        assertEquals("Clair de lune is in the library", StudioCopy.ended(made))
        assertEquals("The transcription model is installed", StudioCopy.ended(downloading.copy(state = JobState.Done)))
        assertEquals("The transcription didn't finish", StudioCopy.ended(transcribing.copy(state = JobState.Failed)))
    }

    @Test
    fun `the Library's line names the job running and how far it is`() {
        assertEquals("Transcribing Clair de lune.m4a · 42%", StudioCopy.libraryLine(transcribing))
        assertEquals("Reading Clair de lune.m4a…", StudioCopy.libraryLine(transcribing.copy(step = JobStep.Reading)))
        assertEquals("Adding Clair de lune.m4a to the library…", StudioCopy.libraryLine(transcribing.copy(step = JobStep.Saving)))
        assertEquals("Downloading the transcription model · 42 of 125 MB", StudioCopy.libraryLine(downloading, Locale.US))
        assertEquals("Downloading the transcription model", StudioCopy.libraryLine(downloading.copy(step = JobStep.Waiting)))
    }

    @Test
    fun `the jobs list keeps those waiting and running, and the last twenty that ended`() {
        val jobs = StudioJobs()
        val ids = (1..25).map { jobs.add(JobKind.Transcribe, "take $it").id }
        ids.take(24).forEach { id -> jobs.update(id) { it.copy(state = JobState.Done) } }
        val list = jobs.jobs.value
        assertEquals(21, list.size)
        assertEquals("the oldest that ended go first", (5L..25L).toList(), list.map { it.id })
        assertEquals(JobState.Queued, jobs.get(25)!!.state)
        assertEquals(true, jobs.busy)
        assertNull(jobs.running)
        jobs.update(25) { it.copy(state = JobState.Running) }
        assertEquals(25L, jobs.running?.id)
    }
}
