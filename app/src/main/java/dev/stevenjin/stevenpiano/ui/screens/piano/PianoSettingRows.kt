// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.stevenjin.stevenpiano.diag.PianoDiag
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.piano.LockPhase
import dev.stevenjin.stevenpiano.ui.LockCopy
import dev.stevenjin.stevenpiano.ui.PlaybackCopy
import dev.stevenjin.stevenpiano.ui.SystemCopy
import dev.stevenjin.stevenpiano.ui.components.FactRow
import dev.stevenjin.stevenpiano.ui.components.GlassAlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoFold
import dev.stevenjin.stevenpiano.piano.PianoPage
import dev.stevenjin.stevenpiano.piano.PianoRow
import dev.stevenjin.stevenpiano.piano.PianoSection
import dev.stevenjin.stevenpiano.piano.PianoSetting
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.piano.Preset
import dev.stevenjin.stevenpiano.piano.SettingKind
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.SettingNotes
import dev.stevenjin.stevenpiano.ui.components.ChoiceRow
import dev.stevenjin.stevenpiano.ui.components.DisclosureRow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.components.PowerBoards
import dev.stevenjin.stevenpiano.ui.components.rememberRailOff
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.pressScale
import dev.stevenjin.stevenpiano.ui.components.ReadingRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.SliderRow
import dev.stevenjin.stevenpiano.ui.components.StepperButtons
import dev.stevenjin.stevenpiano.ui.components.StepperRow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.roundToInt

/** What the piano's pages ask of the Piano tab. */
interface PianoSettingsActions {
    /** The disclosures open just now (v1.13 — M31b): Fine tuning, Strip set-up; Compose state, so reading it recomposes. */
    val openFolds: Set<PianoFold>

    /** Opens or closes [fold]. */
    fun toggleFold(fold: PianoFold)

    /** A setting changed to [value], in its wire form. */
    fun setPianoValue(name: String, value: String)

    fun applyPreset(command: String)

    fun runPianoAction(action: PianoAction, key: Int)

    fun dismissPianoError()
}

/** Firmware without the Bluetooth console: said on the hub and on each piano page, and nothing else there. */
const val UNSUPPORTED_LINE = "This piano's firmware doesn't offer settings over Bluetooth yet."

/**
 * Why the piano's settings can't be changed yet, in one line under the connection card and at the
 * top of each piano page: "Connect to the piano to adjust its settings." while not connected;
 * "Reading the piano's settings…" over an indeterminate hairline once connected, until the piano
 * has answered, and while its Bluetooth lock is proved (v1.25 — M58: the link shows itself
 * connected only then, so the lock's own phase says the piano is there); [UNSUPPORTED_LINE] for
 * firmware without the console. Nothing once it has answered (read only, the connection card says
 * why under the hub, and each piano page under this line).
 */
@Composable
fun PianoStatusLine(piano: PianoState, connected: Boolean) {
    val lock by LocalContext.current.graph.pianoSettings.lock.collectAsStateWithLifecycle()
    val reading = connected || lock.phase == LockPhase.Reading || lock.phase == LockPhase.Proving
    when {
        piano is PianoState.Ready && piano.lock.phase == LockPhase.Proving -> {
            NoteLine("Reading the piano's settings…", Modifier.padding(top = 8.dp))
            ProgressHairline(null, Modifier.padding(horizontal = 16.dp))
        }
        piano is PianoState.Ready -> Unit
        piano == PianoState.Unsupported -> NoteLine(UNSUPPORTED_LINE, Modifier.padding(top = 8.dp))
        reading -> {
            NoteLine("Reading the piano's settings…", Modifier.padding(top = 8.dp))
            ProgressHairline(null, Modifier.padding(horizontal = 16.dp))
        }
        else -> NoteLine("Connect to the piano to adjust its settings.", Modifier.padding(top = 8.dp))
    }
}

