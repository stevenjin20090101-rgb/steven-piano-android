// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.admin.KioskStatus
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.KioskCopy
import dev.stevenjin.stevenpiano.ui.KioskExit
import dev.stevenjin.stevenpiano.ui.KioskPinSheet
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.PinSheet
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.leave
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import kotlinx.coroutines.launch

/** The Kiosk page's words (DESIGN.md › v1.6.1 — M20). */
object KioskPageCopy {
    /** Under Kiosk mode while the app is not the tablet's device owner: the steps follow (v1.39 — M77). */
    const val MAKE_DEVICE_OWNER = "Make the app the device owner first: the steps are below"

    /**
     * The setup guide (v1.39 — M77), while the app is not the device owner: its heading, why, the four steps and the one
     * adb line. Android refuses the device owner while any account is on the tablet (a Google account, a Lenovo ID), with
     * "Not allowed to set the device owner because there are already some accounts on the device".
     */
    const val SETUP = "Set up kiosk"
    const val SETUP_WHY =
        "Kiosk mode needs the app to be the tablet's device owner, set once with a computer and a USB cable. Without this, kiosk mode can't lock the tablet."
    val SETUP_STEPS = listOf(
        "Remove every account in Settings › Accounts: Google, Lenovo ID, any other. They can be re-added after.",
        "Turn on USB debugging, in Settings › Developer options.",
        "Connect the tablet to a computer and run this one line:",
        "Come back here, set a PIN below, and turn on Kiosk mode.",
    )
    const val ADB_LINE = "adb shell dpm set-device-owner dev.stevenjin.stevenpiano/.admin.PianoDeviceAdmin"
    const val ACCOUNTS_LEFT = "If it answers that there are already some accounts on the device, one is left: step 1 again."
    const val COPY = "Copy"
    const val COPIED = "Copied"

    /**
     * Whether the switch can be touched (v1.39 — M77: never on without the device owner, so it can't look on and do
     * nothing; there is no screen-pinning fallback). Off it needs the device owner and a PIN; on, it can always go off.
     */
    fun switchEnabled(on: Boolean, status: KioskStatus, pinSet: Boolean): Boolean = on || (status.owner && pinSet)

    /** Whether the setup guide shows: the start-up checks done, and the app not the device owner. */
    fun showsSetup(status: KioskStatus): Boolean = status.checked && !status.owner

    /** Under Kiosk mode while it is off and can come on: what it does, and the way out. */
    const val WHAT_IT_DOES =
        "The tablet shows only this app, wakes into it and comes back to it after a restart. To leave, hold the byline under any tab's title for three seconds."

    /** Under Kiosk mode when Android kept the tablet's screen lock. */
    const val SCREEN_LOCK_KEPT =
        "The tablet has a screen lock, so after a restart it waits at the lock screen. Remove the lock in Android's settings to start straight into the piano."

    const val UNLOCK_NOTE = "Home and the other apps come back until the app is opened again"
    const val LOCK_AGAIN_NOTE = "Unlocked until the app is opened again, or left alone"
    const val PIN_NOTE = "Six digits, asked for to leave kiosk mode"
    const val DISPLAY_NOTE = "The resting screen is always on in kiosk"

    /** The line under the switch, when it can't come on (or Android refused it): what is missing. */
    fun switchNote(on: Boolean, status: KioskStatus, pinSet: Boolean): String? = when {
        status.problem != null -> status.problem
        on -> null
        !status.owner -> MAKE_DEVICE_OWNER
        !pinSet -> SET_A_PIN_FIRST
        else -> null
    }

    /** The paragraph under the switch: what kiosk mode does and the way out, before it comes on; what Android kept, once on. */
    fun explanation(on: Boolean, status: KioskStatus, pinSet: Boolean): String? = when {
        on -> if (status.keyguardKept) SCREEN_LOCK_KEPT else null
        status.owner && pinSet && status.problem == null -> WHAT_IT_DOES
        else -> null
    }
}

/**
 * Kiosk (DESIGN.md › v1.6.1 — M20): the school tablet shows the app and nothing else. Kiosk mode, a
 * switch, needs the app to be the device owner and a PIN (it says which is missing), with what it
 * does and the way out under it before it comes on; turning it off asks for the PIN. While it is
 * on: Unlock for now (the PIN, then Home and the other apps until the app is opened again), or Lock
 * again while unlocked. Set a PIN / Change PIN (six digits, twice; while kiosk mode is on, the old
 * PIN first). The page ends with "The resting screen is always on in kiosk". Everything it starts runs in
 * the app's scope, so leaving the page never cuts it short. The hub's row reads "On" or "Off". v1.39 — M77: without the
 * device owner the switch stays off and can't be touched, and the page shows how to set it up ([SetupGuide]).
 */
