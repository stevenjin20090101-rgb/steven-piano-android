// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.PianoBluetooth
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.LiveDot
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.actionButtonColors
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * The piano: its name, the status with the dot, and one button that says what it will do:
 * Connect, Cancel (while looking), Disconnect (while connected). While looking, an indeterminate
 * hairline. A problem is copy in place, with Retry or the fix under it: Turn on Bluetooth, Open
 * Location settings, Open Bluetooth settings (to forget a stale pairing, with Retry beside it). When
 * the piano was not found, a tip in the eyebrow style says how its screen shows that another
 * device holds it, with Retry and, when Location is off, Open Location settings. Permission is
 * asked for here, in context, with a one-line reason. When only another piano called Steven Piano
 * answered, the phone connects to it only if the person taps Connect to it ([onConnectTo] with its
 * address). Monochrome throughout: the only red is the [LiveDot]. Under a MIDI piano (v1.11 — M29) it names that
 * instrument ([name]) with its own words ([status]), and asks for no Bluetooth permission for one on a cable
 * ([bluetooth] false).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionCard(
    link: LinkState,
    playing: Boolean,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectTo: (String) -> Unit,
    modifier: Modifier = Modifier,
    name: String = PianoBluetooth.NAME,
    status: String = statusOf(link),
    bluetooth: Boolean = true,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var missing by remember { mutableStateOf(BlePermissions.missing(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }   // "Don't allow" for good: only Settings can grant it now
    LifecycleResumeEffect(Unit) {
        missing = BlePermissions.missing(context)
        onPauseOrDispose { }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = BlePermissions.missing(context)
        if (missing.isEmpty()) {
            onConnect()
        } else {
            refused = activity != null && missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        }
    }
    val fixThenRetry = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onConnect() }
    val searching = link == LinkState.Scanning || link == LinkState.Connecting || link is LinkState.Reconnecting
    val needsPermission = bluetooth && missing.isNotEmpty() && !searching && link !is LinkState.Connected

    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot(live = link is LinkState.Connected, breathing = playing)
                Spacer(Modifier.width(8.dp))
                Text(
                    status,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (searching) ProgressHairline(null, Modifier.padding(top = 12.dp))
            if (needsPermission) {
                Text(
                    permissionReason(),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (refused) TextButton(onClick = { context.startActivity(appSettings(context)) }) { Text("Open app settings") }
            } else if (link is LinkState.Error) {
                OutlinedBanner(link.message, Modifier.padding(top = 12.dp)) {
                    val openLocation = { runCatching { fixThenRetry.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } }
                    when (val reason = link.reason) {
                        LinkError.BluetoothOff -> TextButton(onClick = { runCatching { fixThenRetry.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } }) {
                            Text("Turn on Bluetooth")
                        }
                        LinkError.LocationOff -> TextButton(onClick = { openLocation() }) { Text("Open Location settings") }
                        LinkError.OtherPiano -> link.otherAddress?.let { other ->
                            TextButton(onClick = { onConnectTo(other) }) { Text("Connect to it") }
                        }
                        LinkError.Paired -> FlowRow {
                            TextButton(onClick = { runCatching { fixThenRetry.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) {
                                Text("Open Bluetooth settings")
                            }
                            TextButton(onClick = onConnect) { Text("Retry") }
                        }
                        is LinkError.NotFound -> Column {
                            Eyebrow(NOT_FOUND_TIP, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
                            FlowRow(Modifier.padding(top = 4.dp)) {
                                TextButton(onClick = onConnect) { Text("Retry") }
                                if (reason.locationOff) TextButton(onClick = { openLocation() }) { Text("Open Location settings") }
                            }
                        }
                        else -> TextButton(onClick = onConnect) { Text("Retry") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when {
                link is LinkState.Connected -> OutlinedButton(onClick = onDisconnect, border = BorderStroke(Hairline, LocalTertiary.current), colors = actionButtonColors()) {
                    Text("Disconnect")
                }
                searching -> OutlinedButton(onClick = onCancel, border = BorderStroke(Hairline, LocalTertiary.current), colors = actionButtonColors()) {
                    Text("Cancel")
                }
                else -> Button(onClick = { if (!bluetooth || missing.isEmpty()) onConnect() else askPermission.launch(missing.toTypedArray()) }) { Text("Connect") }
            }
        }
    }
}

/**
 * Under "Can't find Steven Piano": the piano's own screen names its Bluetooth state (firmware
 * `ui_refresh_status`: "BLE MIDI: CONNECTED" while a central holds it, "BLE MIDI: advertising..."
 * while it is free), and a connected piano cannot be found.
 */
private const val NOT_FOUND_TIP = "Tip: if the piano's screen reads “BLE MIDI: CONNECTED”, another device is connected to it."

private fun statusOf(link: LinkState): String = when (link) {
    is LinkState.Connected -> "Connected"
    LinkState.Scanning -> "Looking for the piano…"
    LinkState.Connecting -> "Connecting…"
    is LinkState.Reconnecting -> "Reconnecting…"
    LinkState.Disconnected, is LinkState.Error -> "Not connected"
}

/** The one-line reason, in the words Android's own permission dialog uses. */
private fun permissionReason(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "Allow Nearby devices to find the piano."
    } else {
        "Allow Location to find the piano over Bluetooth."
    }

private fun appSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
