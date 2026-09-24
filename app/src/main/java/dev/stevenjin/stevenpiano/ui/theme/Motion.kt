// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Motion budget (DESIGN.md › Motion). Purposeful, brief, cancellable, rare on
// frequent interactions. There is exactly one orchestrated moment: pressing play.
object Motion {
    const val FastMs      = 120   // note brightens crossing the tracker bar
    const val StandardMs  = 240   // tab fade-through
    const val RollStartMs = 320   // the roll easing from still to scrolling
    const val LivePulseMs = 2000  // the dot breathing while playing (100% -> 55%)

    val EaseOut: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val Standard: Easing = FastOutSlowInEasing
}

// True when the person has turned animations off at the system level ("Remove
// animations" / animator duration scale 0). Decorative motion must then be a cut;
// the roll still scrolls because it is content and progress, not decoration.
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        try {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
    }
}
