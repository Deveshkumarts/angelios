package org.sih.itantra.presentation.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LabColorScheme = lightColorScheme(
    primary = LabInk,
    onPrimary = LabPaper,
    secondary = LabOrange,
    onSecondary = LabInk,
    tertiary = LabGreen,
    background = LabPaper,
    onBackground = LabInk,
    surface = LabCard,
    onSurface = LabInk,
    surfaceVariant = LabPaperDeep,
    onSurfaceVariant = LabInkMuted,
    outline = LabInk,
    outlineVariant = LabHairline,
    error = LabOrange,
    onError = LabInk
)

@Composable
fun ITANTRATheme(content: @Composable () -> Unit) {
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = LabPaper.toArgb()
                window.navigationBarColor = LabCard.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = LabColorScheme,
        typography = Typography,
        content = content
    )
}
