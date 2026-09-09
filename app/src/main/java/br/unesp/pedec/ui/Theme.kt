package br.unesp.pedec.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Phosphor = Color(0xFF7CFF6B)
val PhosphorDim = Color(0xFF3E8A38)
val Amber = Color(0xFFFFB000)
val Danger = Color(0xFFFF5A4E)
val Panel = Color(0xFF0A120A)
/** Borda dos paineis. Estava repetida como literal em tres telas. */
val PanelBorda = Color(0xFF1F401F)
val Ink = Color(0xFF000000)

@Composable
fun PedecTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Phosphor,
            onPrimary = Ink,
            secondary = Amber,
            background = Ink,
            onBackground = Phosphor,
            surface = Panel,
            onSurface = Phosphor,
            error = Danger
        ),
        content = content
    )
}