/**
 * A piano page's content (DESIGN.md › v1.5, v1.13): the status line, then [page]'s sections from the
 * table, each under its eyebrow; the sections a page folds away ([PianoFold]) sit behind its one
 * disclosure row, closed until opened (or until search lands on a row inside). Each row is anchored for
 * search ([SettingsIndex.anchorOf]). Switches for on and off, steppers for numbers with few steps,
 * hairline sliders with the number beside them for continuous values, chips for modes, palettes
 * and presets; units in each control's eyebrow. Every control shows what the piano holds and sends
 * a change the moment it is made; until the piano has answered, everything is disabled under the
 * status line. Firmware without the console gets the one line and nothing else. A refusal shows in
 * an outlined banner right under the control it concerns ("The piano said: …"). While the piano's
 * Bluetooth lock doesn't let this tablet write (v1.25 — M58: proving its key, or read only), every
 * control shows what the piano holds and changes nothing; Read status still reads. No red anywhere:
 * a fault reads in words. [firmwareSection] (v1.6 — M21, the Firmware page) is drawn in place of
 * the table's FIRMWARE section: the version and the updates come from the link, not the console, so
 * it shows under the status line even for firmware without the console.
 */
@Composable
fun PianoPageContent(page: PianoPage, report: PianoReport, actions: PianoSettingsActions, firmwareSection: (@Composable () -> Unit)? = null) {
    PianoStatusLine(report.piano, report.connected)
    // Read only under the piano's lock (v1.25 — M58): why, and how to let this tablet in.
    (report.piano as? PianoState.Ready)?.lock?.let(LockCopy::readOnly)?.let { NoteLine(it, Modifier.padding(top = 8.dp)) }
    firmwareSection?.invoke()
    if (report.piano == PianoState.Unsupported) return
    val ready = report.piano as? PianoState.Ready
    val reduced = rememberReducedMotion()
    var drawn: PianoFold? = null
    for (section in PianoSettings.sections(page)) {
        if (firmwareSection != null && section == PianoSection.Firmware) continue
        val fold = section.fold
        if (fold == null) {
            PianoSettingsSection(section, ready, report.statusText, report.statusReading, actions)
            continue
        }
        if (fold == drawn) continue
        drawn = fold
        val inside = PianoSettings.folded(fold)
        SectionRule()
        DisclosureRow(
            fold.title,
            value = inside.mapNotNull { it.title }.joinToString(" · "),
            open = fold in actions.openFolds,
            onToggle = { actions.toggleFold(fold) },
            reduced = reduced,
        ) {
            for (folded in inside) PianoSettingsSection(folded, ready, report.statusText, report.statusReading, actions)
        }
    }
}

/**
 * What the piano pages show of the piano: its [piano] state, whether the link is [connected], and
 * the last Read status ([statusText], [statusReading]).
 */
@Immutable
data class PianoReport(val piano: PianoState, val connected: Boolean, val statusText: String?, val statusReading: Boolean)

/** One section of a piano page: its eyebrow (or a plain rule for a page's only section), then its rows. */
@Composable
private fun PianoSettingsSection(
    section: PianoSection,
    ready: PianoState.Ready?,
    statusText: String?,
    statusReading: Boolean,
    actions: PianoSettingsActions,
) {
    val title = section.title
    if (title != null) SectionEyebrow(title) else SectionRule()
    val error = ready?.lastError
    val place = error?.let { placeOf(ready.errorAbout) }
    val writable = ready?.lock?.writable == true   // v1.25 — M58: the piano's lock lets this tablet change it
    for (row in PianoSettings.rows(section)) {
        Anchored(SettingsIndex.anchorOf(row)) {
            when (row) {
                is PianoRow.Control -> SettingRow(row.setting, ready, actions)
                is PianoRow.Reading -> PianoFact(row.fact.name, row.fact.label, ready)
                PianoRow.Presets -> PresetsRow(writable, actions)
                PianoRow.StrikeTest -> StrikeTestRow(writable, actions)
                PianoRow.TestLed -> TestLedRow(writable, actions)
                PianoRow.KeyForceNote -> NoteLine(KEY_FORCE_NOTE, Modifier.padding(top = 4.dp))
                PianoRow.ReadStatus -> ReadStatusRow(ready, statusText, statusReading, actions)
                PianoRow.SaveNow -> SaveNowRow(ready, actions)
            }
            if (error != null && place == placeKey(row)) ErrorBanner(error, actions::dismissPianoError)
        }
    }
}

/** Under the two key-force lines: they are read here and set elsewhere. */
private const val KEY_FORCE_NOTE = "Key force is set at the piano's USB console."

/**
 * Where a refusal shows: under the setting it names, under the Test LED, strike test or preset
 * row for their commands, under Save to the piano now for a save, and under Read status (Firmware and
 * status) for a status read, All keys off and anything unnamed.
 */
private fun placeOf(about: String?): String = when {
    about == null -> READ_STATUS
    PianoSettings.named(about) != null -> about
    about == PianoAction.LedTest.command -> about
    about == PianoAction.TestMin.command || about == PianoAction.TestMax.command -> STRIKE_TEST
    PianoSettings.presets.any { it.command == about } -> PRESETS
    about == PianoAction.Save.command -> SAVE
    else -> READ_STATUS
}

/** The place a row gives a refusal, as [placeOf] names it; null for rows no refusal concerns. */
private fun placeKey(row: PianoRow): String? = when (row) {
    is PianoRow.Control -> row.setting.name
    PianoRow.Presets -> PRESETS
    PianoRow.StrikeTest -> STRIKE_TEST
    PianoRow.TestLed -> PianoAction.LedTest.command
    PianoRow.ReadStatus -> READ_STATUS
    PianoRow.SaveNow -> SAVE
    is PianoRow.Reading, PianoRow.KeyForceNote -> null
}

private const val PRESETS = "presets"
private const val STRIKE_TEST = "strike test"
private const val READ_STATUS = "read status"
private const val SAVE = "save"

/** One setting, in the control its kind takes. Adjustable only once the piano has reported it. */
@Composable
private fun SettingRow(setting: PianoSetting, ready: PianoState.Ready?, actions: PianoSettingsActions) {
    val wire = ready?.values?.get(setting.name)
    val enabled = wire != null && !setting.readOnly && ready.lock.writable
    val change: (String) -> Unit = { actions.setPianoValue(setting.name, it) }
    val spoken = setting.spoken(wire)
    when (val kind = setting.kind) {
        SettingKind.Switch -> {
            // Full power goes off only after a warning (v1.22, fire safety); everything else at once.
            var askOff by remember { mutableStateOf(false) }
            SwitchRow(
                setting.label,
                checked = wire != null && wire.trim() != "0",
                onChange = { on -> if (!on && setting.name == FULL_POWER_SETTING) askOff = true else change(PianoSettings.wire(on)) },
                enabled = enabled,
                note = setting.note,
                stateDescription = spoken,
            )
            if (askOff) {
                GlassAlertDialog(
                    onDismissRequest = { askOff = false },
                    title = { Text(PlaybackCopy.FULL_POWER_OFF_TITLE) },
                    text = { Text(PlaybackCopy.FULL_POWER_OFF_WARNING) },
                    confirmButton = {
                        TextButton(onClick = { askOff = false; change(PianoSettings.wire(false)) }) { Text(PlaybackCopy.FULL_POWER_OFF_ACTION) }
                    },
                    dismissButton = { TextButton(onClick = { askOff = false }) { Text("Keep on") } },
                )
            }
        }
        is SettingKind.Stepper -> {
            val value = wire?.toIntOrNull()
            StepperRow(setting.label, unit = setting.unit, enabled = enabled, description = "${setting.label}, $spoken", note = setting.note) {
                StepperButtons(
                    shown = shownBeside(setting, wire),
                    canDown = enabled && value != null && kind.next(value, up = false) != value,
                    canUp = enabled && value != null && kind.next(value, up = true) != value,
                    downLabel = "Decrease ${setting.label}, now $spoken",
                    upLabel = "Increase ${setting.label}, now $spoken",
                    onDown = { value?.let { change(PianoSettings.wire(kind.next(it, up = false))) } },
                    onUp = { value?.let { change(PianoSettings.wire(kind.next(it, up = true))) } },
                )
            }
        }
        is SettingKind.Slider ->
            if (setting.readOnly) {
                ReadingRow(setting.label, setting.display(wire), spoken = spoken)
            } else {
                SliderRow(
                    setting.label,
                    value = wire?.toFloatOrNull(),
                    range = kind.min..kind.max,
                    step = kind.step,
                    shown = shownBeside(setting, wire),
                    onChange = { v -> change(if (kind.decimals > 0) PianoSettings.wire(v) else PianoSettings.wire(v.roundToInt())) },
                    description = "${setting.label}, $spoken",
                    stateDescription = spoken,
                    unit = setting.unit,
                    enabled = enabled,
                    note = setting.note,
                )
            }
        is SettingKind.Choice -> ChoiceRow(setting.label, kind.options, wire?.toIntOrNull(), { change(PianoSettings.wire(it)) }, enabled = enabled, note = setting.note)
    }
}

