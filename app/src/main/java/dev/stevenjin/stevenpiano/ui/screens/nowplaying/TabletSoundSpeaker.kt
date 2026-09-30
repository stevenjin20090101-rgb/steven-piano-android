// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.components.TabletSoundButton
import dev.stevenjin.stevenpiano.ui.components.TabletSoundNote
import kotlinx.coroutines.launch

/**
 * Now playing's speaker, at the end of the tempo row (and the panel's, at its foot): the tablet's piano
 * sound as it is now, and its popover's volume, written to the settings as the slider moves (never
 * locked in kiosk mode) and its download (v1.8 — M25).
 */
@Composable
fun TabletSoundSpeaker(modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val state by graph.tabletSound.state.collectAsStateWithLifecycle()
    TabletSoundButton(
        state,
        onVolume = { pct -> graph.appScope.launch { graph.settingsRepository.setTabletVolume(pct) } },
        onDownload = graph.tabletSound::download,
        modifier = modifier,
    )
}

/** Now playing's one line while the tablet's sound waits for its download, with Download (or its progress and Cancel). */
@Composable
fun TabletSoundDownloadNote(modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val state by graph.tabletSound.state.collectAsStateWithLifecycle()
    TabletSoundNote(state, onDownload = graph.tabletSound::download, onCancel = graph.tabletSound::cancelDownload, modifier = modifier)
}
