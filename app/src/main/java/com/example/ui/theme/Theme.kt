// File: app/src/main/java/com/example/ui/theme/Theme.kt
package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val YggGramCyberColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color(0xFF032217),
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = OnEmeraldContainer,
    secondary = CyberTealSecondary,
    onSecondary = Color(0xFF02232E),
    secondaryContainer = CyberTealContainer,
    onSecondaryContainer = OnCyberTealContainer,
    tertiary = AmberShieldTertiary,
    onTertiary = Color(0xFF281702),
    tertiaryContainer = AmberShieldContainer,
    onTertiaryContainer = OnAmberShieldContainer,
    error = CoralError,
    onError = Color(0xFF2D0608),
    errorContainer = CoralErrorContainer,
    onErrorContainer = OnCoralErrorContainer,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = GraphiteSurface,
    onSurface = TextPrimary,
    surfaceVariant = GraphiteSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = GraphiteSurface,
    surfaceContainer = GraphiteSurfaceElevated,
    surfaceContainerHigh = GraphiteSurfaceHigh,
    surfaceContainerHighest = Color(0xFF25303F),
    outline = GraphiteBorder,
    outlineVariant = Color(0xFF1B2430)
)

@Composable
fun YggGramTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = YggGramCyberColorScheme,
        typography = Typography,
        content = content
    )
}
