package io.github.hibyremote.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Theme wrapper: each app supplies its own color scheme (Material You on Android, static on desktop). */
@Composable
fun RemoteTheme(colorScheme: ColorScheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, content = content)
}
