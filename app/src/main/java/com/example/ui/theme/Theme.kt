package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun MovieHuntTheme(
    themeName: String = "classic-red",
    isDark: Boolean = true,
    content: @Composable () -> Unit
) {
    // Pick Primary and Secondary accents based on theme name
    val (primaryColor, secondaryColor) = when (themeName) {
        "ocean-blue" -> Pair(OceanBluePrimary, OceanBlueSecondary)
        "forest-green" -> Pair(ForestGreenPrimary, ForestGreenSecondary)
        "royal-purple" -> Pair(RoyalPurplePrimary, RoyalPurpleSecondary)
        "sunset-orange" -> Pair(SunsetOrangePrimary, SunsetOrangeSecondary)
        "cyber-pink" -> Pair(CyberPinkPrimary, CyberPinkSecondary)
        else -> Pair(RubyRedPrimary, RubyRedSecondary) // classic-red
    }

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = primaryColor,
            secondary = secondaryColor,
            background = CosmicSlateDarkBg,
            surface = CosmicSlateSurface,
            surfaceVariant = CosmicSlateSurfaceVariant,
            outline = GrayBorder,
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        // Bright/Light Cinematic theme fallback
        lightColorScheme(
            primary = primaryColor,
            secondary = secondaryColor,
            background = Color(0xFFF9F9FC),
            surface = Color.White,
            surfaceVariant = Color(0xFFEEEEF4),
            outline = Color(0xFFDDDDDF),
            onBackground = Color.Black,
            onSurface = Color.Black
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
