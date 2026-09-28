// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.PinSheet
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.leave
import kotlinx.coroutines.launch

/** The Kiosk page's words (DESIGN.md › v1.6 — M20). */
object KioskPageCopy {
    /** Under Kiosk mode while the app is not the tablet's device owner. */
    const val MAKE_DEVICE_OWNER = "Make the app the device owner first: README › Kiosk"

    /** Under Kiosk mode while it is off and can come on: what it does, and the way out. */
    const val WHAT_IT_DOES =
        "The tablet shows only this app, wakes into it and comes back to it after a restart. To leave, hold the byline under any tab's title for three seconds."

    /** Under Kiosk mode when Android kept the tablet's screen lock. */
    const val SCREEN_LOCK_KEPT =
        "The tablet has a screen lock, so after a restart it waits at the lock screen. Remove the lock in Android's settings to start straight into the piano."

    const val UNLOCK_NOTE = "Home and the other apps come back until the app is opened again"
    const val LOCK_AGAIN_NOTE = "Unlocked until the app is opened again, or left alone"
    const val PIN_NOTE = "Six digits, asked for to leave kiosk mode"
    const val DISPLAY_NOTE = "Display mode is always on in kiosk"

    /** The line under the switch: why it can't come on, what it does, or what Android kept. */
    fun switchNote(on: Boolean, status: KioskStatus, pinSet: Boolean): String? = when {
        status.problem != null -> status.problem
        on -> if (status.keyguardKept) SCREEN_LOCK_KEPT else null
        !status.owner -> MAKE_DEVICE_OWNER
        !pinSet -> SET_A_PIN_FIRST
        else -> WHAT_IT_DOES
    }
}

/**
 * Kiosk (DESIGN.md › v1.6 — M20): the school tablet shows the app and nothing else. Kiosk mode, a
 * switch, needs the app to be the device owner and a PIN (it says which is missing); turning it off
 * asks for the PIN. While it is on: Unlock for now (the PIN, then Home and the other apps until the
 * app is opened again), or Lock again while unlocked. Set a PIN / Change PIN (six digits, twice;
 * while kiosk mode is on, the old PIN first). The page ends with "Display mode is always on in
 * kiosk". Everything it starts runs in the app's scope, so leaving the page never cuts it short.
 * The hub's row reads "On" or "Off".
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
    SwitchRow(
        "Kiosk mode",
        on,
        onChange = { wanted -> if (wanted) graph.appScope.launch { kiosk.turnOn() } else pinCheck = KioskExit.TurnOff },
        enabled = on || (status.owner && settings.kioskPinSet),
        note = KioskPageCopy.switchNote(on, status, settings.kioskPinSet),
    )
    if (on && status.unlockedForNow) {
        ActionRow(note = KioskPageCopy.LOCK_AGAIN_NOTE) { ActionButton("Lock again", onClick = kiosk::relock) }
    } else if (on) {
        ActionRow(note = KioskPageCopy.UNLOCK_NOTE) { ActionButton(KioskExit.Unlock.label, onClick = { pinCheck = KioskExit.Unlock }) }
    }
    ActionRow(note = KioskPageCopy.PIN_NOTE) {
        ActionButton(
            if (settings.kioskPinSet) "Change PIN" else "Set a PIN",
            onClick = { if (on && settings.kioskPinSet) pinCheck = KioskExit.ChangePin else newPin = true },
        )
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
