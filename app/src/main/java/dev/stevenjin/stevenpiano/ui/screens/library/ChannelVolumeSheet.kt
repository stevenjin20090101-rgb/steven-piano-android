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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.ChannelCopy
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.SliderRow
import kotlinx.coroutines.launch

/** Under the slider: where the volume goes, and that it comes back. */
private const val VOLUME_NOTE =
    "The piano's own volume while this channel plays, or how hard its keys are struck where the piano has none. What was there comes back when it ends."

/**
 * A channel's volume (the card's Set volume): a sheet with its drag handle, the channel's name,
 * and a hairline slider from 0 to 100 % with the value beside it. Each change is kept for the
 * channel and, when it is the one playing, heard at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelVolumeSheet(key: String, name: String, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    var volume by remember(key) { mutableIntStateOf(graph.settings.value.channelVolume(key)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Eyebrow(ChannelCopy.CHANNEL, Modifier.padding(horizontal = 16.dp))
            Text(
                name,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            SliderRow(
                label = "Volume",
                value = volume.toFloat(),
                range = 0f..100f,
                step = 1f,
                shown = Format.percent(volume),
                onChange = { value ->
                    val pct = value.toInt()
                    volume = pct
                    graph.appScope.launch { graph.settingsRepository.setChannelVolume(key, pct) }
                    graph.channelPlayer.volumeChanged(key, pct)
                },
                description = "$name volume",
                stateDescription = Format.percent(volume),
                unit = "%",
            )
            NoteLine(VOLUME_NOTE)
        }
    }
}
