// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassSheet
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider

/** What the system pickers may offer. Some file managers call a .mid file octet-stream. */
private val MidiTypes = arrayOf("audio/midi", "audio/mid", "audio/x-midi", "application/x-midi", "application/octet-stream")
private val ZipTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/** The three ways in: files, a folder (read recursively), a zip. */
class ImportPickers(
    private val files: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    private val folder: ManagedActivityResultLauncher<Uri?, Uri?>,
    private val zip: ManagedActivityResultLauncher<Array<String>, Uri?>,
) {
    fun addFiles() = files.launch(MidiTypes)

    fun addFolder() = folder.launch(null)

    fun addZip() = zip.launch(ZipTypes)
}

@Composable
fun rememberImportPickers(onChosen: (ImportSource) -> Unit): ImportPickers {
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onChosen(ImportSource.Uris(uris))
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onChosen(ImportSource.Tree(uri))
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onChosen(ImportSource.Zip(uri))
    }
    return remember(files, folder, zip) { ImportPickers(files, folder, zip) }
}

/**
 * One of Studio's entries on the `+` sheet: [label] ("Transcribe a recording…", v1.7 — M23; "Compose a
 * piece…", M24), its line under it, and what a tap does (the system's audio picker; the compose sheet).
 */
class StudioEntry(val label: String, val detail: String, val onClick: () -> Unit)

/**
 * The `+` sheet: Add files, Add folder, Add zip; then, set apart by a hairline, Fetch artwork and
 * notes for every composer ([onFetchArtwork]), which asks again even for composers not found
 * before; then, below another hairline, Studio's entries ([studio]: Transcribe a recording… and
 * Compose a piece…; none on a device Studio can't run on).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(pickers: ImportPickers, onFetchArtwork: () -> Unit, onDismiss: () -> Unit, studio: List<StudioEntry> = emptyList()) {
    GlassSheet(
        onDismissRequest = onDismiss,
        // Open all the way: with Studio's entry the sheet is taller than half a phone's screen, where it would open half up and cut its last row.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            "Add MIDI files",
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SheetOption("Add files", "One or more MIDI files.") {
            onDismiss()
            pickers.addFiles()
        }
        SheetOption("Add folder", "Every MIDI file inside, subfolders too. An INDEX.csv fills in playlists and composers.") {
            onDismiss()
            pickers.addFolder()
        }
        SheetOption("Add zip", "A zip of MIDI files, such as ALL-SONGS.zip.") {
            onDismiss()
            pickers.addZip()
        }
        HairlineDivider(Modifier.padding(vertical = 8.dp))
        SheetOption("Fetch artwork and notes for every composer", "Portraits and notes from Wikipedia. Nothing about you is sent.") {
            onDismiss()
            onFetchArtwork()
        }
        if (studio.isNotEmpty()) HairlineDivider(Modifier.padding(vertical = 8.dp))
        for (entry in studio) {
            SheetOption(entry.label, entry.detail) {
                onDismiss()
                entry.onClick()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SheetOption(label: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Eyebrow(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
    }
}
