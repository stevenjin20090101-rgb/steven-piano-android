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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ui.LockGlyph
import dev.stevenjin.stevenpiano.ui.theme.LocalDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular

// The Piano tab's rows (DESIGN.md › v1.5): one set for the piano's settings and the app's, so every
// row on the hub and its pages has the same height, type and rules. A row is at least 56 dp tall,
// its label in Body (17 sp) at the start 16 dp in, its value or control at the end, with a hairline
// under it inset 16 dp to the text. Frequent taps get the system ripple and nothing else. Disabled
// labels read in the secondary colour and disabled handles in the disabled-glyph grey; nothing is
// ever red.

/** How far a row's text sits from the start edge, and where its hairline starts. */
private val RowInset = 16.dp

/** A section's header: its name in the eyebrow style, a heading for TalkBack, then a full-width hairline. */
@Composable
fun SectionEyebrow(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Eyebrow(
            text,
            Modifier
                .padding(start = RowInset, top = 24.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        HairlineDivider()
    }
}

/** The start of a page's only section, which needs no name: a little space, then a full-width hairline. */
@Composable
fun SectionRule(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        HairlineDivider()
    }
}

/**
 * On or off. [note] is a line of explanation under the label, in the eyebrow style and sentence
 * case; [stateDescription] is what TalkBack says of the value when "on" and "off" are not enough
 * ("not known" before the piano has answered). [locked]: settings locked in kiosk mode, a padlock
 * before the switch (the change asks for the kiosk PIN).
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    note: String? = null,
    stateDescription: String? = null,
    locked: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { if (stateDescription != null) this.stateDescription = stateDescription }
            .padding(horizontal = RowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (note == null) {
            RowLabel(label, enabled, Modifier.weight(1f))
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor(enabled))
                Eyebrow(note, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
            }
        }
        Spacer(Modifier.width(16.dp))
        if (locked) {
            LockGlyph()
            Spacer(Modifier.width(8.dp))
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = switchColors())
    }
    HairlineDivider(startInset = RowInset)
}

/**
 * A number with few steps: the label, with its [unit] in the eyebrow beneath ("MS", "LEDS"), and
 * [control] at the end (a [StepperControl], or [StepperButtons]). [description] is what TalkBack
 * reads for the label ("Burst window, 110 milliseconds"). [below] sits under the row (the test rows'
 * buttons), then [note].
 */
@Composable
fun StepperRow(
    label: String,
    modifier: Modifier = Modifier,
    unit: String = "",
    enabled: Boolean = true,
    description: String? = null,
    note: String? = null,
    below: (@Composable () -> Unit)? = null,
    control: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = RowInset, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabelBlock(
                label,
                unit,
                enabled,
                Modifier
                    .weight(1f)
                    .then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier),
            )
            control()
        }
        below?.invoke()
        note?.let { NoteLine(it, Modifier.padding(bottom = 4.dp)) }
        HairlineDivider(startInset = RowInset)
    }
}

/**
 * The piano's "−  value  +": 48 dp buttons that repeat while held, the value between them in
 * tabular figures ([shown]; hidden from TalkBack, which reads it in the buttons' labels).
 */
@Composable
fun StepperButtons(
    shown: String,
    canDown: Boolean,
    canUp: Boolean,
    downLabel: String,
    upLabel: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(R.drawable.ic_remove, downLabel, Modifier.size(48.dp), enabled = canDown, repeatWhileHeld = true, onClick = onDown)
        ValueText(shown, canDown || canUp, TextAlign.Center)
        GlyphButton(R.drawable.ic_add, upLabel, Modifier.size(48.dp), enabled = canUp, repeatWhileHeld = true, onClick = onUp)
    }
}

/**
 * A continuous value: the label with its [unit] beneath, then a [HairlineSlider] with the value
 * beside it ([shown], tabular). The slider speaks for the row ([description], [stateDescription]).
 */
@Composable
fun SliderRow(
    label: String,
    value: Float?,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    shown: String,
    onChange: (Float) -> Unit,
    description: String,
    stateDescription: String,
    modifier: Modifier = Modifier,
    unit: String = "",
    enabled: Boolean = true,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = RowInset, end = RowInset, top = 8.dp),
    ) {
        LabelBlock(label, unit, enabled, Modifier.clearAndSetSemantics { })   // the slider says it all
        Row(verticalAlignment = Alignment.CenterVertically) {
            HairlineSlider(
                value = value,
                range = range,
                step = step,
                onChange = onChange,
                contentDescription = description,
                stateDescription = stateDescription,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
            ValueText(shown, enabled, TextAlign.End)
        }
    }
    HairlineDivider(startInset = RowInset)
}

/**
 * One of a few named [options], as a row of chips under the label; the chosen one carries a check.
 * [selected] null: none known yet. TalkBack reads each chip as "label, option".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = RowInset, end = RowInset, top = 8.dp, bottom = 8.dp),
    ) {
        RowLabel(label, enabled)
        FlowRow(
            Modifier
                .fillMaxWidth()
                .semantics { selectableGroup() },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, option ->
                val chosen = index == selected
                FilterChip(
                    selected = chosen,
                    onClick = { onSelect(index) },
                    label = { Text(option) },
                    modifier = Modifier.semantics { contentDescription = "$label, $option" },
                    enabled = enabled,
                    leadingIcon = if (chosen) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledLeadingIconColor = LocalDisabledGlyph.current,
                        disabledSelectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = enabled,
                        selected = chosen,
                        disabledBorderColor = LocalHairline.current,
                    ),
                )
            }
        }
    }
    HairlineDivider(startInset = RowInset)
}

/**
 * A row that opens a page: the label at the start, the page's one-line [value] in the secondary
 * colour and a chevron in the tertiary one at the end. A value too long to sit beside the label
 * (large text) goes under it. [selected] marks the page open beside the hub on wide screens: the
 * row is filled with the elevated surface edge to edge; null on phones, where a row navigates.
 * [locked]: settings locked in kiosk mode, a padlock in the chevron's place (the page opens after
 * the kiosk PIN).
 */
