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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.components.GlassSheet
import dev.stevenjin.stevenpiano.ui.ImportCopy

/**
 * Files another app sent ("Open with", Share) are not imported until the person says so: a
 * bottom sheet asks "Add 3 files to the library?" with Add and Cancel. Dismissing it is Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(count: Int, onAdd: () -> Unit, onCancel: () -> Unit) {
    GlassSheet(onDismissRequest = onCancel) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                ImportCopy.addShared(count),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                ImportCopy.sharedDetail(count),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAdd) { Text("Add") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
