package io.github.ardaulas.earshot.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFFFB300)
private val Teal = Color(0xFF26A69A)

@Composable
fun EarshotTheme(content: @Composable () -> Unit) {
    val colors =
        if (isSystemInDarkTheme()) {
            darkColorScheme(primary = Amber, secondary = Teal)
        } else {
            lightColorScheme(primary = Color(0xFF8D5A00), secondary = Color(0xFF00796B))
        }
    MaterialTheme(colorScheme = colors, content = content)
}
