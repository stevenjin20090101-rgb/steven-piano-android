// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoSection
import dev.stevenjin.stevenpiano.piano.PianoSetting
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.piano.Preset
import dev.stevenjin.stevenpiano.piano.SettingKind
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.HairlineSlider
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry
import dev.stevenjin.stevenpiano.ui.theme.LocalDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import kotlin.math.roundToInt

/** What the settings sections ask of the Piano tab. */
interface PianoSettingsActions {
    /** A setting changed to [value], in its wire form. */
    fun setPianoValue(name: String, value: String)

    fun applyPreset(command: String)

    fun runPianoAction(action: PianoAction, key: Int)

    fun dismissPianoError()
}

/**
 * The piano's own lighting, feel and pedal settings, and its diagnostics, on the Piano tab
 * between the connection card and the app's preferences (DESIGN.md › v1.1 › Piano settings):
 * LIGHTING · FEEL · PEDAL · DIAGNOSTICS under eyebrow headers. Switches for on and off, steppers
 * for numbers with few steps, hairline sliders with the number beside them for continuous
 * values, chips for palettes and presets; units in each control's eyebrow. Every control shows
 * what the piano holds ([piano]'s values) and sends a change the moment it is made. Until the
 * piano has answered, everything is disabled, with a line saying why; firmware without the
 * console gets one line and nothing else. A refusal shows in an outlined banner in the section of
 * the control it concerns, right under that control, where the change was made. No red anywhere:
 * a fault reads in words. [appDiagnostics] (the app's own Share diagnostics, v1.4) closes the
 * DIAGNOSTICS section whatever the piano's state, under its own header when the piano's firmware
 * has no settings to show.
 */
@Composable
fun PianoSettingsSections(
    piano: PianoState,
    connected: Boolean,
    statusText: String?,
    statusReading: Boolean,
    actions: PianoSettingsActions,
    modifier: Modifier = Modifier,
    appDiagnostics: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        if (piano == PianoState.Unsupported) {
            Line("This piano's firmware doesn't offer settings over Bluetooth yet.", Modifier.padding(top = 8.dp))
            Header(PianoSection.Diagnostics)
            appDiagnostics()
            return@Column
        }
        val ready = piano as? PianoState.Ready
        if (ready == null) {
            if (connected) {
                Line("Reading the piano's settings…", Modifier.padding(top = 8.dp))
                ProgressHairline(null, Modifier.padding(horizontal = 16.dp))
            } else {
                Line("Connect to the piano to adjust its settings.", Modifier.padding(top = 8.dp))
            }
        }
        val error = ready?.lastError
        val place = error?.let { placeOf(ready.errorAbout) }
        val bannerAfter: @Composable (String) -> Unit = { row ->
            if (error != null && row == place) ErrorBanner(error, actions::dismissPianoError)
        }

        Header(PianoSection.Lighting)
        for (setting in PianoSettings.inSection(PianoSection.Lighting)) {
            SettingRow(setting, ready, actions)
            bannerAfter(setting.name)
            if (setting.name == "rainspeed") {
                TestLedRow(ready != null, actions)
                bannerAfter(PianoAction.LedTest.command)
            }
        }

        Header(PianoSection.Feel)
        PresetsRow(ready != null, actions)
        bannerAfter(PRESETS)
        for (setting in PianoSettings.inSection(PianoSection.Feel)) {
            SettingRow(setting, ready, actions)
            bannerAfter(setting.name)
            if (setting.name == "max") {
                StrikeTestRow(ready != null, actions)
                bannerAfter(STRIKE_TEST)
            }
        }

        Header(PianoSection.Pedal)
        for (setting in PianoSettings.inSection(PianoSection.Pedal)) {
            SettingRow(setting, ready, actions)
            bannerAfter(setting.name)
        }

        Header(PianoSection.Diagnostics)
        Diagnostics(ready, statusText, statusReading, actions)
        bannerAfter(DIAGNOSTICS)
        appDiagnostics()
    }
}

/**
 * Where a refusal shows: under the setting it names, under the Test LED, strike test or preset
 * row for their commands, and at the end of Diagnostics for its buttons and anything unnamed.
 */
