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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.library.PackState
import dev.stevenjin.stevenpiano.ui.LibraryCopy
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassSheet

/**
 * Before Steven's library first comes onto a tablet (v1.10 — M27): what it is (its pieces and its size,
 * when the pack is known, [offer]), the three collections' credits as their licences ask, and "For
 * non-commercial use", then Not now and **Load · 61 MB** ([onLoad]). Dismissing it is Not now. Updates of
 * the library don't show it again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryLicenceSheet(offer: PackState.Offered?, onLoad: () -> Unit, onDismiss: () -> Unit) {
    // Open all the way: the credits are taller than half a phone's screen.
    GlassSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                LibraryCopy.SHEET_TITLE,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                LibraryCopy.sheetIntro(offer),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Eyebrow(LibraryCopy.CREDITS, Modifier.padding(top = 24.dp, bottom = 4.dp))
            for (credit in LibraryCopy.CREDIT_LINES) {
                Text(
                    credit.name,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Eyebrow(credit.line, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
            }
            Text(
                LibraryCopy.NON_COMMERCIAL_NOTE,
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(LibraryCopy.NOT_NOW) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onLoad) { Text(LibraryCopy.loadButton(offer)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
