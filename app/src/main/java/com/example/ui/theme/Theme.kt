// File: app/src/main/java/com/example/ui/theme/Theme.kt
package com.example.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * The themes the user can pick in the privacy screen.
 *
 * `AppTheme.CyberEmerald` is what every install had before this enum existed, so it remains
 * the default and an unparseable stored key falls back to it rather than changing the app
 * under somebody who never chose anything. The app never follows the system light/dark mode:
 * the three original palettes stay dark and the two new ones are light.
 */
enum class AppTheme(val key: String, val labelRu: String) {
    CyberEmerald("cyber_emerald", "Кибер-изумруд"),
    NightIndigo("night_indigo", "Полночное индиго"),
    TerraRose("terra_rose", "Терракота"),
    SnowLight("snow_light", "Снежная"),
    GraphiteLight("graphite_light", "Графит");

    companion object {
        fun fromKey(key: String?): AppTheme = values().firstOrNull { it.key == key } ?: CyberEmerald
    }
}

/**
 * The picked theme, kept outside the composable so the whole app recomposes the moment the
 * user chooses another one and reads the same value after a restart.
 */
object YggThemePref {
    val state = mutableStateOf(AppTheme.CyberEmerald)

    private const val PREFS = "ygggram_ui_prefs"
    private const val KEY = "ui_theme"

    fun load(prefs: SharedPreferences) {
        state.value = AppTheme.fromKey(prefs.getString(KEY, null))
    }

    fun save(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, theme.key)
            .apply()
        state.value = theme
    }
}

private val CyberEmeraldScheme = darkColorScheme(
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

private val NightIndigoScheme = darkColorScheme(
    primary = Color(0xFF6D8BFF),
    onPrimary = Color(0xFF0A1030),
    primaryContainer = Color(0xFF1B2450),
    onPrimaryContainer = Color(0xFFD6DEFF),
    secondary = Color(0xFF7FD4E8),
    onSecondary = Color(0xFF04222A),
    secondaryContainer = Color(0xFF153A46),
    onSecondaryContainer = Color(0xFFCFEFF8),
    tertiary = Color(0xFFC48CF2),
    onTertiary = Color(0xFF24093A),
    tertiaryContainer = Color(0xFF3A1A5C),
    onTertiaryContainer = Color(0xFFEBD6FF),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF2D0608),
    errorContainer = Color(0xFF4A1518),
    onErrorContainer = Color(0xFFFFD7D8),
    background = Color(0xFF06070F),
    onBackground = Color(0xFFE6E9F5),
    surface = Color(0xFF0D1020),
    onSurface = Color(0xFFE6E9F5),
    surfaceVariant = Color(0xFF161A2E),
    onSurfaceVariant = Color(0xFFAAB2CD),
    surfaceContainerLowest = Color(0xFF06070F),
    surfaceContainerLow = Color(0xFF0D1020),
    surfaceContainer = Color(0xFF161A2E),
    surfaceContainerHigh = Color(0xFF1E2340),
    surfaceContainerHighest = Color(0xFF272D4C),
    outline = Color(0xFF3A4266),
    outlineVariant = Color(0xFF232842)
)

private val TerraRoseScheme = darkColorScheme(
    primary = Color(0xFFE07856),
    onPrimary = Color(0xFF2A0E05),
    primaryContainer = Color(0xFF5A2415),
    onPrimaryContainer = Color(0xFFFFDCCF),
    secondary = Color(0xFFD8A65B),
    onSecondary = Color(0xFF2A1B04),
    secondaryContainer = Color(0xFF574013),
    onSecondaryContainer = Color(0xFFF7E4C2),
    tertiary = Color(0xFF9CC4A2),
    onTertiary = Color(0xFF0C2211),
    tertiaryContainer = Color(0xFF264A30),
    onTertiaryContainer = Color(0xFFD6F0DA),
    error = Color(0xFFFF7A6E),
    onError = Color(0xFF2D0608),
    errorContainer = Color(0xFF5A1A16),
    onErrorContainer = Color(0xFFFFD7D8),
    background = Color(0xFF120C0A),
    onBackground = Color(0xFFF3E4DC),
    surface = Color(0xFF1C1411),
    onSurface = Color(0xFFF3E4DC),
    surfaceVariant = Color(0xFF291D18),
    onSurfaceVariant = Color(0xFFC9B0A4),
    surfaceContainerLowest = Color(0xFF120C0A),
    surfaceContainerLow = Color(0xFF1C1411),
    surfaceContainer = Color(0xFF291D18),
    surfaceContainerHigh = Color(0xFF35251E),
    surfaceContainerHighest = Color(0xFF432E26),
    outline = Color(0xFF6A4C40),
    outlineVariant = Color(0xFF3B2A23)
)

private val SnowLightScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB3E5DF),
    onPrimaryContainer = Color(0xFF002823),
    secondary = Color(0xFF0369A1),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFDFEF),
    onSecondaryContainer = Color(0xFF001F2E),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE0BE),
    onTertiaryContainer = Color(0xFF2A1300),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF11161D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11161D),
    surfaceVariant = Color(0xFFE7ECF3),
    onSurfaceVariant = Color(0xFF4C5663),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F5F9),
    surfaceContainer = Color(0xFFEAEFF5),
    surfaceContainerHigh = Color(0xFFE1E8F0),
    surfaceContainerHighest = Color(0xFFD7E0EA),
    outline = Color(0xFFB7C1CE),
    outlineVariant = Color(0xFFD5DCE5)
)

private val GraphiteLightScheme = lightColorScheme(
    primary = Color(0xFF0E9F6E),
    onPrimary = Color(0xFF04241A),
    primaryContainer = Color(0xFFB9F0D8),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF0EA5E9),
    onSecondary = Color(0xFF05222E),
    secondaryContainer = Color(0xFFC5E9FA),
    onSecondaryContainer = Color(0xFF001E2C),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5D8FF),
    onTertiaryContainer = Color(0xFF1C0840),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFEFF1F4),
    onBackground = Color(0xFF141A22),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF141A22),
    surfaceVariant = Color(0xFFDFE4EA),
    onSurfaceVariant = Color(0xFF444B56),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7FA),
    surfaceContainer = Color(0xFFEFF1F4),
    surfaceContainerHigh = Color(0xFFE8EBF0),
    surfaceContainerHighest = Color(0xFFDFE3EA),
    outline = Color(0xFFB9C2CE),
    outlineVariant = Color(0xFFD3D9E2)
)

private fun schemeFor(theme: AppTheme) = when (theme) {
    AppTheme.CyberEmerald -> CyberEmeraldScheme
    AppTheme.NightIndigo -> NightIndigoScheme
    AppTheme.TerraRose -> TerraRoseScheme
    AppTheme.SnowLight -> SnowLightScheme
    AppTheme.GraphiteLight -> GraphiteLightScheme
}

@Composable
fun YggGramTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = schemeFor(YggThemePref.state.value),
        typography = Typography,
        content = content
    )
}
