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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular

/** How many digits a PIN has. */
const val PIN_DIGITS = 6

/**
 * A new PIN, six digits entered twice (the web panel's now, the kiosk's in M20): a sheet with its
 * drag handle, an [eyebrow] naming what it guards, the [title], one centred field in the app's
 * outlined style (dots, a number pad, tabular figures), and Cancel and Next, then Save. When the
 * second entry differs the sheet starts again and says so. [onSet] gets the digits (the caller
 * hashes them; nothing here keeps or shows them), then the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Eyebrow(eyebrow)
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    mismatch -> "The two didn't match. Enter six digits."
                    confirming -> "Enter them again."
                    else -> "Enter six digits."
                },
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = entry,
                onValueChange = { typed -> entry = typed.filter { it in '0'..'9' }.take(PIN_DIGITS) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(min = 200.dp, max = 280.dp)
                    .focusRequester(focus)
                    .semantics { contentDescription = if (confirming) "PIN again" else "PIN" },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.merge(Tabular).copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = if (confirming) ImeAction.Done else ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { next() }, onDone = { next() }),
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedBorderColor = LocalTertiary.current,
                    disabledBorderColor = LocalHairline.current,
                    cursorColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.widthIn(min = 8.dp))
                ActionButton(if (confirming) "Save" else "Next", onClick = ::next, enabled = complete)
            }
        }
    }
    LaunchedEffect(confirming) { focus.requestFocus() }
}
