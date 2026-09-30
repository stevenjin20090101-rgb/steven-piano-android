// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.theme

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.BuildConfig

// Liquid Glass across the functional layer (DESIGN.md › v1.9, after v1.5 — M16): two layers only. The
// content (rows, cards, tiles, the roll, the score, the art, the resting screen) is never glass; the
// functional layer (the headers, the tab bar and the rail, the mini player, the transport, sheets,
// menus, popovers and dialogs, the Keys pills) is. Regular glass, monochrome: the container is the
// surface colour itself over a blur of whatever lies beneath, with no tint and no noise; bars are
// clearer, the larger surfaces (sheets, menus, dialogs) more opaque, as Apple's are.

/** The material's measures. */
object GlassTokens {
    /** Bars (the headers, the tab bar and the rail, the mini player, the transport, the Keys pills): the surface colour at this opacity over the blur. */
    const val ContainerAlpha = 0.72f

    /**
     * Sheets, menus, popovers and dialogs: larger, text-heavy, so more opaque. 0.86 rather than the
     * brief's 0.84: the least fill at which the secondary grey still reads 4.5:1 over the worst
     * backdrop on both appearances (4.3:1 on the paper at 0.84; GlassTokensTest).
     */
    const val SheetAlpha = 0.86f

    /** How far the content beneath is blurred. */
    val Blur = 24.dp

    /**
     * The wide surfaces blur a copy of what lies beneath at a fifth of its resolution: a header, as wide
     * as its pane and blurring on every frame a list scrolls beneath it, and the sheets, menus and
     * dialogs, through whose 0.86 fill a seventh of the blur shows. The bars keep a third (Haze's
     * automatic scale, as M16 measured them). The difference does not show under a 24 dp blur.
     */
    const val WideInputScale = 0.2f

    /** The edge (the hairline token) and the specular line along it, 1 dp each. */
    val Edge = 1.dp

    /** A round or rounded surface's specular line fades out this far down it (or halfway down, if that is less). */
    val SpecularReach = 24.dp

    /**
     * The scroll-edge effect: where scrolling content meets a bar, its last 24 dp fade into the glass,
     * and inside the bar's edge the frost thickens over as much again.
     */
    val EdgeBand = 24.dp

    /** Beside the rail the band is the content's own 16 dp margin, so no row's text is ever washed. */
    val RailBand = 16.dp

    /** At the bar's edge the content is under the surface at this opacity (a sheet's), and clear 24 dp away. */
    const val EdgeFadeAlpha = SheetAlpha

    /**
     * Inside a blurring bar's content-facing edge the surface is laid over the container at up to this
     * opacity, so the frost reaches a sheet's at the edge: 0.72 + 0.28 × 0.5 = 0.86.
     */
    const val BandAlpha = 1f - (1f - SheetAlpha) / (1f - ContainerAlpha)

    /** Increase contrast: every hairline in the content colour at this opacity instead of the hairline token. */
    const val ContrastHairlineAlpha = 0.4f
}

// The specular line: a faint highlight along the glass's edge, white at 10 % on the camera body and
// 70 % on the paper. The only colour literals the glass adds.
val GlassEdgeDark  = Color(0x1AFFFFFF)
val GlassEdgeLight = Color(0xB3FFFFFF)

/** The specular line's colour for the appearance; PianoTheme provides it. */
val LocalGlassEdge = staticCompositionLocalOf { GlassEdgeDark }

/**
 * What the person has asked of the glass: [reducedTransparency] (solid surfaces with the hairlines)
 * and [increasedContrast] (hairlines in the content colour at [GlassTokens.ContrastHairlineAlpha]).
 */
@Immutable
data class GlassAccessibility(val reducedTransparency: Boolean, val increasedContrast: Boolean)

/**
 * The glass's accessibility states, followed as they change. Android's "High contrast text"
 * (`Settings.Secure` `high_text_contrast_enabled`, the platform's ACCESSIBILITY_HIGH_TEXT_CONTRAST)
 * is the nearest it comes to iOS's Reduce Transparency and Increase Contrast, and stands for both:
 * the glass is today's solid surface, and its hairlines are stronger. In debug builds only, the
 * property `debug.stevenpiano.noblur` (`adb shell setprop debug.stevenpiano.noblur 1`, read once per
 * process) reduces transparency alone. Below API 31 there is no blur at all and the glass is always solid.
 */
@Composable
fun rememberGlassAccessibility(): GlassAccessibility {
    val resolver = LocalContext.current.contentResolver
    var highContrastText by remember(resolver) { mutableStateOf(highContrastText(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                highContrastText = highContrastText(resolver)
            }
        }
        val watching = runCatching {
            resolver.registerContentObserver(Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST), false, observer)
        }.isSuccess
        onDispose { if (watching) resolver.unregisterContentObserver(observer) }
    }
    return GlassAccessibility(reducedTransparency = highContrastText || DebugNoBlur.on, increasedContrast = highContrastText)
}

/** Whether the glass draws as today's solid surface ([rememberGlassAccessibility]). */
@Composable
fun rememberReducedTransparency(): Boolean = rememberGlassAccessibility().reducedTransparency

/** Whether this device can blur what lies under the glass (RenderEffect, API 31 and up). */
val GlassCanBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"

private fun highContrastText(resolver: android.content.ContentResolver): Boolean =
    runCatching { Settings.Secure.getInt(resolver, HIGH_TEXT_CONTRAST, 0) == 1 }.getOrDefault(false)

/** `debug.stevenpiano.noblur`, in debug builds: read once (a getprop), the first time the glass asks. */
private object DebugNoBlur {
    private const val PROPERTY = "debug.stevenpiano.noblur"

    val on: Boolean by lazy {
        if (!BuildConfig.DEBUG) return@lazy false
        val value = runCatching {
            ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim().lowercase() }
        }.getOrDefault("")
        value.isNotEmpty() && value != "0" && value != "false" && value != "no"
    }
}
