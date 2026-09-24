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
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.theme.Tabular

/**
 * An import in the background: "Imported 1,204 of 1,727" over a hairline progress line, then a
 * one-line summary until it is dismissed. Duplicates are skipped without comment.
 */
@Composable
fun ImportBar(progress: ImportProgress, dismissed: ImportProgress?, onDismiss: (ImportProgress) -> Unit) {
    val style = MaterialTheme.typography.bodyLarge.merge(Tabular)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        !progress.finished -> Column {
            Text(importRunning(progress), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = style, color = color)
            ProgressHairline(if (progress.total == 0) null else progress.done.toFloat() / progress.total)
        }
        progress.total > 0 && progress !== dismissed -> Column {
            Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(importSummary(progress), Modifier.weight(1f), style = style, color = color)
                GlyphButton(R.drawable.ic_close, "Dismiss") { onDismiss(progress) }
            }
            HairlineDivider()
        }
    }
}

internal fun importRunning(progress: ImportProgress): String =
    if (progress.total == 0) "Looking for MIDI files…" else "Imported ${Format.count(progress.done)} of ${Format.count(progress.total)}"

/** "Imported 12 pieces. 1 file couldn't be read." */
internal fun importSummary(progress: ImportProgress): String {
    val imported = when {
        progress.imported > 0 -> "Imported ${Format.count(progress.imported, "piece", "pieces")}."
        progress.failed > 0 -> null
        progress.duplicates == 1 -> "That piece is already in the library."
        else -> "Those pieces are already in the library."
    }
    val failed = if (progress.failed > 0) "${Format.count(progress.failed, "file", "files")} couldn't be read." else null
    return listOfNotNull(imported, failed).joinToString(" ")
}
