package info.cafferata.bvktracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Bvk {
    val red = Color(0xFFFF0000)
    val blue = Color(0xFF0093E0)
}

@Composable
fun BvkTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(primary = Bvk.blue, secondary = Bvk.red)
    MaterialTheme(colorScheme = scheme, content = content)
}
