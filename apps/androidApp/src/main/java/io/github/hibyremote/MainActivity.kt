package io.github.hibyremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import io.github.hibyremote.ui.RemoteRoot
import io.github.hibyremote.ui.bluetoothSources
import io.github.hibyremote.ui.RemoteTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as RemoteApp
    private val controller get() = app.controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Material You colors (dynamic color is always available on Android 13+).
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            RemoteTheme(scheme) {
                RemoteRoot(
                    controller = controller,
                    platform = app.platform,
                    library = app.library,
                    extraSources = { onConnect, snackbar -> bluetoothSources(onConnect, snackbar) },
                    backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
                )
            }
        }
    }

    /** The phone's volume keys drive the player's volume while connected. */
    private fun volumeStep(keyCode: Int): Int {
        val state = controller.state.value
        if (!state.connected || state.volume == null) return 0
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> 0
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = volumeStep(keyCode)
        if (step == 0) return super.onKeyDown(keyCode, event)
        controller.volumeStep(step)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        volumeStep(keyCode) != 0 || super.onKeyUp(keyCode, event)
}
