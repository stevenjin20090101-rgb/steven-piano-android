// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ui.LockGlyph
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.mirrored
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * The hub's search field (DESIGN.md › v1.13 — M31b): "Search settings", the Library's own field (the outlined
 * field, the search glyph, Clear while there is text). Content, not glass: it scrolls with the hub.
 */
@Composable
fun SettingsSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(SettingsIndex.PLACEHOLDER) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            { GlyphButton(R.drawable.ic_close, "Clear search") { onQuery("") } }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

/**
 * What the search found, in place of the hub's list: a row a result, its label in Body over its path in the
 * eyebrow ("THE PIANO › SOUND AND TOUCH › FINE TUNING"), a chevron at the end (a padlock where [locked]
 * says its page waits for the kiosk PIN); "No setting matches." when nothing does. TalkBack reads "label,
 * path" and hears the count change.
 */
@Composable
fun SettingsResults(results: List<SettingsEntry>, locked: (SettingsEntry) -> Boolean, onChoose: (SettingsEntry) -> Unit) {
    if (results.isEmpty()) {
        NoteLine(SettingsIndex.NO_MATCH, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        return
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    for (entry in results) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { onChoose(entry) }
                .semantics(mergeDescendants = true) { contentDescription = "${entry.label}, ${entry.eyebrow}" }
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Eyebrow(entry.eyebrow, Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (locked(entry)) {
                LockGlyph(Modifier.padding(3.dp), description = null)
            } else {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .mirrored(rtl),
                    tint = LocalTertiary.current,
                )
            }
        }
        HairlineDivider(startInset = 16.dp)
    }
}
