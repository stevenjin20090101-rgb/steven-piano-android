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

// The glass of the floating controls (DESIGN.md › v1.5 — M16): the tab bar and the rail, the mini
// player, the transport on Now playing and in the now-playing panel, and a playlist's Play. Never
// on content (cards, rows, tiles, the roll, the score). Monochrome: the container is the surface
// colour itself, over a blur of whatever scrolls beneath, with no tint and no noise.

/** The material's measures. */
object GlassTokens {
    /** The container: the surface colour at this opacity over the blur. */
    const val ContainerAlpha = 0.72f

    /** The 72 dp play circle alone is clearer, a lens in the bar: its 32 dp glyph needs 3:1 (GlassTokensTest). */
    const val LensAlpha = 0.60f

    /**
     * A surface holding a lens is blurred once, under the lens's clearer container, and veiled with
     * the surface colour at this opacity everywhere but the lens: over any backdrop that composes to
     * exactly [ContainerAlpha] (0.30 + 0.70 × 0.60 = 0.72), without a second blur for the lens.
     */
    const val LensVeilAlpha = 1f - (1f - ContainerAlpha) / (1f - LensAlpha)

    /** How far the content beneath is blurred. */
    val Blur = 24.dp

    /** The edge (the hairline token) and the specular line along it, 1 dp each. */
    val Edge = 1.dp
}

// The specular line: a faint highlight along the glass's edge, white at 10 % on the camera body and
// 70 % on the paper. The only colour literals the glass adds.
val GlassEdgeDark  = Color(0x1AFFFFFF)
val GlassEdgeLight = Color(0xB3FFFFFF)

/** The specular line's colour for the appearance; PianoTheme provides it. */
val LocalGlassEdge = staticCompositionLocalOf { GlassEdgeDark }

/**
 * Whether the glass draws as today's solid surface: Android's "High contrast text" is on (the
 * nearest the platform comes to iOS's Reduce Transparency; `Settings.Secure`
 * `high_text_contrast_enabled`, followed as it changes), or, in debug builds only, the property
 * `debug.stevenpiano.noblur` is set (`adb shell setprop debug.stevenpiano.noblur 1`, read once per
 * process). Below API 31 there is no blur at all and the glass is always solid.
 */
@Composable
fun rememberReducedTransparency(): Boolean {
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
    return highContrastText || DebugNoBlur.on
}

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
