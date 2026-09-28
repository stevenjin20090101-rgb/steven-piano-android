// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.studio.JobKind
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.ModelEntry
import dev.stevenjin.stevenpiano.studio.StudioFailures
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.studio.StudioSupport
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel

/** What the system's document picker offers for a recording: any audio (m4a, mp3, wav, flac, ogg…), and Ogg some providers label as an application. */
private val RecordingTypes = arrayOf("audio/*", "application/ogg")

/** The system's document picker for one recording; [onPicked] hears the document chosen. */
@Composable
fun rememberRecordingPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(uri) }
    return remember(launcher) { { launcher.launch(RecordingTypes) } }
}

/**
 * Studio (DESIGN.md › v1.7 — M23): MODELS, each with its size and licence, and Download, its progress
 * (with Cancel), or Installed with Remove; TRANSCRIBE, "Transcribe a recording…" (the system's audio
 * picker) with what it takes, and the memory line when a gate refused the last try; JOBS, newest first,
 * each with its line, a hairline while it runs, Cancel while it waits or runs, and Listen while its
 * piece waits for Keep or Discard ([onListen] plays it and shows Now playing). On a device Studio can't
 * run on, the page says why and nothing else.
 */
@Composable
fun StudioPage(vm: PianoViewModel, onListen: (Long) -> Unit) {
    val support by vm.studioSupport.collectAsStateWithLifecycle()
    val installed by vm.studioModels.collectAsStateWithLifecycle()
    val jobs by vm.studioJobs.collectAsStateWithLifecycle()
    val undecided by vm.studioUndecided.collectAsStateWithLifecycle()
    val discarded by vm.studioDiscarded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pick = rememberRecordingPicker { uri -> vm.transcribe(context, uri) }

    val unsupported = StudioCopy.unsupported(support)
    if (unsupported != null) {
        SectionEyebrow("Studio")
        NoteLine("$unsupported.")
        return
    }

    SectionEyebrow("Models")
    for (model in ModelCatalogue.all) {
        val download = jobs.lastOrNull { it.kind == JobKind.Download && it.model == model.name }
        ModelRow(model, installed = model.name in installed, download = download, vm = vm)
    }

    SectionEyebrow("Transcribe")
    val transcription = ModelCatalogue.transcription
    val note = if (transcription.name in installed) StudioCopy.TRANSCRIBE_NOTE else "${StudioCopy.TRANSCRIBE_NOTE} ${StudioCopy.downloadsFirst(transcription)}"
    ActionRow(note = note) {
        ActionButton(StudioCopy.TRANSCRIBE, onClick = pick, enabled = support == StudioSupport.Available)
    }
    val refused = jobs.lastOrNull { it.kind == JobKind.Transcribe && it.state.finished }?.takeIf { it.state == JobState.Failed && it.error in MemoryLines }
    if (refused != null) NoteLine(refused.error!!, Modifier.semantics { liveRegion = LiveRegionMode.Polite })

    if (jobs.isNotEmpty()) {
        SectionEyebrow("Jobs")
        for (job in jobs.asReversed()) JobRow(job, undecided, discarded, onCancel = { vm.cancelStudioJob(job.id) }, onListen = onListen)
    }
}

/** The lines that say the tablet's memory refused: the page repeats them under Transcribe. */
private val MemoryLines = setOf(StudioFailures.BUSY, StudioFailures.RAN_OUT, StudioFailures.TOO_LITTLE_MEMORY)

/** A model: its name, "125 MB · CC BY 4.0" and what it is for; Download, its progress and Cancel, or Installed and Remove. */
@Composable
private fun ModelRow(model: ModelEntry, installed: Boolean, download: StudioJob?, vm: PianoViewModel) {
    val running = download?.takeIf { !it.state.finished }
    val line = when {
        running != null -> StudioCopy.jobLine(running)
        installed -> "Installed · ${StudioCopy.modelLine(model)}"
        download?.state == JobState.Failed -> download.error ?: StudioCopy.modelLine(model)
        else -> "${StudioCopy.modelLine(model)} · ${model.use}"
    }
    StudioRow(
        title = model.title,
        line = line,
        progress = running?.takeIf { it.state == JobState.Running }?.let { it.progress ?: 0f },
    ) {
        when {
            running != null -> ActionButton("Cancel", onClick = { vm.cancelStudioJob(running.id) }, description = "Cancel the download of the ${model.title.lowercase()} model")
            installed -> ActionButton("Remove", onClick = { vm.removeModel(model) }, description = "Remove the ${model.title.lowercase()} model")
            else -> ActionButton("Download", onClick = { vm.downloadModel(model) }, description = "Download the ${model.title.lowercase()} model, ${StudioCopy.size(model.sizeBytes)}")
        }
    }
}

/** A job: what it is, its line, a hairline while it runs, Cancel while it waits or runs, Listen while its piece waits for Keep or Discard. */
@Composable
private fun JobRow(job: StudioJob, undecided: Set<Long>, discarded: Set<Long>, onCancel: () -> Unit, onListen: (Long) -> Unit) {
    val piece = job.pieceId?.takeIf { job.state == JobState.Done && it in undecided }
    StudioRow(
        title = if (job.state == JobState.Done && job.title != null) job.title else job.name,
        line = StudioCopy.jobLine(job, undecided, discarded),
        progress = if (job.state == JobState.Running) job.progress ?: -1f else null,
        buttons = if (!job.state.finished || piece != null) {
            {
                if (!job.state.finished) ActionButton("Cancel", onClick = onCancel, description = "Cancel ${job.name}")
                if (piece != null) ActionButton("Listen", onClick = { onListen(piece) }, description = "Listen to ${job.title}")
            }
        } else {
            null
        },
    )
}

/**
 * One of the page's rows: [title] in Body, [line] under it in the eyebrow's size, sentence case; a
 * hairline of [progress] while something runs (below zero: under way, no measure), then [buttons].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioRow(title: String, line: String, progress: Float?, buttons: (@Composable () -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Eyebrow(
            line,
            Modifier
                .padding(top = 2.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            uppercase = false,
        )
        if (progress != null) ProgressHairline(progress.takeIf { it >= 0f }, Modifier.padding(top = 10.dp))
        if (buttons != null) {
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { buttons() }
        }
    }
    HairlineDivider(startInset = 16.dp)
}
