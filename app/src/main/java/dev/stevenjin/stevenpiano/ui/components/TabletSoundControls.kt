// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.audio.TabletSoundState
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.TabletSoundCopy
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import kotlin.math.roundToInt

// The tablet's piano sound on screen (DESIGN.md › v1.8 — M25): the speaker at the end of Now playing's
// tempo row (and at the foot of the tablet's now-playing panel), its popover with the volume, the note
// Now playing shows while the sound waits for its download, and the SoundFont's row on the Playback page.

/**
 * The speaker: the content colour while the tablet sounds, the tertiary grey while it doesn't (off, the
 * piano connected, or no SoundFont yet), as Shuffle and Repeat show off and on. A tap opens the popover:
 * the TABLET SOUND eyebrow, what the sound is doing, the Volume slider with its value, and the download
 * when the sound waits for it. The app's standard popover (the elevated tone, a hairline edge), not glass. The volume is
 * never locked in kiosk mode; the mode is a setting, on the Playback page.
 */
@Composable
fun TabletSoundButton(
    state: TabletSoundState,
    onVolume: (Int) -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { open = true },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (state.active) MaterialTheme.colorScheme.onSurface else LocalTertiary.current,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_speaker), contentDescription = TabletSoundCopy.glyphDescription(state))
        }
        // A hairline edge: over the score's panel (the same elevated tone) the menu's shadow alone doesn't show in the dark.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, border = BorderStroke(Hairline, LocalHairline.current)) {
            TabletSoundPopover(state, onVolume, onDownload)
        }
    }
}

/** The popover's content: 300 dp wide, 16 dp in. */
@Composable
private fun TabletSoundPopover(state: TabletSoundState, onVolume: (Int) -> Unit, onDownload: () -> Unit) {
    // The slider shows the finger's value at once; the setting follows a moment later.
    var shown by remember(state.volume) { mutableIntStateOf(state.volume) }
    Column(
        Modifier
            .width(POPOVER_WIDTH)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Eyebrow(TabletSoundCopy.EYEBROW)
        Text(
            TabletSoundCopy.status(state),
            Modifier
                .padding(top = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clearAndSetSemantics { },   // the slider says it all
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(TabletSoundCopy.VOLUME, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(Format.percent(shown), style = MaterialTheme.typography.labelLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurface)
        }
        HairlineSlider(
            value = shown.toFloat(),
            range = 0f..100f,
            step = 1f,
            onChange = {
                shown = it.roundToInt()
                onVolume(shown)
            },
            contentDescription = "Tablet sound volume",
            stateDescription = Format.percent(shown),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.needsDownload) {
            val progress = TabletSoundCopy.progress(state)
            Text(
                TabletSoundCopy.line(state),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null) {
                ProgressHairline(progress, Modifier.padding(vertical = 10.dp))
            } else {
                ActionButton("Download", onClick = onDownload, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp), description = TabletSoundCopy.downloadDescription())
            }
        }
    }
}

/**
 * Now playing's one line while the sound waits for its download (the mode wants it and the SoundFont
 * isn't here): "Hear it on this tablet: the piano sound is a 57 MB download." with Download; then the
 * download's progress with Cancel, or why it failed with Download again. Nothing otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabletSoundNote(state: TabletSoundState, onDownload: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val line = TabletSoundCopy.nowPlayingNote(state) ?: return
    val progress = TabletSoundCopy.progress(state)
    Column(modifier.fillMaxWidth()) {
        FlowRow(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                line,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null) {
                ActionButton("Cancel", onClick = onCancel, description = "Cancel the piano sound's download")
            } else {
                ActionButton("Download", onClick = onDownload, description = TabletSoundCopy.downloadDescription())
            }
        }
        if (progress != null) ProgressHairline(progress, Modifier.padding(top = 4.dp))
    }
}

/**
 * The Playback page's SoundFont row, as Studio's model rows are: its name in Body, then in the eyebrow's
 * size, sentence case, its size, licence and what it is ("57 MB · CC0 · FreePats · A Kawai upright,
 * recorded note by note."), "Downloading · 12 of 57 MB" over the progress hairline, "Installed · 57 MB ·
 * CC0", or why the download failed; then Download, Cancel or Remove.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundFontRow(state: TabletSoundState, onDownload: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val progress = TabletSoundCopy.progress(state)
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(TabletSoundCopy.title(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Eyebrow(
            TabletSoundCopy.line(state),
            Modifier
                .padding(top = 2.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            uppercase = false,
        )
        if (progress != null) ProgressHairline(progress, Modifier.padding(top = 10.dp))
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                progress != null -> ActionButton("Cancel", onClick = onCancel, description = "Cancel the piano sound's download")
                state.installed -> ActionButton("Remove", onClick = onRemove, description = "Remove the piano sound")
                else -> ActionButton("Download", onClick = onDownload, description = TabletSoundCopy.downloadDescription())
            }
        }
    }
    HairlineDivider(startInset = 16.dp)
}

private val POPOVER_WIDTH = 300.dp