@Composable
fun NavRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    locked: Boolean = false,
) {
    val tap = if (selected == null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.selectable(selected, role = Role.Button, onClick = onClick)
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier
            .fillMaxWidth()
            .then(if (selected == true) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .heightIn(min = 56.dp)
            .then(tap)
            .padding(start = RowInset, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelAndValue(label, value, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (locked) {
            LockGlyph(Modifier.padding(3.dp))   // in the chevron's 24 dp
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
    HairlineDivider(startInset = RowInset)
}

/**
 * A row that does something at once: the app's action control ([ActionButton], the outlined button
 * of the connection card and the test rows) at the 16 dp inset, in the 56 dp rhythm; [buttons] side
 * by side when there are several (All keys off · Save now). Under them an optional [note] in the
 * eyebrow style, sentence case, read out as it changes (what the action does, or what it found),
 * then [below] (the piano's report, a failure). So the tab reads at a glance: a chevron opens a
 * page, an outlined button acts, a row with neither only reads.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    note: String? = null,
    below: (@Composable () -> Unit)? = null,
    buttons: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = RowInset, end = RowInset, top = 4.dp, bottom = if (note != null || below != null) 12.dp else 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        FlowRow(
            Modifier.clearOfRules(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { buttons() }
        if (note != null) {
            Eyebrow(
                note,
                Modifier
                    .padding(top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                uppercase = false,
            )
        }
        below?.invoke()
    }
    HairlineDivider(startInset = RowInset)
}

/**
 * A 40 dp button sits in a 48 dp touch target, which leaves it 4 dp clear above and below; with
 * large text it outgrows the target and loses that space, so then it gets 4 dp of its own and never
 * crowds the row's hairlines.
 */
private fun Modifier.clearOfRules(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val extra = if (placeable.height > 48.dp.roundToPx()) 4.dp.roundToPx() else 0
    val height = (placeable.height + 2 * extra).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, extra) }
}

/**
 * The app's action control: an outlined button, a hairline border in the tertiary colour (the
 * hairline's own when unavailable), its label in the content colour, the secondary colour when
 * unavailable (Material 3's outlined label is the secondary ink, `onSurfaceVariant`, so both are
 * set here). [description] when the words need context TalkBack lacks ("Save now": "Save the
 * settings on the piano now").
 */
@Composable
fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = if (description != null) modifier.semantics { contentDescription = description } else modifier,
        enabled = enabled,
        border = BorderStroke(Hairline, if (enabled) LocalTertiary.current else LocalHairline.current),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(label)
    }
}

/** A line of explanation in the secondary colour, in Body: the pages' status line, a control's help. */
@Composable
fun NoteLine(text: String, modifier: Modifier = Modifier, inset: Boolean = true) {
    Text(
        text,
        modifier
            .fillMaxWidth()
            .padding(horizontal = if (inset) RowInset else 0.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A read-only row: what it is, and what the piano says ([shown], tabular); TalkBack reads "label, [spoken]". */
@Composable
fun ReadingRow(label: String, shown: String, modifier: Modifier = Modifier, spoken: String = shown) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $spoken" }
            .padding(horizontal = RowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelAndValue(label, shown, Modifier.weight(1f))
    }
    HairlineDivider(startInset = RowInset)
}

/**
 * A label at the start and a value at the end, at least 16 dp apart; when the two don't fit on one
 * line (a long value, or large text), the value goes under the label rather than squeezing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelAndValue(label: String, value: String, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.padding(vertical = 8.dp),
        horizontalArrangement = ApartArrangement,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Items at the two ends of their line (one alone sits at the start), and at least 16 dp apart when they share it. */
private object ApartArrangement : Arrangement.Horizontal {
    override val spacing: Dp = 16.dp

    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        with(Arrangement.SpaceBetween) { arrange(totalSize, sizes, layoutDirection, outPositions) }
    }
}

@Composable
private fun RowLabel(text: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge, color = labelColor(enabled))
}

/** A control's label with its unit in the eyebrow beneath ("MS", "%", "LEDS"). */
@Composable
private fun LabelBlock(label: String, unit: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor(enabled))
        if (unit.isNotEmpty()) Eyebrow(unit)
    }
}

/** A value beside its control, in tabular figures so it does not jitter as it changes. */
@Composable
private fun ValueText(shown: String, enabled: Boolean, align: TextAlign) {
    Text(
        shown,
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clearAndSetSemantics { },
        style = MaterialTheme.typography.labelLarge.merge(Tabular),
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
    )
}

@Composable
private fun labelColor(enabled: Boolean): Color =
    if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

/** Monochrome switches: an outlined track when off, the content colour when on, disabled-glyph grey when unavailable. */
@Composable
private fun switchColors() = SwitchDefaults.colors(
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedBorderColor = LocalTertiary.current,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    disabledUncheckedThumbColor = LocalDisabledGlyph.current,
    disabledUncheckedBorderColor = LocalDisabledGlyph.current,
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surface,
    disabledCheckedThumbColor = MaterialTheme.colorScheme.surface,
    disabledCheckedTrackColor = LocalDisabledGlyph.current,
    disabledCheckedBorderColor = LocalDisabledGlyph.current,
)

/** Direction glyphs (a chevron, the back arrow) point the other way in right-to-left layouts: [on] there. */
fun Modifier.mirrored(on: Boolean): Modifier = if (on) graphicsLayer { scaleX = -1f } else this
