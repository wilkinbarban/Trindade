package com.trindade.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand palette drawn from Variant F visual identity ("cinta dinâmica"):
// - Primary: Brand red #B42025 (matching launcher background, site theme-color, and logomarca)
// - Secondary: Warm gold #855400 (derived from ribbon/wheat dynamic accents)
// - Warm neutrals: Off-white background/surface #FFF8F6 with warm tint #F4DDDA surfaceVariant
// - Contrast: onPrimary #FFFFFF provides 6.6:1 WCAG AA contrast over primary #B42025
val BrandRed = Color(0xFFB42025)
val BrandOnPrimary = Color(0xFFFFFFFF)
val BrandPrimaryContainer = Color(0xFFFFDAD6)
val BrandOnPrimaryContainer = Color(0xFF410005)

val BrandGold = Color(0xFF855400)
val BrandOnSecondary = Color(0xFFFFFFFF)
val BrandSecondaryContainer = Color(0xFFFFDDB3)
val BrandOnSecondaryContainer = Color(0xFF2B1700)

val BrandTertiary = Color(0xFF775653)
val BrandOnTertiary = Color(0xFFFFFFFF)
val BrandTertiaryContainer = Color(0xFFFFDAD6)
val BrandOnTertiaryContainer = Color(0xFF2D1513)

val BrandBackground = Color(0xFFFFF8F6)
val BrandOnBackground = Color(0xFF221919)
val BrandSurface = Color(0xFFFFF8F6)
val BrandOnSurface = Color(0xFF221919)
val BrandSurfaceVariant = Color(0xFFF4DDDA)
val BrandOnSurfaceVariant = Color(0xFF534341)

val BrandOutline = Color(0xFF857371)
val BrandOutlineVariant = Color(0xFFD8C2BF)

val BrandError = Color(0xFFBA1A1A)
val BrandOnError = Color(0xFFFFFFFF)
val BrandErrorContainer = Color(0xFFFFDAD6)
val BrandOnErrorContainer = Color(0xFF410002)

// The help action's amber. It is not a role of the scheme -- M3 has no "aside" colour -- and it stays
// outside `primary`/`secondary` because help is not part of the flow it explains. It lives here, with the
// rest of the palette, and not as a literal inside a component, so the whole app keeps one rule: a hex
// colour exists in this file and nowhere else.
val HelpAmber = Color(0xFFD97706)

val BrandSurfaceContainer = Color(0xFFFBEBE8)
val BrandSurfaceContainerHigh = Color(0xFFF5E5E2)
val BrandSurfaceContainerHighest = Color(0xFFEFE0DC)

val TrindadeLightColorScheme: ColorScheme = lightColorScheme(
    primary = BrandRed,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandGold,
    onSecondary = BrandOnSecondary,
    secondaryContainer = BrandSecondaryContainer,
    onSecondaryContainer = BrandOnSecondaryContainer,
    tertiary = BrandTertiary,
    onTertiary = BrandOnTertiary,
    tertiaryContainer = BrandTertiaryContainer,
    onTertiaryContainer = BrandOnTertiaryContainer,
    error = BrandError,
    onError = BrandOnError,
    errorContainer = BrandErrorContainer,
    onErrorContainer = BrandOnErrorContainer,
    background = BrandBackground,
    onBackground = BrandOnBackground,
    surface = BrandSurface,
    onSurface = BrandOnSurface,
    surfaceVariant = BrandSurfaceVariant,
    onSurfaceVariant = BrandOnSurfaceVariant,
    outline = BrandOutline,
    outlineVariant = BrandOutlineVariant,
    surfaceContainer = BrandSurfaceContainer,
    surfaceContainerHigh = BrandSurfaceContainerHigh,
    surfaceContainerHighest = BrandSurfaceContainerHighest,
)

/**
 * The corner radius every card in the app draws.
 *
 * `shapes.medium` is already 12dp in the Material 3 baseline, and `Card` takes its shape from there, so this
 * block is not needed to *make* the cards 12dp today -- it is needed to *say* it. Without it the radius is a
 * default of a library, and the day someone passes a custom `Shapes()` or Compose revises a baseline the app
 * silently changes its corners. Everything else keeps the baseline.
 */
private val TrindadeShapes = Shapes(
    medium = RoundedCornerShape(12.dp),
)

@Composable
fun TrindadeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrindadeLightColorScheme,
        shapes = TrindadeShapes,
        content = content,
    )
}
