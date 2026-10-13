// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Motion budget (DESIGN.md › Motion, › v1.14 — motion). Purposeful, brief, cancellable. Nothing loops by itself
// but the aura and, while a piece plays, the album-colour backdrop (v1.15 — M41); status that lasts (the live dot's
// breath, a search's sweep, an indeterminate hairline) moves only while it lasts. Every transition takes one of the
// durations below, the longest 480 ms; it enters decelerating and leaves accelerating; and it goes through the helper
// at the end ([Motion.timed], [Motion.sprung], [Motion.enter], [Motion.exit], [Motion.change]), which makes it a cut
// when motion is reduced.
object Motion {
    /** Quick: something leaving, a digit rolling, a pressed glass settling; the note brightening at the tracker bar. */
    const val QuickMs = 120

    /** A menu or a popover growing from its anchor. */
    const val PopMs = 160

    /** Standard: a tab fading in, a Piano page, a glyph changing, a hairline easing to its value, a row easing in. */
    const val StandardMs = 200

    /** Emphasised: the roll easing from still, the art growing into Now playing, a Studio turn arriving. */
    const val EmphasisedMs = 320

    /** Slow, and the longest any transition may take. */
    const val SlowMs = 480

    /** [QuickMs] by its name before v1.14, which only the web panel's asset test (M56's) still reads; it goes with that. */
    const val FastMs = QuickMs

    /** A pressed control's scale; what grows in (the art, a popover) starts from [GrowFrom]. */
    const val PressedScale = 0.97f
    const val GrowFrom = 0.92f

    /** Entering decelerates; leaving accelerates; [Standard] for what moves within the screen. */
    val Enter: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val Leave: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
    val Standard: Easing = FastOutSlowInEasing

    /** The press spring, a control under a finger: firm, barely overshooting (it settles in about a tenth of a second). */
    const val PressDamping = 0.8f
    const val PressStiffness = 3_000f   // between Spring.StiffnessMedium (1,500) and Spring.StiffnessHigh (10,000)

    /** The settle spring, things coming to rest (rows finding their places): softer, no visible overshoot. */
    const val SettleDamping = 0.9f
    const val SettleStiffness = Spring.StiffnessMediumLow

    fun <T> press(): SpringSpec<T> = spring(PressDamping, PressStiffness)

    fun <T> settle(): SpringSpec<T> = spring(SettleDamping, SettleStiffness)

    // The helper every animation goes through: when motion is reduced (rememberReducedMotion) a transition is a cut.

    /** [ms] of [easing] after [delayMs], never longer than [SlowMs]; a cut when [reduced]. */
    fun <T> timed(ms: Int, reduced: Boolean, easing: Easing = Standard, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(ms.coerceAtMost(SlowMs), delayMs, easing)

    /** One of the two springs ([press], [settle]); a cut when [reduced]. */
    fun <T> sprung(spec: SpringSpec<T>, reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else spec

    /** Content coming in by [transition]; a cut when [reduced]. */
    fun enter(transition: EnterTransition, reduced: Boolean): EnterTransition = if (reduced) EnterTransition.None else transition

    /** Content going by [transition]; a cut when [reduced]. */
    fun exit(transition: ExitTransition, reduced: Boolean): ExitTransition = if (reduced) ExitTransition.None else transition

    /** One content giving way to another; a cut when [reduced]. */
    fun change(incoming: EnterTransition, outgoing: ExitTransition, reduced: Boolean): ContentTransform =
        enter(incoming, reduced) togetherWith exit(outgoing, reduced)
}

// True when the person has turned animations off at the system level ("Remove animations" / animator
// duration scale 0). Decorative motion must then be a cut; the roll still scrolls because it is content and
// progress, not decoration. Followed while the app is open (v1.25 — M59), as the glass's accessibility states are
// (rememberGlassAccessibility): turned off or on in the system's settings, it takes effect at once.
@Composable
fun rememberReducedMotion(): Boolean {
    val app = LocalContext.current.applicationContext
    return remember(app) { ReducedMotion.state(app.contentResolver) }.value
}

/**
 * The animator duration scale, watched for the whole process: one observer, registered on the application's resolver
 * by the first reader and kept while the process lives (every screen reads it, so it is never idle for long), its
 * state read in composition so a change recomposes what reads it.
 */
private object ReducedMotion {
    private var watched: State<Boolean>? = null

    fun state(resolver: ContentResolver): State<Boolean> {
        watched?.let { return it }
        val state = mutableStateOf(reducedMotion(resolver))
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = reducedMotion(resolver)
            }
        }
        runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer) }
        watched = state
        return state
    }
}

/** [rememberReducedMotion] outside composition (a modifier node reads it when it is attached). */
fun reducedMotion(resolver: ContentResolver): Boolean =
    try {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }
