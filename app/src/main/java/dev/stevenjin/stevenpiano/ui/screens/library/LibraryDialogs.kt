// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.data.db.CollectionSummary
import dev.stevenjin.stevenpiano.data.db.PieceEntity

/** The dialogs a long-press can open. */
sealed interface LibraryDialog {
    data class AddToCollection(val piece: PieceEntity) : LibraryDialog

    data class Rename(val piece: PieceEntity) : LibraryDialog

    data class Delete(val piece: PieceEntity) : LibraryDialog

    data class RenameCollection(val collection: CollectionSummary) : LibraryDialog

    data class DeleteCollection(val collection: CollectionSummary) : LibraryDialog
}

@Composable
fun LibraryDialogs(dialog: LibraryDialog, vm: LibraryViewModel, onClose: () -> Unit) {
    when (dialog) {
        is LibraryDialog.AddToCollection -> AddToCollectionDialog(dialog.piece, vm, onClose)
        is LibraryDialog.Rename -> RenamePieceDialog(dialog.piece, onClose) { title, composer ->
            vm.rename(dialog.piece, title, composer)
            onClose()
        }
        is LibraryDialog.Delete -> ConfirmDialog(
            title = "Delete “${dialog.piece.title}”?",
            text = "It will be removed from the library and from this phone. This can't be undone.",
            confirm = "Delete piece",
            onClose = onClose,
        ) { vm.delete(dialog.piece) }
        is LibraryDialog.RenameCollection -> RenameCollectionDialog(dialog.collection, vm, onClose)
        is LibraryDialog.DeleteCollection -> ConfirmDialog(
            title = "Delete “${dialog.collection.name}”?",
            text = "Only the collection goes. Its pieces stay in the library.",
            confirm = "Delete collection",
            onClose = onClose,
        ) { vm.deleteCollection(dialog.collection.id) }
    }
}

/** Tick the collections the piece belongs in, or name a new one. Changes apply as they are made. */
@Composable
private fun AddToCollectionDialog(piece: PieceEntity, vm: LibraryViewModel, onClose: () -> Unit) {
    val collections by remember { vm.collections }.collectAsStateWithLifecycle(emptyList())
    val member by remember(piece.id) { vm.membershipOf(piece.id) }.collectAsStateWithLifecycle(emptyList())
    var newName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { DialogTitle("Add to collection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    piece.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(collections, key = { it.id }) { collection ->
                        val checked = collection.id in member
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(checked, role = Role.Checkbox) { vm.setMembership(collection.id, piece.id, it) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(collection.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("New collection") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    TextButton(
                        onClick = {
                            vm.addToNewCollection(newName.trim(), piece.id)
                            newName = ""
                        },
                        enabled = newName.isNotBlank(),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
private fun RenamePieceDialog(piece: PieceEntity, onClose: () -> Unit, onRename: (String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(piece.title) }
    var composer by rememberSaveable { mutableStateOf(piece.composer) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { DialogTitle("Rename") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NameField(title, { title = it }, "Title")
                NameField(composer, { composer = it }, "Composer")
            }
        },
        confirmButton = { TextButton(onClick = { onRename(title, composer) }, enabled = title.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun RenameCollectionDialog(collection: CollectionSummary, vm: LibraryViewModel, onClose: () -> Unit) {
    val others by remember { vm.collections }.collectAsStateWithLifecycle(emptyList())
    var name by rememberSaveable { mutableStateOf(collection.name) }
    val taken = others.any { it.id != collection.id && it.name.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { DialogTitle("Rename") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NameField(name, { name = it }, "Name")
                if (taken) Text("Another collection has that name.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.renameCollection(collection.id, name)
                    onClose()
                },
                enabled = name.isNotBlank() && !taken,
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onClose: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { DialogTitle(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onClose()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun DialogTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )
}
