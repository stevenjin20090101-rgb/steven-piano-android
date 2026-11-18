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
import dev.stevenjin.stevenpiano.data.sort.PieceSort
import dev.stevenjin.stevenpiano.ui.LibraryCopy
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassPopover
import dev.stevenjin.stevenpiano.ui.components.MenuChoice

/**
 * A piece list's header row (M82), as the Playlists' is (DESIGN.md › v1.10.1, D6): the list's name as an eyebrow at its
 * start ([eyebrow]: PIECES, FAVORITES, DISNEY), and at its end a pop-up button that shows the order chosen ("A–Z", the
 * default) and opens the five on the menus' glass with a checkmark on the current one, its end at the button's end so
 * on the tablet it opens within the list's pane. Ordering a list is how the person looks at it, so it never waits for the
 * kiosk PIN. The button keeps to one line at any text size; the eyebrow may wrap beside it.
 */
@Composable
fun PiecesHeader(eyebrow: String, sort: PieceSort, onSort: (PieceSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(eyebrow, Modifier.weight(1f).semantics { heading() })
        Box {
            PieceSortButton(sort) { open = true }
            GlassPopover(expanded = open, onDismissRequest = { open = false }, alignment = Alignment.End) {
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = MENU_MIN_WIDTH).selectableGroup()) {
                    PieceSort.entries.forEach { option ->
                        MenuChoice(option.label, selected = option == sort, oneLine = true, onClick = {
                            open = false
                            if (option != sort) onSort(option)
                        })
                    }
                }
            }
        }
    }
}

/** The pop-up button: the order chosen in the label's style and the content colour, a chevron in the secondary grey, 48 dp tall. */
@Composable
private fun PieceSortButton(sort: PieceSort, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.DropdownList, onClickLabel = "Change", onClick = onClick)
            .clearAndSetSemantics { contentDescription = LibraryCopy.sortDescription(sort) }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(sort.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(4.dp))
        Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** The menu at least this wide, as the Playlists' is. */
private val MENU_MIN_WIDTH = 168.dp
