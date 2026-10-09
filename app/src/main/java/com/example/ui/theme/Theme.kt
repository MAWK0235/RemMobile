package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RemminaDarkColorScheme = darkColorScheme(
    primary = RemminaCyan,
    onPrimary = Color(0xFF00262E),
    primaryContainer = RemminaCyanDim,
    onPrimaryContainer = Color.White,
    secondary = RemminaEmerald,
    onSecondary = Color(0xFF00291B),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = RemminaPurple,
    onTertiary = Color.White,
    background = RemminaNavyBg,
    onBackground = TextPrimaryDark,
    surface = RemminaSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = RemminaSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark,
    error = RemminaCoral,
    onError = Color.White
)

private val RemminaLightColorScheme = lightColorScheme(
    primary = RemminaCyanLightMode,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = RemminaEmeraldLightMode,
    onSecondary = Color.White,
    tertiary = RemminaPurple,
    background = RemminaLightBg,
    onBackground = Color(0xFF0F172A),
    surface = RemminaLightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = RemminaLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to dark workstation aesthetic like Remmina Desktop
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) RemminaDarkColorScheme else RemminaLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
