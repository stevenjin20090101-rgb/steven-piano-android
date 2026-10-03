// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.ui.AppFrame
import dev.stevenjin.stevenpiano.ui.NotesPlan
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassPopover
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.SplitAxis
import dev.stevenjin.stevenpiano.ui.components.switchColors
import dev.stevenjin.stevenpiano.ui.label
import kotlinx.coroutines.launch

/**
 * What the View menu's Show rows offer on wide frames: the two panes, or either alone (the split's 0 and 1), or neither
 * (Art only, v1.18 — M49: the cover and its controls alone).
 */
enum class ViewShow(val label: String) {
    BOTH("Score and notes"),
    NOTES("Notes only"),
    SCORE("Score only"),
    ART("Art only"),
    ;

    companion object {
        /** What [split] shows. */
        fun of(split: Float): ViewShow = when {
            split <= 0f -> NOTES
            split >= 1f -> SCORE
            else -> BOTH
        }

        /** What [plan] shows: Art only, else as its split says. */
        fun of(plan: NotesPlan): ViewShow = if (plan.artOnly) ART else of(plan.split)
    }
}

/** Under Hand colours: what it colours. */
internal const val HAND_COLOURS_NOTE = "Colours the two hands in the notes and on the keyboard strip"

/**
 * Now playing's View menu (DESIGN.md › v1.12): a glyph in the header opening a [GlassPopover] whose end
 * stands at the glyph's end, so on the tablet it never crosses the panes' divider. On wide frames SHOW
 * (Score and notes · Notes only · Score only: the split's shares without dragging; Art only, v1.18 — M49: neither, and
 * back to Score and notes the split as it was), then NOTES (the roll's
 * style: Paper roll · Falling notes, and Score on a phone), then Fingering, Chord names and Hand colours
 * (with its note), and Album colours (v1.15 — M41: the backdrop, as Piano › Display has it). Free in kiosk, as the
 * divider is. The settings are written as they are chosen.
 */
@Composable
fun ViewMenu(settings: PianoSettings, plan: NotesPlan, frame: AppFrame) {
    val graph = LocalContext.current.graph
    var open by remember { mutableStateOf(false) }
    val write: (suspend SettingsRepository.() -> Unit) -> Unit = { change -> graph.appScope.launch { graph.settingsRepository.change() } }
    Box {
        GlyphButton(R.drawable.ic_view, VIEW_LABEL, onClick = { open = true })
        GlassPopover(expanded = open, onDismissRequest = { open = false }, alignment = Alignment.End) {
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH)
                    .verticalScroll(rememberScrollState()),
            ) {
                val axis = plan.axis
                if (axis != null) {
                    MenuEyebrow("Show")
                    Column(Modifier.selectableGroup()) {
                        val shown = ViewShow.of(plan)
                        for (option in ViewShow.entries) {
                            MenuOption(option.label, selected = option == shown) {
                                if (option == shown) return@MenuOption
                                val share = when (option) {
                                    ViewShow.BOTH -> if (ViewShow.of(plan.split) == ViewShow.BOTH) plan.split else axis.defaultShare
                                    ViewShow.NOTES -> 0f
                                    ViewShow.SCORE -> 1f
                                    ViewShow.ART -> null
                                }
                                if (share == null) write { setNotesArtOnly(true) } else write { setNotesSplit(axis == SplitAxis.Stacked, share) }
                            }
                        }
                    }
                }
                MenuEyebrow("Notes")
                Column(Modifier.selectableGroup()) {
                    val choices = frame.noteDisplayChoices
                    val display = if (frame.wide) settings.noteDisplay.rollStyle else settings.noteDisplay
                    for (choice in choices) {
                        MenuOption(choice.label, selected = choice == display) {
                            if (choice != display) write { setNoteDisplay(choice) }
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                MenuSwitch("Fingering", settings.fingering) { on -> write { setFingering(on) } }
                MenuSwitch("Chord names", settings.chordNames) { on -> write { setChordNames(on) } }
                MenuSwitch("Hand colours", settings.handColours, note = HAND_COLOURS_NOTE) { on -> write { setHandColours(on) } }
                MenuSwitch("Album colours", settings.albumBackdrop) { on -> write { setAlbumBackdrop(on) } }   // v1.15 — M41
            }
        }
    }
}

/** What TalkBack calls the View menu's glyph. */
internal const val VIEW_LABEL = "View"

/** A section of the menu: its name in the eyebrow style, a heading for TalkBack. */
@Composable
private fun MenuEyebrow(text: String) {
    // On glass the eyebrow takes the secondary grey: nothing tertiary sits on glass (DESIGN.md › v1.9).
    Eyebrow(text, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp).semantics { heading() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One choice: a checkmark when it is the current one (its room kept when not), then its name; 48 dp tall. */
@Composable
private fun MenuOption(label: String, selected: Boolean, onClick: () -> Unit) {
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
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A switch row: its label (and [note] under it, in the eyebrow style and sentence case), the switch at the end; 48 dp tall. */
@Composable
private fun MenuSwitch(label: String, checked: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (note != null) Eyebrow(note, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, colors = switchColors())
    }
}

/** The menu between these widths: as wide as its widest row, a note wrapping past the most. */
private val MENU_MIN_WIDTH = 240.dp
private val MENU_MAX_WIDTH = 320.dp
