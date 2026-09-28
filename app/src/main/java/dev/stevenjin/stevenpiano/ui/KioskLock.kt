// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.PinCheckSheet
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

// Settings locked in kiosk (DESIGN.md › v1.6.1 — M20): the tablet stands in a public space, so while
// kiosk mode is on, playing, queueing, browsing and the Keys tab stay free, and anything that changes
// the piano or the library asks for the kiosk PIN first. A right PIN opens them for five minutes, or
// until the tablet rests in display mode; "Unlock for now" counts as open.

/** The lock's words. */
object KioskLockCopy {
    /** The PIN sheet's title before a locked setting. */
    const val TITLE = "Settings are locked in kiosk"

    /** The sheet's line under the title, until a try says otherwise. */
    const val HINT = "The kiosk PIN opens them for five minutes."

    /** The sheet's one button. */
    const val UNLOCK = "Unlock"

    /** What TalkBack says of a locked row or button. */
    const val LOCKED = "Locked"

    /** A settings page while locked, in place of its controls. */
    const val PAGE_NOTE = "Settings are locked in kiosk."
}

/**
 * A screen's view of the lock: [locked] while kiosk mode keeps the settings closed; [run] does an
 * action at once, or, while locked, only after the kiosk PIN, which [KioskGateSheet] asks for.
 */
@Stable
class KioskGate internal constructor(private val lockedState: State<Boolean>) {
    val locked: Boolean get() = lockedState.value

    /** The action waiting for the PIN; the sheet is up while there is one. */
    internal var pending by mutableStateOf<(() -> Unit)?>(null)

    /** [action] now, or after the kiosk PIN while the settings are locked. */
    fun run(action: () -> Unit) {
        if (locked) pending = action else action()
    }
}

/** The lock as the screen sees it. Pair it with a [KioskGateSheet]. */
@Composable
fun rememberKioskGate(): KioskGate {
    val locked = LocalContext.current.graph.kiosk.settingsLocked.collectAsStateWithLifecycle()
    return remember(locked) { KioskGate(locked) }
}

/**
 * The kiosk PIN before a locked setting: KIOSK, "Settings are locked in kiosk", the line, the field
 * and Unlock. The right PIN opens the settings for five minutes, then the waiting action runs.
 */
@Composable
fun KioskGateSheet(gate: KioskGate) {
    val action = gate.pending ?: return
    val kiosk = LocalContext.current.graph.kiosk
    PinCheckSheet(
        eyebrow = KioskCopy.EYEBROW,
        title = KioskLockCopy.TITLE,
        hint = KioskLockCopy.HINT,
        actions = listOf(KioskLockCopy.UNLOCK),
        waitMs = kiosk::waitMs,
        check = kiosk::check,
        onRight = {
            kiosk.unlockSettings()
            gate.pending = null
            action()
        },
        onDismiss = { gate.pending = null },
    )
}

/** The small padlock of a locked setting, in the tertiary grey; [description] for TalkBack where it stands alone in a row. */
@Composable
fun LockGlyph(modifier: Modifier = Modifier, description: String? = KioskLockCopy.LOCKED) {
    Icon(
        painterResource(R.drawable.ic_lock),
        contentDescription = description,
        modifier = modifier.size(LOCK_GLYPH),
        tint = LocalTertiary.current,
    )
}

/** The padlock's size: smaller than a row's 24 dp glyphs, beside which it stands. */
private val LOCK_GLYPH = 18.dp

/**
 * A settings page while the settings are locked (the page beside the hub on a tablet, or a page
 * left open when the five minutes ran out or the tablet rested): the page's controls give way to
 * "Settings are locked in kiosk." and Unlock, which asks for the PIN.
 */
@Composable
fun LockedPage(gate: KioskGate) {
    SectionRule()
    NoteLine(KioskLockCopy.PAGE_NOTE)
    ActionRow(note = KioskLockCopy.HINT) {
        ActionButton(KioskLockCopy.UNLOCK, onClick = { gate.run {} })
    }
}
