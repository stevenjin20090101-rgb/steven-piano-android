// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.LocalIdleState
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassAlertDialog
import dev.stevenjin.stevenpiano.ui.components.GlassSheet
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.QrSheet
import dev.stevenjin.stevenpiano.ui.components.QrTile
import dev.stevenjin.stevenpiano.ui.components.ReadingRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.watchTouches
import dev.stevenjin.stevenpiano.web.relay.CloudAddress
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import dev.stevenjin.stevenpiano.web.relay.EnrolResult
import kotlinx.coroutines.delay

/** Under the switch until the tablet is enrolled. */
const val ENROL_FIRST = "Enrol this tablet first"

/** The enrol sheet's line: where a code comes from. */
const val GET_A_CODE = "Get a code from the console"

/**
 * The Remote page's CLOUD section (DESIGN.md › v1.10 — M26, Steven Piano Cloud): **Remote access
 * over the internet** (a switch, disabled with "Set a PIN first" until the panel has a PIN and "Enrol
 * this tablet first" until it is enrolled) and under it, while it is on, how the connection stands
 * ([CloudLine]); the panel's public link with its QR at 96 dp (a tap shows it large); **Cloud
 * address** (the relay's host as typed, or "Not set"); **Enrol with code** ([EnrolSheet]); and, once
 * enrolled, **Forget this cloud** after a confirmation. In kiosk mode the whole page waits behind
 * the kiosk's PIN, as every settings page does.
 */
@Composable
fun CloudSection(settings: PianoSettings, vm: PianoViewModel) {
    val cloud by vm.cloud.collectAsStateWithLifecycle()
    var enrolling by rememberSaveable { mutableStateOf(false) }
    var forgetting by rememberSaveable { mutableStateOf(false) }
    var largeQr by rememberSaveable { mutableStateOf(false) }

    SectionEyebrow("Cloud")
    SwitchRow(
        "Remote access over the internet",
        settings.cloudEnabled,
        vm::setCloudEnabled,
        enabled = settings.webPinSet && settings.cloudEnrolled,
        note = when {
            !settings.webPinSet -> SET_A_PIN_FIRST
            !settings.cloudEnrolled -> ENROL_FIRST
            else -> null
        },
    )
    if (settings.cloudEnabled && settings.cloudEnrolled) {
        CloudLine(cloud)
        // The link while it can lead somewhere: not once the console has revoked or removed this tablet, or its key is gone.
        if (cloud !is CloudStatus.Revoked && cloud !is CloudStatus.Disabled && cloud !is CloudStatus.NotEnrolled) {
            vm.cloudLink(settings, cloud)?.let { link -> LinkRow(link, onShowLarge = { largeQr = true }) }
        }
    }
    ReadingRow("Cloud address", settings.cloudHost ?: "Not set")
    ActionRow(note = if (settings.cloudEnrolled) "A new code enrols this tablet again" else GET_A_CODE) {
        ActionButton("Enrol with code", onClick = { enrolling = true })
    }
    if (settings.cloudEnrolled) {
        ActionRow(note = "Remote access stops and this tablet's key is deleted; its row in the console stays until removed there") {
            ActionButton("Forget this cloud", onClick = { forgetting = true })
        }
    }

    if (enrolling) EnrolSheet(settings.cloudHost, vm, onDone = { enrolling = false })
    if (forgetting) {
        GlassAlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text("Forget this cloud?") },
            text = {
                Text(
                    "The tablet stops using ${settings.cloudHost ?: "the relay"} and deletes its key. To come back, enrol again with a new code from the console.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.forgetCloud()
                    forgetting = false
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Cancel") } },
        )
    }
    val link = vm.cloudLink(settings, cloud)
    if (largeQr && link != null) QrSheet(link, "Scan to open the panel from anywhere", onDismiss = { largeQr = false })
}

/**
 * How the connection stands, in a line: "Connected", "Connecting…", "Waiting for a network ·
 * retrying in 30 s" (counted down), "Revoked in the console. Enrol again.", "Removed from the
 * console. Enrol again." (4403: the console forgot this piano), or that the tablet's key is gone.
 */
@Composable
private fun CloudLine(status: CloudStatus) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (status is CloudStatus.Waiting) {
        LaunchedEffect(status) {
            while (true) {
                now = System.currentTimeMillis()
                delay(COUNTDOWN_MS)
            }
        }
    }
    NoteLine(CloudCopy.line(status, now), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    HairlineDivider(startInset = 16.dp)
}

