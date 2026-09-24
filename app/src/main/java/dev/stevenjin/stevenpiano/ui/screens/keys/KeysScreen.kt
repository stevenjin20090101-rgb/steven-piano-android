// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader

/** The Keys destination. The playable keyboard itself arrives in the next step of M8. */
@Composable
fun KeysScreen(onOpenPiano: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Keys")
    }
}
