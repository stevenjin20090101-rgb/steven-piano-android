// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.PinSheet
import dev.stevenjin.stevenpiano.ui.components.QrSheet
import dev.stevenjin.stevenpiano.ui.components.QrTile
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.web.WebStatus

/** Under Web control until a PIN exists. */
const val SET_A_PIN_FIRST = "Set a PIN first"

/** Under Panel on Wi-Fi too: what it costs. */
const val WIFI_NOTE = "Over Wi-Fi the PIN travels unencrypted"

/** Under the address: what the QR does. */
private const val SCAN_NOTE = "Scan it with your phone, or tap it to show it large"

/**
 * Remote control (DESIGN.md › v1.5.1 — M18): the web panel. PANEL: Web control (a switch, disabled
 * with "Set a PIN first" until a PIN exists); while it is on, the panel's address in Body with its
 * QR code beside it at 96 dp (a tap shows it large); Set a PIN / Change PIN (six digits, twice);
 * Panel on Wi-Fi too with what it costs. GUESTS: Guests can request, Approve requests first, and
 * Print the request poster (Android's print dialog, the poster of the guests' address). The hub's
 * row reads "On · 100.101.2.3" or "Off". CLOUD (v1.10 — M26): Steven Piano Cloud ([CloudSection]),
 * which adds "· Cloud" to the row.
 */
@Composable
fun RemotePage(settings: PianoSettings, web: WebStatus, vm: PianoViewModel) {
    var pinSheet by rememberSaveable { mutableStateOf(false) }
    var largeQr by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current

    SectionEyebrow("Panel")
    SwitchRow(
        "Web control",
        settings.webEnabled,
        vm::setWebEnabled,
        enabled = settings.webPinSet,
        note = if (settings.webPinSet) null else SET_A_PIN_FIRST,
    )
    if (settings.webEnabled) AddressRow(web, onShowLarge = { largeQr = true })
    ActionRow(note = "Six digits, asked for when the panel opens in a browser") {
        ActionButton(if (settings.webPinSet) "Change PIN" else "Set a PIN", onClick = { pinSheet = true })
    }
    SwitchRow("Panel on Wi-Fi too", settings.webOnWifi, vm::setWebOnWifi, note = WIFI_NOTE)

    SectionEyebrow("Guests")
    SwitchRow("Guests can request", settings.webGuests, vm::setWebGuests)
    SwitchRow("Approve requests first", settings.webApproveFirst, vm::setWebApproveFirst, enabled = settings.webGuests)
    val guestUrl = web.guestUrl
    ActionRow(note = guestUrl?.let { "The poster's code opens $it" } ?: "Turn on Web control to print the poster") {
        ActionButton(
            "Print the request poster",
            onClick = { if (activity != null && guestUrl != null) vm.printPoster(activity, guestUrl) },
            enabled = settings.webEnabled && guestUrl != null && activity != null,
        )
    }

    CloudSection(settings, vm)

    if (pinSheet) {
        PinSheet(
            eyebrow = "Web control",
            title = if (settings.webPinSet) "Change PIN" else "Set a PIN",
            onSet = { pin ->
                vm.setWebPin(pin)
                pinSheet = false
            },
            onDismiss = { pinSheet = false },
        )
    }
    val panelUrl = web.panelUrl
    if (largeQr && panelUrl != null) QrSheet(panelUrl, "Scan to open the panel", onDismiss = { largeQr = false })
}

/**
 * The panel's address as Body in tabular figures with its QR at 96 dp beside it; when the panel has
 * no address yet, why ("Waiting for a network", Android's refusal, or no Tailscale with guests on
 * the Wi-Fi only).
 */
@Composable
private fun AddressRow(web: WebStatus, onShowLarge: () -> Unit) {
    val url = web.panelUrl
    if (url == null) {
        NoteLine(
            when {
                web.problem != null -> web.problem
                !web.running -> "Starting…"
                web.wifi != null -> "No Tailscale address yet: the panel waits for one (or for Panel on Wi-Fi too). Guests can use ${web.guestUrl}"
                else -> "Waiting for a network"
            },
        )
        HairlineDivider(startInset = 16.dp)
        return
    }
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
            Text(url, style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurface)
            Eyebrow(SCAN_NOTE, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
        }
        Spacer(Modifier.width(16.dp))
        QrTile(url, 96.dp, "QR code for the panel's address", onClick = onShowLarge)
    }
    HairlineDivider(startInset = 16.dp)
}
