// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.stevenjin.stevenpiano.BuildConfig
import kotlinx.coroutines.delay

/**
 * When the screen was last touched, and whether it has been left alone for [timeoutMs] (display
 * mode, DESIGN.md › v1.5 — M17). A finger on the glass sends dozens of events a second, so a touch
 * is remembered at most once a second ([WRITE_EVERY_MS]); once idle, the first touch always is, as
 * it ends idleness. Pure: the time is given.
 */
class IdleTimer(now: Long, val timeoutMs: Long) {
    var lastTouchAt: Long = now
        private set

    /** A touch at [now]; true when it was remembered. */
    fun touch(now: Long): Boolean {
        if (!isIdle(now) && now - lastTouchAt < WRITE_EVERY_MS) return false
        lastTouchAt = now
        return true
    }

    fun isIdle(now: Long): Boolean = now - lastTouchAt >= timeoutMs

    /** How long from [now] until idle; 0 once it is. */
    fun remaining(now: Long): Long = (lastTouchAt + timeoutMs - now).coerceAtLeast(0L)

    /** The same last touch, idle after [timeoutMs] instead. */
    fun retimed(timeoutMs: Long): IdleTimer = if (timeoutMs == this.timeoutMs) this else IdleTimer(lastTouchAt, timeoutMs)

    companion object {
        const val WRITE_EVERY_MS = 1_000L
    }
}

/**
 * The app's idleness, kept at the nav host's root: [touch] from every pointer event
 * ([watchTouches]), [idle] once none came for the timeout ([rememberIdle]).
 */
@Stable
class IdleState internal constructor(private val clock: () -> Long, timeoutMs: Long) {
    private var timer = IdleTimer(clock(), timeoutMs)

    /** Counts the touches the timer remembered (at most one a second): the wait for idleness starts again on each. */
    internal var touches by mutableIntStateOf(0)
        private set

    /** No touch for the timeout, while watching is enabled. */
    var idle by mutableStateOf(false)
        internal set

    fun touch() {
        if (timer.touch(clock())) touches++
        if (idle) idle = false
    }

    internal fun remaining(timeoutMs: Long): Long {
        timer = timer.retimed(timeoutMs)
        return timer.remaining(clock())
    }
}

/**
 * Observes every pointer event under this node on the Initial pass (before any child sees it) and
 * tells [onTouch]; consumes nothing, so everything beneath works as before. Keep [onTouch] stable:
 * the first one given is the one called.
 */
fun Modifier.watchTouches(onTouch: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            onTouch()
        }
    }
}

/** The app's idleness: [IdleState.idle] once [enabled] and no touch came for [timeoutMs]. */
@Composable
fun rememberIdle(enabled: Boolean, timeoutMs: Long): IdleState {
    val state = remember { IdleState(SystemClock::uptimeMillis, timeoutMs) }
    LaunchedEffect(state, enabled, timeoutMs, state.touches) {
        if (!enabled) {
            state.idle = false
            return@LaunchedEffect
        }
        delay(state.remaining(timeoutMs))
        state.idle = true
    }
    return state
}

/** Display mode's wait: a minute; in debug builds `adb shell setprop debug.stevenpiano.idlesecs 5` shortens it (read once). */
object DisplayModeTimeout {
    const val DEFAULT_MS = 60_000L
    private const val PROPERTY = "debug.stevenpiano.idlesecs"

    val ms: Long by lazy {
        if (!BuildConfig.DEBUG) return@lazy DEFAULT_MS
        val seconds = runCatching {
            ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("").toLongOrNull()
        if (seconds != null && seconds > 0) seconds * 1_000L else DEFAULT_MS
    }
}

/**
 * When display mode comes: the idle clock runs while Display mode after a minute is on, or kiosk mode
 * is (DESIGN.md › v1.6 — M20: there it is always on); once idle it shows while a piece is loaded, or
 * in kiosk mode with nothing loaded too, its resting state (the byline and, with guests on, the
 * request code).
 */
object DisplayRule {
    fun watched(afterMinute: Boolean, kiosk: Boolean): Boolean = afterMinute || kiosk

    fun shows(pieceLoaded: Boolean, kiosk: Boolean): Boolean = pieceLoaded || kiosk
}
