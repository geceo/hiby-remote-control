package io.github.hibyremote.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.hibyremote.remote.RemoteController
import io.github.hibyremote.ui.LibraryModel
import io.github.hibyremote.ui.RemoteRoot
import io.github.hibyremote.ui.RemoteTheme
import io.github.hibyremote.ui.TextInputFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

private const val KEY_WINDOW = "window"
private val DEFAULT_SIZE = DpSize(420.dp, 820.dp)
private val MIN_SIZE = Dimension(360, 560)

/** Saved window geometry: "x,y,width,height" in dp. */
private data class Geometry(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun encode() = "$x,$y,$w,$h"

    companion object {
        fun decode(s: String?): Geometry? {
            val p = s?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
            return if (p.size == 4 && p[2] >= MIN_SIZE.width && p[3] >= MIN_SIZE.height) Geometry(p[0], p[1], p[2], p[3]) else null
        }
    }
}

/** True when the top-left corner of the window lies on a connected screen (a monitor may have been unplugged). */
private fun isOnScreen(g: Geometry): Boolean = try {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any {
        val b: Rectangle = it.defaultConfiguration.bounds
        b.contains(g.x + 20, g.y + 20)
    }
} catch (_: Exception) {
    false
}

@OptIn(FlowPreview::class)
fun main() = application {
    val platform = remember { DesktopPlatform() }
    val controller = remember {
        RemoteController(platform, CoroutineScope(SupervisorJob() + Dispatchers.Default)).also {
            // A desktop window is always "in the foreground" for polling and reconnection.
            it.foreground = true
        }
    }
    val library = remember { LibraryModel(controller) }

    val saved = remember { Geometry.decode(platform.settings.getString(KEY_WINDOW)) }
    val windowState = rememberWindowState(
        size = saved?.let { DpSize(it.w.dp, it.h.dp) } ?: DEFAULT_SIZE,
        position = if (saved != null && isOnScreen(saved)) WindowPosition(saved.x.dp, saved.y.dp)
        else WindowPosition.PlatformDefault,
    )
    LaunchedEffect(windowState) {
        snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
            .debounce(500)
            .collect { (size, pos, _) ->
                val x = if (pos.isSpecified) pos.x.value.toInt() else 0
                val y = if (pos.isSpecified) pos.y.value.toInt() else 0
                if (pos.isSpecified && size.width.value >= MIN_SIZE.width) {
                    platform.settings.putString(
                        KEY_WINDOW,
                        Geometry(x, y, size.width.value.toInt(), size.height.value.toInt()).encode(),
                    )
                }
            }
    }

    val icon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }

    Window(
        onCloseRequest = {
            controller.disconnect()
            exitApplication()
        },
        title = "HiBy Remote",
        icon = icon,
        state = windowState,
        onPreviewKeyEvent = { handleShortcut(it, controller) },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = MIN_SIZE }
        val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
        RemoteTheme(scheme) {
            RemoteRoot(controller = controller, platform = platform, library = library)
        }
    }
}

/**
 * Space: play/pause. Ctrl+Right/Left: next/previous. Ctrl+Up/Down: volume.
 * Only while connected and when no text field has the focus.
 */
private val held = mutableSetOf<Key>()

private fun handleShortcut(e: KeyEvent, controller: RemoteController): Boolean {
    val connected = controller.state.value.connected
    if (!connected || TextInputFocus.active) return false
    val ctrl = e.isCtrlPressed
    val handled = when {
        e.key == Key.Spacebar && !ctrl -> true
        ctrl && e.key in setOf(Key.DirectionRight, Key.DirectionLeft, Key.DirectionUp, Key.DirectionDown) -> true
        else -> false
    }
    if (!handled) return false
    // Consume key-up too so that a focused button does not also react to Space.
    if (e.type != KeyEventType.KeyDown) {
        held.remove(e.key)
        return true
    }
    // Holding Space or Ctrl+Left/Right must not repeat the action; volume does repeat.
    val repeats = e.key == Key.DirectionUp || e.key == Key.DirectionDown
    if (!repeats && !held.add(e.key)) return true
    when (e.key) {
        Key.Spacebar -> controller.togglePlay()
        Key.DirectionRight -> controller.next()
        Key.DirectionLeft -> controller.previous()
        Key.DirectionUp -> controller.volumeStep(1)
        Key.DirectionDown -> controller.volumeStep(-1)
    }
    return true
}
