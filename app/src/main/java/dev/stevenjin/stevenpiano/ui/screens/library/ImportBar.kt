// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.art.ArtworkProgress
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.library.PackState
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.ui.ArtworkCopy
import dev.stevenjin.stevenpiano.ui.ImportCopy
import dev.stevenjin.stevenpiano.ui.LibraryCopy
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.secondaryText
import dev.stevenjin.stevenpiano.ui.theme.Tabular

/**
 * An import in the background: "Imported 1,204 of 1,727" over a hairline progress line, then a
 * one-line summary until it is dismissed. Duplicates are skipped without comment.
 */
@Composable
fun ImportBar(progress: ImportProgress, dismissed: ImportProgress?, onDismiss: (ImportProgress) -> Unit) {
    val style = MaterialTheme.typography.bodyLarge.merge(Tabular)
    // In the Library's header: the content colour while the list is scrolled beneath its glass.
    val color = secondaryText()
    when {
        !progress.finished -> ProgressRow(ImportCopy.running(progress), if (progress.total == 0) null else progress.done.toFloat() / progress.total)
        progress.total > 0 && progress !== dismissed -> Column {
            Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ImportCopy.summary(progress), Modifier.weight(1f), style = style, color = color)
                GlyphButton(R.drawable.ic_close, "Dismiss") { onDismiss(progress) }
            }
            HairlineDivider()
        }
    }
}

/**
 * Steven's library arriving (v1.10 — M27): "Loading Steven's library · 23 of 61 MB" over the same hairline
 * progress line imports use while the pack downloads (its pieces then show in [ImportBar] as any import's
 * do); a load that stopped says why, with Dismiss ([onDismiss]). Nothing otherwise.
 */
@Composable
fun LibraryBar(state: PackState, onDismiss: () -> Unit) {
    val line = LibraryCopy.bar(state)
    when {
        line != null -> ProgressRow(line, LibraryCopy.progress(state))
        state is PackState.Failed -> Column {
            Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.line, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = secondaryText())
                GlyphButton(R.drawable.ic_close, "Dismiss") { onDismiss() }
            }
            HairlineDivider()
        }
    }
}

/**
 * Artwork arriving in the background: "Fetching artwork 12 of 61" over the same hairline progress
 * line imports use. Nothing while no background fetch runs (a sheet's own fetch shows in the sheet).
 */
@Composable
fun ArtworkBar(progress: ArtworkProgress) {
    if (progress.idle || progress.total == 0) return
    ProgressRow(ArtworkCopy.running(progress), progress.done.toFloat() / progress.total)
}

/**
 * Studio's job running (v1.7 — M23): "Transcribing Clair de lune.m4a · 42%" over the same hairline
 * progress line, measured while it downloads or transcribes. Nothing while no job runs.
 */
@Composable
fun StudioBar(jobs: List<StudioJob>) {
    val job = jobs.firstOrNull { it.state == JobState.Running } ?: return
    // v1.12 (M30): the job's own measure (composing's too: it used to sweep while its line said 42%).
    ProgressRow(StudioCopy.libraryLine(job), StudioCopy.libraryProgress(job))
}

/** A line of progress copy over a hairline progress line; [progress] null is indeterminate. */
@Composable
private fun ProgressRow(text: String, progress: Float?) {
    Column {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge.merge(Tabular),
            color = secondaryText(),
        )
        ProgressHairline(progress)
    }
}