/** PRESETS: the four presets as chips. A preset changes several settings, which then show what the piano reports. */
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
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in PianoSettings.presets) PresetChip(preset, enabled) {
                sent = preset.label
                actions.applyPreset(preset.command)
            }
        }
        sent?.let { NoteLine("$it sent. The settings below show what the piano holds now.", Modifier.padding(top = 4.dp), inset = false) }
    }
    HairlineDivider(startInset = 16.dp)
}

@Composable
private fun PresetChip(preset: Preset, enabled: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    SuggestionChip(
        onClick = onClick,
        label = { Text(preset.label) },
        modifier = Modifier.pressScale(press).semantics { contentDescription = "Apply the ${preset.label} preset" },
        interactionSource = press,
        enabled = enabled,
        colors = SuggestionChipDefaults.suggestionChipColors(disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant),
        border = SuggestionChipDefaults.suggestionChipBorder(enabled, borderColor = LocalTertiary.current, disabledBorderColor = LocalHairline.current),
    )
}

/** LAYOUT: one key's LED lit white for a few seconds, to line up offset and scale standing at the piano. */
@Composable
private fun TestLedRow(enabled: Boolean, actions: PianoSettingsActions) {
    var key by rememberSaveable { mutableIntStateOf(PianoSettings.TEST_KEY_DEFAULT) }
    TestKeyRow("Test LED", key, { key = it }, enabled, SettingNotes.TEST_LED) {
        ActionButton("Light it", { actions.runPianoAction(PianoAction.LedTest, key) }, enabled = enabled, description = "Light the LED for ${spokenKey(key)}")
    }
}

/** TOUCH: one key struck once at its floor or at the ceiling, to hear whether they are right. */
@Composable
private fun StrikeTestRow(enabled: Boolean, actions: PianoSettingsActions) {
    var key by rememberSaveable { mutableIntStateOf(PianoSettings.TEST_KEY_DEFAULT) }
    TestKeyRow("Strike test", key, { key = it }, enabled, "Plays the key once, as softly as its floor allows or as hard as the ceiling.") {
        ActionButton("Floor", { actions.runPianoAction(PianoAction.TestMin, key) }, enabled = enabled, description = "Strike ${spokenKey(key)} at its floor")
        ActionButton("Ceiling", { actions.runPianoAction(PianoAction.TestMax, key) }, enabled = enabled, description = "Strike ${spokenKey(key)} at the ceiling")
    }
}