private fun placeOf(about: String?): String = when {
    about == null -> DIAGNOSTICS
    PianoSettings.named(about) != null -> about
    about == PianoAction.LedTest.command -> about
    about == PianoAction.TestMin.command || about == PianoAction.TestMax.command -> STRIKE_TEST
    PianoSettings.presets.any { it.command == about } -> PRESETS
    else -> DIAGNOSTICS
}

private const val PRESETS = "presets"
private const val STRIKE_TEST = "strike test"
private const val DIAGNOSTICS = "diagnostics"

@Composable
private fun Header(section: PianoSection) {
    Eyebrow(
        section.title,
        Modifier
            .padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
    HairlineDivider()
}

/** One setting, in the control its kind takes. Adjustable only once the piano has reported it. */
@Composable
private fun SettingRow(setting: PianoSetting, ready: PianoState.Ready?, actions: PianoSettingsActions) {
    val wire = ready?.values?.get(setting.name)
    val enabled = wire != null && !setting.readOnly
    val change: (String) -> Unit = { actions.setPianoValue(setting.name, it) }
    when (val kind = setting.kind) {
        SettingKind.Switch -> SwitchSetting(setting, wire, enabled, change)
        is SettingKind.Stepper -> StepperSetting(setting, kind, wire, enabled, change)
        is SettingKind.Slider ->
            if (setting.readOnly) Reading(setting.label, setting.display(wire), setting.spoken(wire)) else SliderSetting(setting, kind, wire, enabled, change)
        is SettingKind.Choice -> ChoiceSetting(setting, kind, wire, enabled, change)
    }
}

@Composable
private fun SwitchSetting(setting: PianoSetting, wire: String?, enabled: Boolean, onChange: (String) -> Unit) {
    val on = wire != null && wire.trim() != "0"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(on, enabled = enabled, role = Role.Switch) { onChange(PianoSettings.wire(it)) }
            .semantics {
                contentDescription = setting.label
                stateDescription = setting.spoken(wire)
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(setting.label, enabled, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = on, onCheckedChange = null, enabled = enabled, colors = switchColors())
    }
    HairlineDivider(startInset = 16.dp)
}

@Composable
private fun StepperSetting(setting: PianoSetting, kind: SettingKind.Stepper, wire: String?, enabled: Boolean, onChange: (String) -> Unit) {
    val value = wire?.toIntOrNull()
    val spoken = setting.spoken(wire)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelBlock(
            setting.label,
            setting.unit,
            enabled,
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { contentDescription = "${setting.label}, $spoken" },
        )
        Stepper(
            shown = shownBeside(setting, wire),
            canDown = enabled && value != null && kind.next(value, up = false) != value,
            canUp = enabled && value != null && kind.next(value, up = true) != value,
            downLabel = "Decrease ${setting.label}, now $spoken",
            upLabel = "Increase ${setting.label}, now $spoken",
            onDown = { value?.let { onChange(PianoSettings.wire(kind.next(it, up = false))) } },
            onUp = { value?.let { onChange(PianoSettings.wire(kind.next(it, up = true))) } },
        )
    }
    setting.note?.let { Line(it, Modifier.padding(bottom = 4.dp)) }
    HairlineDivider(startInset = 16.dp)
}