/** The panel's public link in Body, tabular, with its QR at 96 dp beside it (a tap shows it large), as the tablet's own address is shown. */
@Composable
private fun LinkRow(link: String, onShowLarge: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { },
        ) {
            Text(link, style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurface)
            Eyebrow("The panel from anywhere, behind the same PIN", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
        }
        Spacer(Modifier.width(16.dp))
        QrTile(link, 96.dp, "QR code for the panel's public link", onClick = onShowLarge)
    }
    HairlineDivider(startInset = 16.dp)
}

/**
 * Enrol with code: a sheet with its drag handle, the eyebrow CLOUD, the title, a line ("Get a code
 * from the console", or what the relay said), the relay's address (remembered from the last time) and
 * the code (`XXXX-XXXX`, any case, a dash or not), then Cancel and Enrol (while both read as what
 * they must be). Enrol asks the relay; on success the sheet closes and the section shows the switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnrolSheet(rememberedHost: String?, vm: PianoViewModel, onDone: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(rememberedHost.orEmpty()) }
    var code by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf(GET_A_CODE) }
    var busy by remember { mutableStateOf(false) }
    val codeFocus = remember { FocusRequester() }
    val hostFocus = remember { FocusRequester() }
    val ready = !busy && CloudAddress.host(host) != null && CloudAddress.code(code) != null
    val idle = LocalIdleState.current
    if (idle?.idle == true) LaunchedEffect(Unit) { onDone() }
    val touched = remember(idle) { { idle?.touch() ?: Unit } }

    fun enrol() {
        if (!ready) return
        busy = true
        line = "Enrolling…"
        vm.enrol(host, code) { result ->
            busy = false
            when (result) {
                is EnrolResult.Enrolled -> onDone()
                is EnrolResult.Refused -> line = result.message
            }
        }
    }

    GlassSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .watchTouches(touched)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Eyebrow("Cloud")
            Text("Enrol with code", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                line,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            EnrolField(
                host,
                onValue = { host = it.take(MAX_HOST_TEXT) },
                label = "Relay address",
                placeholder = "steven-piano-relay.you.workers.dev",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                actions = KeyboardActions(onNext = { codeFocus.requestFocus() }),
                modifier = Modifier.focusRequester(hostFocus),
                enabled = !busy,
            )
            Spacer(Modifier.height(12.dp))
            EnrolField(
                code,
                onValue = { code = it.uppercase().take(MAX_CODE_TEXT) },
                label = "Code",
                placeholder = "ABCD-EFGH",
                keyboard = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                actions = KeyboardActions(onDone = { enrol() }),
                modifier = Modifier.focusRequester(codeFocus),
                enabled = !busy,
                tabular = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone) { Text("Cancel") }
                Spacer(Modifier.widthIn(min = 8.dp))
                ActionButton(if (busy) "Enrolling…" else "Enrol", onClick = ::enrol, enabled = ready)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { (if (host.isEmpty()) hostFocus else codeFocus).requestFocus() } }
}

/** One of the enrol sheet's fields, in the app's outlined style (the PIN sheet's). */
@Composable
private fun EnrolField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboard: KeyboardOptions,
    actions: KeyboardActions,
    modifier: Modifier,
    enabled: Boolean,
    tabular: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        textStyle = if (tabular) MaterialTheme.typography.bodyLarge.merge(Tabular) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = keyboard,
        keyboardActions = actions,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
            unfocusedBorderColor = LocalTertiary.current,
            disabledBorderColor = LocalHairline.current,
            focusedLabelColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** The CLOUD section's words, worked out without Compose (tested). */
object CloudCopy {
    /** The status line under the switch; [now] counts a wait down. */
    fun line(status: CloudStatus, now: Long): String = when (status) {
        CloudStatus.Off -> "Starting…"
        CloudStatus.Connecting -> "Connecting…"
        is CloudStatus.Connected -> "Connected"
        is CloudStatus.Waiting -> "${status.reason} · retrying in ${seconds(status.retryInMs - (now - status.since))}"
        CloudStatus.Revoked -> "Revoked in the console. Enrol again."
        CloudStatus.Disabled -> "Removed from the console. Enrol again."
        CloudStatus.NotEnrolled -> "This tablet's key is gone. Enrol again."
    }

    /** "30 s", "4 min", rounded up; "a moment" once due. */
    private fun seconds(ms: Long): String {
        if (ms <= 0) return "a moment"
        val s = (ms + 999) / 1_000
        return if (s < 60) "$s s" else "${(s + 59) / 60} min"
    }
}

private const val COUNTDOWN_MS = 1_000L
private const val MAX_HOST_TEXT = 260
private const val MAX_CODE_TEXT = 16
