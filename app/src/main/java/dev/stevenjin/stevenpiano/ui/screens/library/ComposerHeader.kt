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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.net.WikipediaUrls
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.WikipediaLink

/**
 * A composer's page head: back ([backLabel], "Back to composers", or under Modern "Back to artists":
 * v1.14 — M37), the [portrait] at 96 dp beside the name in Title over [meta] ("12 pieces"), then
 * [blurb] (the first two sentences of their Wikipedia text, when there is one) with a "From
 * Wikipedia" link to [sourceUrl], since that text is Wikipedia's.
 */
@Composable
fun ComposerHeader(
    portrait: @Composable (Modifier) -> Unit,
    name: String,
    meta: String,
    blurb: String?,
    sourceUrl: String?,
    onBack: () -> Unit,
    backLabel: String = "Back to composers",
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(R.drawable.ic_back, backLabel, onClick = onBack)
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            portrait(Modifier.size(PORTRAIT))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    name,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Eyebrow(meta)
            }
        }
        if (!blurb.isNullOrBlank()) {
            Text(
                blurb,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (WikipediaUrls.pageLink(sourceUrl) != null) {
                Column(Modifier.padding(horizontal = 4.dp)) { WikipediaLink(sourceUrl) }
            } else {
                Spacer(Modifier.height(16.dp))
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
        HairlineDivider()
    }
}

private val PORTRAIT = 96.dp
