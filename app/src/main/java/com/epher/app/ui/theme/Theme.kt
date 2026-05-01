package com.epher.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ColorWhite = Color(0xFFFDFBF6)
private val ColorWhiteSoft = Color(0xFFF3EFE5)

private val DarkColors = darkColorScheme(
    primary = ChromePurple,
    onPrimary = DuneSand,
    secondary = ChromePurpleSoft,
    onSecondary = NightInk,
    background = NightInk,
    onBackground = DuneSand,
    surface = InkCard,
    onSurface = DuneSand,
    surfaceVariant = ChromePurpleDark,
    onSurfaceVariant = MistBlue,
)

private val LightColors = lightColorScheme(
    primary = ChromePurple,
    onPrimary = DuneSand,
    secondary = ChromePurpleSoft,
    onSecondary = DuneSand,
    background = DuneSand,
    onBackground = NightInk,
    surface = ColorWhite,
    onSurface = NightInk,
    surfaceVariant = ColorWhiteSoft,
    onSurfaceVariant = ChromePurpleDark,
)

@Composable
@Suppress("DEPRECATION")
fun EpherTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = ChromePurple.toArgb()
            window.navigationBarColor = NightInk.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = EpherTypography,
        content = content,
    )
}
