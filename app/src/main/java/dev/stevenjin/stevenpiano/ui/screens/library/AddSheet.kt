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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.ui.components.Eyebrow

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

/** The `+` sheet: Add files, Add folder, Add zip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(pickers: ImportPickers, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceVariant) {
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
        SheetOption("Add folder", "Every MIDI file inside, subfolders too. An INDEX.csv fills in collections and composers.") {
            onDismiss()
            pickers.addFolder()
        }
        SheetOption("Add zip", "A zip of MIDI files, such as ALL-SONGS.zip.") {
            onDismiss()
            pickers.addZip()
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
