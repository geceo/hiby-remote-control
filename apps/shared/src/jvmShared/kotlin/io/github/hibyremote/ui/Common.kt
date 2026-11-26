package io.github.hibyremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * True while a text field has the keyboard focus. The desktop app consults it
 * so that its global shortcuts (Space, Ctrl+arrows) do not steal typing.
 */
object TextInputFocus {
    @Volatile
    var active: Boolean = false
}

fun Modifier.trackTextFocus(): Modifier = onFocusChanged { TextInputFocus.active = it.isFocused }

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/** Formats milliseconds as m:ss or h:mm:ss. */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatRate(hz: Long): String {
    if (hz <= 0) return ""
    val k = hz / 1000.0
    return if (hz % 1000 == 0L) "${hz / 1000} kHz" else "%.1f kHz".format(k)
}

fun fileExtension(path: String): String =
    path.substringAfterLast('/').substringAfterLast('.', "").uppercase()
