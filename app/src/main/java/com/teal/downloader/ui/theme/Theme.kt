package com.teal.downloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark only, deliberately: Teal is a dark app, so there is no light scheme and
// no dynamic-colour branch to fall back to.
val TealColorScheme = darkColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    inversePrimary = TealInversePrimary,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,
    background = TealBackground,
    onBackground = TealOnBackground,
    surface = TealSurface,
    onSurface = TealOnSurface,
    surfaceVariant = TealSurfaceVariant,
    onSurfaceVariant = TealOnSurfaceVariant,
    surfaceTint = TealPrimary,
    inverseSurface = TealInverseSurface,
    inverseOnSurface = TealInverseOnSurface,
    outline = TealOutline,
    outlineVariant = TealOutlineVariant,
    surfaceBright = TealSurfaceBright,
    surfaceDim = TealSurfaceDim,
    surfaceContainer = TealSurfaceContainer,
    surfaceContainerHigh = TealSurfaceContainerHigh,
    surfaceContainerHighest = TealSurfaceContainerHighest,
    surfaceContainerLow = TealSurfaceContainerLow,
    surfaceContainerLowest = TealSurfaceContainerLowest,
    error = TealError,
    onError = TealOnError,
    errorContainer = TealErrorContainer,
    onErrorContainer = TealOnErrorContainer,
    scrim = TealScrim,
)

@Composable
fun TealTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TealColorScheme,
        typography = TealTypography,
        content = content,
    )
}
