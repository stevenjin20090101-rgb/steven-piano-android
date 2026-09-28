// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stevenjin.stevenpiano.ui.LocalIdleState
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.watchTouches
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.web.LoginGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many digits a PIN has. */
const val PIN_DIGITS = 6

/**
 * A new PIN, six digits entered twice (the web panel's, and the kiosk's since M20): a sheet with its
 * drag handle, an [eyebrow] naming what it guards, the [title], one centred field in the app's
 * outlined style (dots, a number pad, tabular figures), and Cancel and Next, then Save. When the
 * second entry differs the sheet starts again and says so. [onSet] gets the digits (the caller
 * hashes them; nothing here keeps or shows them), then the sheet closes.
 */
@Composable
fun PinSheet(eyebrow: String, title: String, onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    var entry by rememberSaveable { mutableStateOf("") }
    var mismatch by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val complete = entry.length == PIN_DIGITS
    val confirming = first != null

    fun next() {
        if (!complete) return
        val chosen = first
        when {
            chosen == null -> {
                first = entry
                entry = ""
                mismatch = false
            }
            chosen == entry -> onSet(entry)
            else -> {
                first = null
                entry = ""
                mismatch = true
            }
        }
    }

    PinSheetFrame(
        eyebrow,
        title,
        line = when {
            mismatch -> "The two didn't match. Enter six digits."
            confirming -> "Enter them again."
            else -> "Enter six digits."
        },
        onDismiss = onDismiss,
    ) {
        PinField(
            entry,
            onEntry = { entry = it },
            focus = focus,
            description = if (confirming) "PIN again" else "PIN",
            imeAction = if (confirming) ImeAction.Done else ImeAction.Next,
            onIme = ::next,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.widthIn(min = 8.dp))
            ActionButton(if (confirming) "Save" else "Next", onClick = ::next, enabled = complete)
        }
    }
    LaunchedEffect(confirming) { focus.requestFocus() }
}

/**
 * The PIN asked for before something only its holder may do (the kiosk's way out, DESIGN.md › v1.6
 * — M20): the sheet of [PinSheet] with its [eyebrow] and [title], a line under them (the [hint], or
 * what the last try found), the six-digit field, then Cancel and one outlined button for each of
 * [actions], side by side. A button weighs the digits ([check], slow on purpose, so off the main
 * thread): right, [onRight] gets the action's index and the caller closes the sheet; wrong, the
 * field empties and the line says so ("That PIN isn't right."), with the wait the guard now imposes
 * counted down ("Try again in 5 s."), the field and the buttons disabled until it ends. A wait
 * already running as the sheet opens ([waitMs]) is counted down the same way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinCheckSheet(
    eyebrow: String,
    title: String,
    hint: String,
    actions: List<String>,
    waitMs: () -> Long,
    check: suspend (String) -> LoginGuard.Attempt,
    onRight: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var entry by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var left by remember { mutableLongStateOf(waitMs()) }
    var tries by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val latestWait by rememberUpdatedState(waitMs)
    val latestRight by rememberUpdatedState(onRight)
    val waiting = left > 0
    val fieldEnabled = !waiting && !checking
    val ready = entry.length == PIN_DIGITS && fieldEnabled

    // The wait, counted down from the guard itself (a try in another sheet, or before a restart, may have started it).
    LaunchedEffect(tries) {
        while (true) {
            left = latestWait()
            if (left <= 0) break
            delay(COUNTDOWN_TICK_MS)
        }
    }
    // Whenever the field can take digits again (the sheet opening, a try weighed, a wait over), it takes the focus:
    // after the composition that enabled it, since a disabled field cannot be focused. A focus that can't be taken
    // (the sheet's window not up yet) is no reason to fail: the field can be tapped.
    LaunchedEffect(fieldEnabled) { if (fieldEnabled) runCatching { focus.requestFocus() } }

    fun weigh(action: Int) {
        if (!ready) return
        val pin = entry
        checking = true
        scope.launch {
            val outcome = check(pin)
            checking = false
            if (outcome == LoginGuard.Attempt.Right) {
                latestRight(action)
            } else {
                entry = ""
                if (outcome is LoginGuard.Attempt.Wrong) wrong = true
                tries++
            }
        }
    }

    PinSheetFrame(
        eyebrow,
        title,
        line = when {
            waiting && wrong -> "That PIN isn't right. Try again in ${PinWait.text(left)}."
            waiting -> "Too many wrong tries. Try again in ${PinWait.text(left)}."
            wrong -> "That PIN isn't right."
            else -> hint
        },
        onDismiss = onDismiss,
    ) {
        PinField(
            entry,
            onEntry = { entry = it },
            focus = focus,
            description = "PIN",
            imeAction = ImeAction.Done,
            onIme = { if (actions.size == 1) weigh(0) },
            enabled = fieldEnabled,
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            actions.forEachIndexed { index, label -> ActionButton(label, onClick = { weigh(index) }, enabled = ready) }
        }
    }
}

/** How a PIN's wait reads: seconds under a minute ("5 s", "59 s"), then whole minutes rounded up ("1 min", "5 min"), never less than it is. */
object PinWait {
    fun text(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) + 999) / 1_000
        return if (seconds < 60) "$seconds s" else "${(seconds + 59) / 60} min"
    }
}

/** How often a wait's line counts down. */
private const val COUNTDOWN_TICK_MS = 250L

/**
 * Both PIN sheets: the drag handle, the eyebrow, the title, the line (read out as it changes), then
 * [content]. A sheet is a window of its own, so its touches are counted for display mode here
 * ([LocalIdleState]); should display mode come all the same (the person walked away), the sheet
 * closes, and in kiosk mode the tablet rests locked with no PIN sheet left over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheetFrame(eyebrow: String, title: String, line: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val idle = LocalIdleState.current
    if (idle?.idle == true) LaunchedEffect(Unit) { onDismiss() }
    val touched = remember(idle) { { idle?.touch() ?: Unit } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier
                .watchTouches(touched)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Eyebrow(eyebrow)
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                line,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** The six-digit field: centred, masked, a number pad, tabular figures spaced wide, in the app's outlined style. */
@Composable
private fun ColumnScope.PinField(
    entry: String,
    onEntry: (String) -> Unit,
    focus: FocusRequester,
    description: String,
    imeAction: ImeAction,
    onIme: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = entry,
        onValueChange = { typed -> onEntry(typed.filter { it in '0'..'9' }.take(PIN_DIGITS)) },
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .widthIn(min = 200.dp, max = 280.dp)
            .focusRequester(focus)
            .semantics { contentDescription = description },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.merge(Tabular).copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
            unfocusedBorderColor = LocalTertiary.current,
            disabledBorderColor = LocalHairline.current,
            cursorColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
