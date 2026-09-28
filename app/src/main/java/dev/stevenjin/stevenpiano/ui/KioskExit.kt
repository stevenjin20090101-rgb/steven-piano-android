// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.admin.KioskMode
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.PinCheckSheet
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Kiosk mode's way out (DESIGN.md › v1.6.1 — M20): nothing on screen hints at it. While kiosk mode is
// on, a three-second hold on the byline under any tab's title opens the PIN sheet; the right PIN
// offers "Unlock for now" and "Turn kiosk off". The Kiosk page asks for the same PIN before it
// turns kiosk mode off, unlocks or changes the PIN.

/**
 * What a three-second hold on a tab's byline does ([ScreenHeader][dev.stevenjin.stevenpiano.ui.components.ScreenHeader]):
 * null while kiosk mode is off, so the byline is plain text with no gesture at all.
 */
val LocalBylineHold = compositionLocalOf<(() -> Unit)?> { null }

/** Kiosk mode's words. */
object KioskCopy {
    const val EYEBROW = "Kiosk"
    const val TITLE = "Enter the PIN"
    const val HINT = "The six digits set in Piano › Kiosk."

    /** What TalkBack offers on the byline while kiosk mode is on, in place of the hold. */
    const val HOLD_ACTION = "Kiosk PIN"
}

/** What the right kiosk PIN can open. */
enum class KioskExit(val label: String) {
    /** The screen let go until the app is next opened. */
    Unlock("Unlock for now"),

    /** Kiosk mode off: Home, Recents and the other apps back, the lock screen as it was. */
    TurnOff("Turn kiosk off"),

    /** The Kiosk page's Change PIN, while kiosk mode is on: the new PIN's sheet follows. */
    ChangePin("Change PIN"),
    ;

    companion object {
        /** What the byline's sheet offers: both ways out, or only Turn kiosk off while already unlocked for now. */
        fun offered(unlocked: Boolean): List<KioskExit> = if (unlocked) listOf(TurnOff) else listOf(Unlock, TurnOff)
    }
}

/** How long the byline is held before the PIN sheet opens. */
const val KIOSK_HOLD_MS = 3_000L

/**
 * A tab's byline while kiosk mode is on: the same eyebrow, which a three-second hold turns into
 * [onHeld] (the PIN sheet). While the finger is down a hairline in the byline's own grey grows along
 * its foot over the three seconds (shown whole at once when motion is reduced: a cut), and goes the
 * moment the finger lifts or strays. It takes no room of its own, so the header never moves. For
 * TalkBack, the same as an action ([KioskCopy.HOLD_ACTION]).
 */
@Composable
fun HeldByline(text: String, onHeld: () -> Unit) {
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onHeld)
    var fill by remember { mutableFloatStateOf(0f) }
    val ink = LocalTertiary.current
    Eyebrow(
        text,
        Modifier
            .semantics { customActions = listOf(CustomAccessibilityAction(KioskCopy.HOLD_ACTION) { latest(); true }) }
            .pointerInput(reduced) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val hold = scope.launch {
                        if (reduced) {
                            fill = 1f
                            delay(KIOSK_HOLD_MS)
                        } else {
                            val start = withFrameMillis { it }
                            do {
                                val now = withFrameMillis { it }
                                fill = ((now - start).toFloat() / KIOSK_HOLD_MS).coerceAtMost(1f)
                            } while (fill < 1f)
                        }
                        fill = 0f
                        latest()
                    }
                    waitForUpOrCancellation()
                    hold.cancel()
                    fill = 0f
                }
            }
            .drawBehind {
                if (fill <= 0f) return@drawBehind
                val line = Hairline.toPx()
                val width = size.width * fill
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                drawRect(ink, topLeft = Offset(x, size.height - line), size = Size(width, line))
            },
    )
}

/**
 * The kiosk PIN, before whatever [offers] lists (the byline's hold: Unlock for now and Turn kiosk
 * off; the Kiosk page: one of them, or Change PIN). The right PIN hands [onRight] the chosen one.
 */
@Composable
fun KioskPinSheet(offers: List<KioskExit>, onRight: (KioskExit) -> Unit, onDismiss: () -> Unit) {
    val kiosk = LocalContext.current.graph.kiosk
    PinCheckSheet(
        eyebrow = KioskCopy.EYEBROW,
        title = KioskCopy.TITLE,
        hint = KioskCopy.HINT,
        actions = offers.map { it.label },
        waitMs = kiosk::waitMs,
        check = kiosk::check,
        onRight = { onRight(offers[it]) },
        onDismiss = onDismiss,
    )
}

/**
 * The byline's sheet, over any tab: the right PIN unlocks for now (not offered while already
 * unlocked) or turns kiosk mode off. The steps run in the app's scope, so closing the sheet never
 * cuts them short.
 */
@Composable
fun KioskExitSheet(onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val status by graph.kiosk.status.collectAsStateWithLifecycle()
    KioskPinSheet(
        offers = KioskExit.offered(status.unlockedForNow),
        onRight = { exit ->
            graph.kiosk.leave(exit, graph.appScope)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** Unlock for now, or kiosk mode off (in [scope], the app's: off waits for the screen to be let go). Change PIN has its own sheet. */
fun KioskMode.leave(exit: KioskExit, scope: CoroutineScope) {
    when (exit) {
        KioskExit.Unlock -> unlockForNow()
        KioskExit.TurnOff -> scope.launch { turnOff() }
        KioskExit.ChangePin -> Unit
    }
}