@Composable
fun KioskPage(settings: PianoSettings) {
    val graph = LocalContext.current.graph
    val kiosk = graph.kiosk
    val status by kiosk.status.collectAsStateWithLifecycle()
    var pinCheck by rememberSaveable { mutableStateOf<KioskExit?>(null) }
    var newPin by rememberSaveable { mutableStateOf(false) }
    // The device owner may have been set over adb while the app ran: asked again whenever the page shows.
    LifecycleResumeEffect(Unit) {
        kiosk.refresh()
        onPauseOrDispose { }
    }
    val on = settings.kioskEnabled

    SectionRule()
    Anchored(PageRows.KIOSK_MODE.anchor) {
        SwitchRow(
            PageRows.KIOSK_MODE.label,
            on,
            onChange = { wanted -> if (wanted) graph.appScope.launch { kiosk.turnOn() } else pinCheck = KioskExit.TurnOff },
            enabled = KioskPageCopy.switchEnabled(on, status, settings.kioskPinSet),
            note = KioskPageCopy.switchNote(on, status, settings.kioskPinSet),
        )
        KioskPageCopy.explanation(on, status, settings.kioskPinSet)?.let {
            NoteLine(it)
            HairlineDivider(startInset = 16.dp)
        }
    }
    if (KioskPageCopy.showsSetup(status)) SetupGuide()
    if (on) {
        Anchored(PageRows.KIOSK_UNLOCK.anchor) {
            if (status.unlockedForNow) {
                ActionRow(note = KioskPageCopy.LOCK_AGAIN_NOTE) { ActionButton("Lock again", onClick = kiosk::relock) }
            } else {
                ActionRow(note = KioskPageCopy.UNLOCK_NOTE) { ActionButton(KioskExit.Unlock.label, onClick = { pinCheck = KioskExit.Unlock }) }
            }
        }
    }
    Anchored(PageRows.KIOSK_PIN.anchor) {
        ActionRow(note = KioskPageCopy.PIN_NOTE) {
            ActionButton(
                if (settings.kioskPinSet) "Change PIN" else "Set a PIN",
                onClick = { if (on && settings.kioskPinSet) pinCheck = KioskExit.ChangePin else newPin = true },
            )
        }
    }
    NoteLine(KioskPageCopy.DISPLAY_NOTE)

    pinCheck?.let { exit ->
        KioskPinSheet(
            listOf(exit),
            onRight = {
                pinCheck = null
                if (exit == KioskExit.ChangePin) newPin = true else kiosk.leave(exit, graph.appScope)
            },
            onDismiss = { pinCheck = null },
        )
    }
    if (newPin) {
        PinSheet(
            eyebrow = KioskCopy.EYEBROW,
            title = if (settings.kioskPinSet) "Change PIN" else "Set a PIN",
            onSet = { pin ->
                graph.appScope.launch { kiosk.setPin(pin) }
                newPin = false
            },
            onDismiss = { newPin = false },
        )
    }
}

/**
 * Setting up kiosk (v1.39 — M77), while the app is not the device owner: why, then four numbered steps, the adb line in
 * a box with Copy. Coming back to the page asks Android again ([KioskPage]'s refresh), and the guide goes once it is done.
 */
@Composable
private fun SetupGuide() {
    val context = LocalContext.current
    var copied by rememberSaveable { mutableStateOf(false) }
    SectionEyebrow(KioskPageCopy.SETUP)
    NoteLine(KioskPageCopy.SETUP_WHY)
    KioskPageCopy.SETUP_STEPS.forEachIndexed { index, step ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("${index + 1}.", Modifier.width(28.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.weight(1f)) {
                Text(step, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (index == ADB_STEP) {
                    Surface(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        SelectionContainer {
                            Text(
                                KioskPageCopy.ADB_LINE,
                                Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    ActionButton(
                        if (copied) KioskPageCopy.COPIED else KioskPageCopy.COPY,
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText(KioskPageCopy.SETUP, KioskPageCopy.ADB_LINE))
                            copied = true
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        KioskPageCopy.ACCOUNTS_LEFT,
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HairlineDivider(startInset = 16.dp)
}

/** The step whose line is run on the computer. */
private const val ADB_STEP = 2
