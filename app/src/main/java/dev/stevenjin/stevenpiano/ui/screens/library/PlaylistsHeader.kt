// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.PlaylistSort
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassPopover

/**
 * The Playlists listing's header row (DESIGN.md › v1.10.1, D6), below the channels' row: the eyebrow
 * PLAYLISTS at its start, and at its end a pop-up button that shows the order chosen, "Newest first" (the
 * default) or "Name", and opens the two on the menus' glass with a checkmark on the current one. HIG
 * `pop-up-buttons.md`: a flat list of mutually exclusive options, a useful default selection;
 * `settings.md`: ordering a collection belongs on the screen it affects. The menu's end stands at the
 * button's end ([GlassPopover], [Alignment.End]), so on the tablet it opens within the list's pane and never
 * across the divider. The button keeps to one line at any text size; the eyebrow may wrap beside it.
 */
@Composable
fun PlaylistsHeader(sort: PlaylistSort, onSort: (PlaylistSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow("Playlists", Modifier.weight(1f).semantics { heading() })
        Box {
            SortButton(sort) { open = true }
            GlassPopover(expanded = open, onDismissRequest = { open = false }, alignment = Alignment.End) {
                // As wide as its widest order (a popup offers the whole window): its end at the button's, inside the list's pane.
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = SORT_MENU_MIN_WIDTH).selectableGroup()) {
                    PlaylistSort.entries.forEach { option ->
                        SortOption(option, selected = option == sort) {
                            open = false
                            if (option != sort) onSort(option)
                        }
                    }
                }
            }
        }
    }
}

/** What TalkBack reads for the pop-up button: "Sort playlists, Newest first". */
fun sortDescription(sort: PlaylistSort): String = "Sort playlists, ${sort.label}"

/** The pop-up button: the order chosen in the label's style and the content colour, a chevron in the secondary grey, 48 dp tall. */
@Composable
private fun SortButton(sort: PlaylistSort, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.DropdownList, onClickLabel = "Change", onClick = onClick)
            .clearAndSetSemantics { contentDescription = sortDescription(sort) }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(sort.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(4.dp))
        Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** One order in the menu: a checkmark when it is the current one (its room kept when not), then its name. */
@Composable
private fun SortOption(option: PlaylistSort, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp)) {
            if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(12.dp))
        Text(option.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
    }
}

/** The menu at least this wide, so two short orders still read as a menu. */
private val SORT_MENU_MIN_WIDTH = 168.dp
