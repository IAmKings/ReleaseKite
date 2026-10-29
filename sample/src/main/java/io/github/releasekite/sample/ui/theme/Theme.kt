package io.github.releasekite.sample.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 示例应用主题：纯 Compose Material3 配色，不引用任何自定义资源，
 * 便于直接复制到任意工程（示例只演示 ReleaseKite，不演示主题体系）。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF00695C),
    onSecondary = Color.White,
    tertiary = Color(0xFF8E24AA),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE2E2EC),
    background = Color(0xFFFDFBFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003730),
    tertiary = Color(0xFFE1BEE7),
    surface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFF44464F),
    background = Color(0xFF1A1B1F),
)

@Composable
fun ReleaseKiteSampleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
