// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.Provenance
import dev.stevenjin.stevenpiano.ui.ArtworkCopy
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.ui.TabletSoundCopy
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.CloudCopy

private const val LIBRARY_SOURCES =
    "Library sources: MAESTRO (Google Magenta, CC BY-NC-SA 4.0) · piano-midi.de (Bernd Krüger, CC BY-SA) · " +
        "Mutopia Project (public domain)"

/**
 * The very bottom of the Piano tab: who made the app, the sources' credit, Studio's models and their
 * licences (v1.7 — M23), the tablet's piano sound (v1.8 — M25), what the app sends to the internet (nothing about the person), what
 * the relay sees when remote access over the internet is on (v1.10, audit delta 3) and the credit
 * for Wikipedia's text and Wikimedia Commons' portraits, in the eyebrow style. The provenance line
 * keeps its own case so the fingerprint reads exactly as it is published.
 */
@Composable
fun AboutRow(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Eyebrow(Provenance.text, uppercase = false)
        Spacer(Modifier.height(8.dp))
        Eyebrow(LIBRARY_SOURCES, uppercase = false)
        Spacer(Modifier.height(8.dp))
        Eyebrow(StudioCopy.MODELS_CREDIT, uppercase = false)
        Spacer(Modifier.height(8.dp))
        Eyebrow(TabletSoundCopy.CREDIT, uppercase = false)   // v1.8 — M25
        Spacer(Modifier.height(8.dp))
        Eyebrow(ArtworkCopy.TRANSPARENCY, uppercase = false)
        Eyebrow(ArtworkCopy.ATTRIBUTION, uppercase = false)
        Spacer(Modifier.height(8.dp))
        Eyebrow(CloudCopy.ABOUT, uppercase = false)   // v1.10, audit delta 3
    }
}