@Composable
private fun SliderSetting(setting: PianoSetting, kind: SettingKind.Slider, wire: String?, enabled: Boolean, onChange: (String) -> Unit) {
    val spoken = setting.spoken(wire)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        LabelBlock(setting.label, setting.unit, enabled, Modifier.clearAndSetSemantics { })   // the slider says it all
        Row(verticalAlignment = Alignment.CenterVertically) {
            HairlineSlider(
                value = wire?.toFloatOrNull(),
                range = kind.min..kind.max,
                step = kind.step,
                onChange = { v -> onChange(if (kind.decimals > 0) PianoSettings.wire(v) else PianoSettings.wire(v.roundToInt())) },
                contentDescription = "${setting.label}, $spoken",
                stateDescription = spoken,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
            Value(shownBeside(setting, wire), enabled, TextAlign.End)
        }
    }
    HairlineDivider(startInset = 16.dp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceSetting(setting: PianoSetting, kind: SettingKind.Choice, wire: String?, enabled: Boolean, onChange: (String) -> Unit) {
    val selected = wire?.toIntOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Label(setting.label, enabled)
        FlowRow(
            Modifier
                .fillMaxWidth()
                .semantics { selectableGroup() },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            kind.options.forEachIndexed { index, option ->
                val chosen = index == selected
                FilterChip(
                    selected = chosen,
                    onClick = { onChange(PianoSettings.wire(index)) },
                    label = { Text(option) },
                    modifier = Modifier.semantics { contentDescription = "${setting.label}, $option" },
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
    HairlineDivider(startInset = 16.dp)
}

/** FEEL's first row: the four presets as chips. A preset changes several settings, which then show what the piano reports. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsRow(enabled: Boolean, actions: PianoSettingsActions) {
    var sent by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled) { if (!enabled) sent = null }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Label("Presets", enabled)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in PianoSettings.presets) PresetChip(preset, enabled) {
                sent = preset.label
                actions.applyPreset(preset.command)
            }
        }
        sent?.let { Line("$it sent. The settings below show what the piano holds now.", Modifier.padding(top = 4.dp), inset = false) }
    }
    HairlineDivider(startInset = 16.dp)
}

@Composable
private fun PresetChip(preset: Preset, enabled: Boolean, onClick: () -> Unit) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(preset.label) },
        modifier = Modifier.semantics { contentDescription = "Apply the ${preset.label} preset" },
        enabled = enabled,
        colors = SuggestionChipDefaults.suggestionChipColors(disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant),
        border = SuggestionChipDefaults.suggestionChipBorder(enabled, borderColor = LocalTertiary.current, disabledBorderColor = LocalHairline.current),
    )
}

/** LIGHTING: one key's LED lit white for a few seconds, to line up offset and scale standing at the piano. */
@Composable
private fun TestLedRow(enabled: Boolean, actions: PianoSettingsActions) {
    var key by rememberSaveable { mutableIntStateOf(PianoSettings.TEST_KEY_DEFAULT) }
    TestKeyRow("Test LED", key, { key = it }, enabled) {
        ActionButton("Light it", "Light the LED for ${spokenKey(key)}", enabled) { actions.runPianoAction(PianoAction.LedTest, key) }
    }
}

/** FEEL: one key struck once at its floor or at the ceiling, to hear whether they are right. */
@Composable
private fun StrikeTestRow(enabled: Boolean, actions: PianoSettingsActions) {
    var key by rememberSaveable { mutableIntStateOf(PianoSettings.TEST_KEY_DEFAULT) }
    TestKeyRow("Strike test", key, { key = it }, enabled, "Plays the key once, as softly as its floor allows or as hard as the ceiling.") {
        ActionButton("Floor", "Strike ${spokenKey(key)} at its floor", enabled) { actions.runPianoAction(PianoAction.TestMin, key) }
        ActionButton("Ceiling", "Strike ${spokenKey(key)} at the ceiling", enabled) { actions.runPianoAction(PianoAction.TestMax, key) }
    }
}

/** A key chosen with a stepper, shown by name (C4), and the actions for it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestKeyRow(title: String, key: Int, onKey: (Int) -> Unit, enabled: Boolean, note: String? = null, buttons: @Composable RowScope.() -> Unit) {
    val keys = SettingKind.Stepper(PianoSettings.testKeys.first, PianoSettings.testKeys.last)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelBlock(
            title,
            "Key",
            enabled,
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { contentDescription = "$title, key ${spokenKey(key)}" },
        )
        Stepper(
            shown = KeyboardGeometry.name(key),
            canDown = enabled && key > keys.min,
            canUp = enabled && key < keys.max,
            downLabel = "Key down, now ${spokenKey(key)}",
            upLabel = "Key up, now ${spokenKey(key)}",
            onDown = { onKey(keys.next(key, up = false)) },
            onUp = { onKey(keys.next(key, up = true)) },
        )
    }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        content = { buttons() },
    )
    note?.let { Line(it, Modifier.padding(bottom = 4.dp)) }
    HairlineDivider(startInset = 16.dp)
}

/** DIAGNOSTICS: the facts, the per-key force (set at the USB console only), the status text, all keys off and save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Diagnostics(ready: PianoState.Ready?, statusText: String?, statusReading: Boolean, actions: PianoSettingsActions) {
    val enabled = ready != null
    for (fact in PianoSettings.facts) {
        val value = ready?.facts?.get(fact.name)
        if (fact.name == "boards") Boards(value) else Reading(fact.label, factText(fact.name, value))
    }
    for (setting in PianoSettings.inSection(PianoSection.Diagnostics)) SettingRow(setting, ready, actions)
    Line("Key force is set at the piano's USB console.", Modifier.padding(top = 4.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ActionButton(if (statusReading) "Reading status…" else "Read status", null, enabled && !statusReading) {
            actions.runPianoAction(PianoAction.Status, 0)
        }
        if (statusReading) ProgressHairline(null, Modifier.padding(top = 8.dp))
        if (statusText != null) {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    statusText.ifEmpty { "The piano didn't answer." },
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("All keys off", null, enabled) { actions.runPianoAction(PianoAction.AllKeysOff, 0) }
            ActionButton("Save now", "Save the settings on the piano now", enabled) { actions.runPianoAction(PianoAction.Save, 0) }
        }
    }
}

/** The seven power boards, one per octave (C1 … C7), each OK or MISSING in words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Boards(value: String?) {
    val words = value?.split(',')?.map { it.trim() }?.takeIf { it.size == BOARDS } ?: List(BOARDS) { "—" }
    val spoken = words.mapIndexed { i, word -> "C${i + 1} octave ${if (word == "—") "not known" else word.lowercase()}" }.joinToString(", ")
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Power boards, $spoken" }
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Label("Power boards", enabled = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            words.forEachIndexed { i, word ->
                Column {
                    Eyebrow("C${i + 1}")
                    Text(word, style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    HairlineDivider(startInset = 16.dp)
}

/** A read-only row: what it is, and what the piano says. */
@Composable
private fun Reading(label: String, shown: String, spoken: String = shown) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $spoken" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, enabled = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Text(shown, style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
    HairlineDivider(startInset = 16.dp)
}

/** A value beside its control; blank while not known, since a dash between − and + would read as one more minus. */
private fun shownBeside(setting: PianoSetting, wire: String?): String = if (wire == null) "" else setting.display(wire)

private fun factText(name: String, value: String?): String {
    if (value == null) return "—"
    return when (name) {
        "uptime" -> value.toLongOrNull()?.let(PianoSettings::uptime) ?: value
        "pedalboard" -> value.replaceFirstChar { it.uppercase() }
        else -> value
    }
}

/** "C sharp 4" for C♯4: how TalkBack should say a key. */
private fun spokenKey(key: Int): String = KeyboardGeometry.name(key).replace("♯", " sharp ")

@Composable
private fun Stepper(
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
        Value(shown, canDown || canUp, TextAlign.Center)
        GlyphButton(R.drawable.ic_add, upLabel, Modifier.size(48.dp), enabled = canUp, repeatWhileHeld = true, onClick = onUp)
    }
}

/** A value beside its control, in tabular figures so it does not jitter as it changes. */
@Composable
private fun Value(shown: String, enabled: Boolean, align: TextAlign) {
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

/** An outlined button in the hairline style; [description] when its words need the context TalkBack lacks. */
@Composable
private fun ActionButton(text: String, description: String?, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
        enabled = enabled,
        border = BorderStroke(Hairline, if (enabled) LocalTertiary.current else LocalHairline.current),
        colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Text(text)
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    OutlinedBanner("The piano said: $message", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun Label(text: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A control's label with its unit in the eyebrow beneath ("MS", "%", "LEDS"). */
@Composable
private fun LabelBlock(label: String, unit: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (unit.isNotEmpty()) Eyebrow(unit)
    }
}

/** A line of explanation in the secondary colour. */
@Composable
private fun Line(text: String, modifier: Modifier = Modifier, inset: Boolean = true) {
    Text(
        text,
        modifier
            .fillMaxWidth()
            .padding(horizontal = if (inset) 16.dp else 0.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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

private const val BOARDS = 7
