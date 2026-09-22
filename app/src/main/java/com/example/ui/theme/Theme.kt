package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DialerPrimaryDark,
    onPrimary = DialerOnPrimaryDark,
    primaryContainer = DialerPrimaryContainerDark,
    onPrimaryContainer = DialerOnPrimaryContainerDark,
    secondary = DialerSecondaryDark,
    onSecondary = DialerOnSecondaryDark,
    secondaryContainer = DialerSecondaryContainerDark,
    onSecondaryContainer = DialerOnSecondaryContainerDark,
    background = DialerBackgroundDark,
    onBackground = DialerOnBackgroundDark,
    surface = DialerSurfaceDark,
    onSurface = DialerOnSurfaceDark,
    surfaceVariant = DialerSurfaceVariantDark,
    onSurfaceVariant = DialerOnSurfaceVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = DialerPrimaryLight,
    onPrimary = DialerOnPrimaryLight,
    primaryContainer = DialerPrimaryContainerLight,
    onPrimaryContainer = DialerOnPrimaryContainerLight,
    secondary = DialerSecondaryLight,
    onSecondary = DialerOnSecondaryLight,
    secondaryContainer = DialerSecondaryContainerLight,
    onSecondaryContainer = DialerOnSecondaryContainerLight,
    background = DialerBackgroundLight,
    onBackground = DialerOnBackgroundLight,
    surface = DialerSurfaceLight,
    onSurface = DialerOnSurfaceLight,
    surfaceVariant = DialerSurfaceVariantLight,
    onSurfaceVariant = DialerOnSurfaceVariantLight
)

@Composable
fun SoftphoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
