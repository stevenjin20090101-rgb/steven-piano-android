package dev.stevenjin.stevenpiano.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The live colour is deliberately NOT in the Material scheme so nothing picks it up
// by accident. Only LiveDot reads it.
val LocalLive = staticCompositionLocalOf { LiveRedDark }

// Hairline and disabled-glyph tokens, also kept out of the scheme.
val LocalHairline = staticCompositionLocalOf { InkHairline }
val LocalDisabledGlyph = staticCompositionLocalOf { InkDisabledGlyph }
val LocalTertiary = staticCompositionLocalOf { SilverTertiary }

// Monochrome Material 3 mapping. `primary` is the content colour, so every system
// button renders as light-on-dark (or dark-on-paper) with no tint anywhere.
// `error` is ALSO monochrome, on purpose: red keeps its single meaning (live).
private val DarkScheme = darkColorScheme(
    primary = SilverPrimary,          onPrimary = InkSurface,
    primaryContainer = InkElevated,   onPrimaryContainer = SilverPrimary,
    secondary = SilverSecondary,      onSecondary = InkSurface,
    secondaryContainer = InkElevated, onSecondaryContainer = SilverPrimary,
    tertiary = SilverSecondary,       onTertiary = InkSurface,
    background = InkSurface,          onBackground = SilverPrimary,
    surface = InkSurface,             onSurface = SilverPrimary,
    surfaceVariant = InkElevated,     onSurfaceVariant = SilverSecondary,
    surfaceContainer = InkElevated,   surfaceContainerHigh = InkElevated,
    surfaceContainerHighest = InkElevated, surfaceContainerLow = InkSurface,
    outline = InkHairline,            outlineVariant = InkHairline,
    error = SilverPrimary,            onError = InkSurface,
    errorContainer = InkElevated,     onErrorContainer = SilverPrimary,
    scrim = Color(0xCC000000),
)

private val LightScheme = lightColorScheme(
    primary = CarbonPrimary,          onPrimary = PaperSurface,
    primaryContainer = PaperElevated, onPrimaryContainer = CarbonPrimary,
    secondary = CarbonSecondary,      onSecondary = PaperSurface,
    secondaryContainer = PaperElevated, onSecondaryContainer = CarbonPrimary,
    tertiary = CarbonSecondary,       onTertiary = PaperSurface,
    background = PaperSurface,        onBackground = CarbonPrimary,
    surface = PaperSurface,           onSurface = CarbonPrimary,
    surfaceVariant = PaperElevated,   onSurfaceVariant = CarbonSecondary,
    surfaceContainer = PaperElevated, surfaceContainerHigh = PaperElevated,
    surfaceContainerHighest = PaperElevated, surfaceContainerLow = PaperSurface,
    outline = PaperHairline,          outlineVariant = PaperHairline,
    error = CarbonPrimary,            onError = PaperSurface,
    errorContainer = PaperElevated,   onErrorContainer = CarbonPrimary,
    scrim = Color(0x99000000),
)

// Quiet corners. Controls 8 dp, cards 12 dp, the play control is a circle.
val PianoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// Follows the system appearance. There is no in-app switch (DESIGN.md › Colour).
@Composable
fun PianoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLive provides (if (darkTheme) LiveRedDark else LiveRedLight),
        LocalHairline provides (if (darkTheme) InkHairline else PaperHairline),
        LocalDisabledGlyph provides (if (darkTheme) InkDisabledGlyph else PaperDisabledGlyph),
        LocalTertiary provides (if (darkTheme) SilverTertiary else CarbonTertiary),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = PianoTypography,
            shapes = PianoShapes,
            content = content,
        )
    }
}
