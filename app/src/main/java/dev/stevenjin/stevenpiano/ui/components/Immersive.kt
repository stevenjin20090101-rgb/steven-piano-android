// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.stevenjin.stevenpiano.ui.theme.AuraDark
import dev.stevenjin.stevenpiano.ui.theme.Backdrop
import dev.stevenjin.stevenpiano.ui.theme.GlassEdgeDark
import dev.stevenjin.stevenpiano.ui.theme.HandLeftDark
import dev.stevenjin.stevenpiano.ui.theme.HandRightDark
import dev.stevenjin.stevenpiano.ui.theme.HandTones
import dev.stevenjin.stevenpiano.ui.theme.ImmersiveScheme
import dev.stevenjin.stevenpiano.ui.theme.InkDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.LiveRedDark
import dev.stevenjin.stevenpiano.ui.theme.LocalAuraStops
import dev.stevenjin.stevenpiano.ui.theme.LocalDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.LocalGlassEdge
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalHandTones
import dev.stevenjin.stevenpiano.ui.theme.LocalLive
import dev.stevenjin.stevenpiano.ui.theme.LocalNoteSounding
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.NoteSoundingDark

/*
 * Immersive (DESIGN.md › v1.18 — M49): while the cover's backdrop shows, what stands on it wears the ink scheme in
 * both appearances ([Immersive]): every word and glyph the ink scheme's primary (nothing secondary or tertiary), the
 * play circle filled in it with its glyph in the ink surface, hairlines and the live red the backdrop's, and the bars
 * and capsules black glass ([GlassSurface] reads [LocalImmersive]). What keeps the app's own appearance inside it
 * ([AppAppearance]): the score's opaque sheet, and every menu, popover, sheet and dialog opened from it.
 */

/** Whether what is drawn here stands on the cover's backdrop: its words light, its bars black glass, the roll without a card. */
val LocalImmersive = staticCompositionLocalOf { false }

/** The app's own appearance as it was outside an immersive region; null outside one. */
private val LocalAppAppearance = staticCompositionLocalOf<AppAppearanceTokens?> { null }

/** Everything [Immersive] changes, as it was: compared by value, so an unchanged appearance recomposes nothing. */
@Immutable
private data class AppAppearanceTokens(
    val scheme: ColorScheme,
    val content: Color,
    val live: Color,
    val sounding: Color,
    val glassEdge: Color,
    val hairline: Color,
    val disabled: Color,
    val tertiary: Color,
    val secondary: Color?,
    val hands: HandTones,
    val aura: List<Color>,
)

/**
 * [content] on the backdrop while it is [shown]: the ink scheme's tokens ([ImmersiveScheme]) and [LocalImmersive];
 * otherwise everything as it was. The same composition either way, so nothing beneath loses its state when the
 * backdrop comes or goes.
 */
@Composable
fun Immersive(shown: Boolean, content: @Composable () -> Unit) {
    val outer = LocalAppAppearance.current
    val app = if (shown && outer == null) currentAppearance() else outer
    CompositionLocalProvider(
        LocalAppAppearance provides app,
        LocalImmersive provides (shown || LocalImmersive.current),
        LocalContentColor provides if (shown) Backdrop.Words else LocalContentColor.current,
        LocalLive provides if (shown) LiveRedDark else LocalLive.current,
        LocalNoteSounding provides if (shown) NoteSoundingDark else LocalNoteSounding.current,
        LocalGlassEdge provides if (shown) GlassEdgeDark else LocalGlassEdge.current,
        LocalHairline provides if (shown) Backdrop.Hairline else LocalHairline.current,
        LocalDisabledGlyph provides if (shown) InkDisabledGlyph else LocalDisabledGlyph.current,
        LocalTertiary provides if (shown) Backdrop.Words else LocalTertiary.current,
        LocalSecondaryText provides if (shown) Backdrop.Words else LocalSecondaryText.current,
        LocalHandTones provides if (shown) HandTones(HandLeftDark, HandRightDark) else LocalHandTones.current,
        LocalAuraStops provides if (shown) AuraDark else LocalAuraStops.current,
    ) {
        MaterialTheme(
            colorScheme = if (shown) ImmersiveScheme else MaterialTheme.colorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

/**
 * [content] in the app's own appearance, inside an immersive region or out of one (where it changes nothing): the
 * score's sheet, and the menus, popovers, sheets and dialogs. The same composition either way.
 */
@Composable
fun AppAppearance(content: @Composable () -> Unit) {
    val app = LocalAppAppearance.current
    CompositionLocalProvider(
        LocalAppAppearance provides null,
        LocalImmersive provides (app == null && LocalImmersive.current),
        LocalContentColor provides (app?.content ?: LocalContentColor.current),
        LocalLive provides (app?.live ?: LocalLive.current),
        LocalNoteSounding provides (app?.sounding ?: LocalNoteSounding.current),
        LocalGlassEdge provides (app?.glassEdge ?: LocalGlassEdge.current),
        LocalHairline provides (app?.hairline ?: LocalHairline.current),
        LocalDisabledGlyph provides (app?.disabled ?: LocalDisabledGlyph.current),
        LocalTertiary provides (app?.tertiary ?: LocalTertiary.current),
        LocalSecondaryText provides if (app != null) app.secondary else LocalSecondaryText.current,
        LocalHandTones provides (app?.hands ?: LocalHandTones.current),
        LocalAuraStops provides (app?.aura ?: LocalAuraStops.current),
    ) {
        MaterialTheme(
            colorScheme = app?.scheme ?: MaterialTheme.colorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

@Composable
private fun currentAppearance() = AppAppearanceTokens(
    scheme = MaterialTheme.colorScheme,
    content = LocalContentColor.current,
    live = LocalLive.current,
    sounding = LocalNoteSounding.current,
    glassEdge = LocalGlassEdge.current,
    hairline = LocalHairline.current,
    disabled = LocalDisabledGlyph.current,
    tertiary = LocalTertiary.current,
    secondary = LocalSecondaryText.current,
    hands = LocalHandTones.current,
    aura = LocalAuraStops.current,
)
