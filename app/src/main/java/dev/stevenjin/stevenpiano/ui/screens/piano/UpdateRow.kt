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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.UpdateCopy
import dev.stevenjin.stevenpiano.ui.components.FilledButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.update.UpdateState

/**
 * The Piano tab's UPDATE block, on THIS TABLET › Updates since v1.13 (M31b; on the hub until then, whose
 * Updates row now names it: "1.4 available"), while a newer release is known or
 * has just been installed (DESIGN.md › v1.4 › Updates, v1.5): the eyebrow header, "Steven Piano 1.4 is available"
 * in Body, the release notes in the secondary colour, and one filled Update button; while it
 * downloads, a hairline progress row ("Downloading 1.4 · 1.2 of 2.3 MB", tabular figures); after a
 * silent install, "Updated to 1.4; restart to use it" with Restart, or just "Updated to 1.4" when
 * Android has already restarted the app on the new version. A failure is one line under
 * the button, in words, and Update is the retry. On a tablet that is not the device owner and has
 * not allowed this app to install updates yet ([canInstall] false), a line says so with the way to
 * the setting. Monochrome throughout; no badge anywhere else.
 */
@Composable
fun UpdateRow(state: UpdateState, canInstall: Boolean, onUpdate: () -> Unit, onRestart: () -> Unit, onAllowInstalls: () -> Unit) {
    val manifest = state.manifest
    if (manifest == null && state !is UpdateState.Installed) return
    SectionEyebrow("Update")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val title = when {
            state is UpdateState.Installed -> UpdateCopy.installed(state.version, state.restartNeeded)
            manifest == null -> return@Column
            state is UpdateState.ReadyToInstall -> UpdateCopy.ready(manifest.versionName)
            state is UpdateState.Installing -> UpdateCopy.installing(manifest.versionName)
            else -> UpdateCopy.available(manifest.versionName)
        }
        Text(
            title,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (manifest != null && manifest.notes.isNotBlank() && state !is UpdateState.Installing) {
            Text(
                manifest.notes,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            is UpdateState.Downloading -> {
                Text(
                    UpdateCopy.downloading(state.manifest.versionName, state.bytes, state.total),
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProgressHairline(if (state.total > 0) state.bytes.toFloat() / state.total else null, Modifier.padding(top = 8.dp))
            }
            is UpdateState.Installing -> ProgressHairline(null, Modifier.padding(top = 12.dp))
            is UpdateState.Installed -> if (state.restartNeeded) FilledButton(onClick = onRestart, modifier = Modifier.padding(top = 12.dp)) { Text("Restart") }
            else -> {
                if (!canInstall) {
                    Text(
                        UpdateCopy.ALLOW_INSTALLS,
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onAllowInstalls) { Text("Open settings") }
                }
                FilledButton(onClick = onUpdate, modifier = Modifier.padding(top = if (canInstall) 12.dp else 4.dp)) { Text("Update") }
                if (state is UpdateState.Failed) {
                    Text(
                        state.message,
                        Modifier
                            .padding(top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HairlineDivider(startInset = 16.dp)
}