/** A key chosen with a stepper, shown by name (C4), and the actions for it under the row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestKeyRow(title: String, key: Int, onKey: (Int) -> Unit, enabled: Boolean, note: String? = null, buttons: @Composable () -> Unit) {
    val keys = SettingKind.Stepper(PianoSettings.testKeys.first, PianoSettings.testKeys.last)
    StepperRow(
        title,
        unit = "Key",
        enabled = enabled,
        description = "$title, key ${spokenKey(key)}",
        note = note,
        below = {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) { buttons() }
        },
    ) {
        StepperButtons(
            shown = KeyboardGeometry.name(key),
            canDown = enabled && key > keys.min,
            canUp = enabled && key < keys.max,
            downLabel = "Key down, now ${spokenKey(key)}",
            upLabel = "Key up, now ${spokenKey(key)}",
            onDown = { onKey(keys.next(key, up = false)) },
            onUp = { onKey(keys.next(key, up = true)) },
        )
    }
}

/**
 * A fact the piano reports, a hairline under it: the power boards drawn as the System page draws them ([PowerBoards]),
 * the others in the System page's [FactRow] (one of each since v1.24 — M57).
 */
@Composable
private fun PianoFact(name: String, label: String, ready: PianoState.Ready?) {
    val value = ready?.facts?.get(name)
    if (name == "boards") {
        // v1.26 — M62: with the power plug keeping the 24 V off, the boards that don't answer are unpowered, not missing.
        PowerBoards(PianoDiag(if (value == null) emptyMap() else mapOf(name to value)).boards, title = label, off = rememberRailOff())
    } else {
        FactRow(SystemCopy.Fact(label, factText(name, value)))
    }
    HairlineDivider(startInset = 16.dp)
}

/**
 * STATUS's last row (v1.13; ACTIONS until then): Read status, with what it does under it; while it
 * reads, a hairline; then the piano's report as it wrote it, on the elevated surface, under the button.
 * An outlined button, available once the piano has answered. (All keys off lives on the Instrument page.)
 */
@Composable
private fun ReadStatusRow(ready: PianoState.Ready?, statusText: String?, statusReading: Boolean, actions: PianoSettingsActions) {
    val enabled = ready != null
    val report: (@Composable () -> Unit)? = if (statusReading || statusText != null) {
        {
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
        }
    } else {
        null
    }
    ActionRow(note = SettingNotes.READ_STATUS, below = report) {
        ActionButton(
            if (statusReading) "Reading status…" else SettingsIndex.READ_STATUS,
            onClick = { actions.runPianoAction(PianoAction.Status, 0) },
            enabled = enabled && !statusReading,
        )
    }
}

/** Sound and touch's last row (v1.13; "Save now" under ACTIONS until then): the piano keeps what it holds after a restart. */
@Composable
private fun SaveNowRow(ready: PianoState.Ready?, actions: PianoSettingsActions) {
    ActionRow(note = SettingNotes.SAVE_TO_PIANO) {
        ActionButton(
            SettingsIndex.SAVE_TO_PIANO,
            onClick = { actions.runPianoAction(PianoAction.Save, 0) },
            enabled = ready?.lock?.writable == true,
            description = "Save the settings on the piano now",
        )
    }
}

/** A value beside its control; blank while not known, since a dash between − and + would read as one more minus. */
private fun shownBeside(setting: PianoSetting, wire: String?): String = if (wire == null) "" else setting.display(wire)

/**
 * How a fact reads: the firmware's version as it says it ("Unknown" until it has), uptime as h:mm, the repeat period in
 * milliseconds ("110 ms"), the others as they come.
 */
private fun factText(name: String, value: String?): String {
    if (value == null) return if (name == "fw") "Unknown" else "—"
    return when (name) {
        "uptime" -> value.toLongOrNull()?.let(PianoSettings::uptime) ?: value
        "repeatms" -> value.trim().toIntOrNull()?.let { "$it ms" } ?: value
        "pedalboard" -> value.replaceFirstChar { it.uppercase() }
        else -> value
    }
}

/** "C sharp 4" for C♯4: how TalkBack should say a key. */
private fun spokenKey(key: Int): String = KeyboardGeometry.name(key).replace("♯", " sharp ")

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    OutlinedBanner("The piano said: $message", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/** The piano's Full power switch, which asks before it goes off (v1.22). */
private const val FULL_POWER_SETTING = "fullpower"
