package io.github.hibyremote.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.hibyremote.remote.Platform
import io.github.hibyremote.remote.RemoteController

private enum class Screen { CONNECT, PLAYER, LIBRARY }

/** Platform hook for the system back action (Android back button); a no-op by default. */
typealias BackHandlerHook = @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit

@Composable
fun RemoteRoot(
    controller: RemoteController,
    platform: Platform,
    library: LibraryModel,
    extraSources: ConnectExtras? = null,
    backHandler: BackHandlerHook = { _, _ -> },
) {
    val state by controller.state.collectAsState()
    var screen by rememberSaveable {
        mutableStateOf(if (controller.lastTarget == null) Screen.CONNECT else Screen.PLAYER)
    }
    val snackbar = remember { SnackbarHostState() }

    // A text field that disappears with its screen cannot report losing focus.
    LaunchedEffect(screen) { TextInputFocus.active = false }

    LaunchedEffect(controller) {
        controller.messages.collect { snackbar.showSnackbar(it) }
    }

    when (screen) {
        Screen.CONNECT -> {
            backHandler(state.target != null) { screen = Screen.PLAYER }
            ConnectScreen(
                controller = controller,
                platform = platform,
                state = state,
                snackbar = snackbar,
                canGoBack = state.target != null,
                onBack = { screen = Screen.PLAYER },
                onConnect = { target ->
                    controller.connect(target)
                    library.reset()
                    screen = Screen.PLAYER
                },
                extraSources = extraSources,
            )
        }
        Screen.PLAYER -> PlayerScreen(
            controller = controller,
            state = state,
            snackbar = snackbar,
            onOpenDevices = { screen = Screen.CONNECT },
            onOpenLibrary = { screen = Screen.LIBRARY },
        )
        Screen.LIBRARY -> {
            backHandler(true) {
                if (!library.pop()) screen = Screen.PLAYER
            }
            LibraryScreen(
                model = library,
                state = state,
                snackbar = snackbar,
                onBack = { if (!library.pop()) screen = Screen.PLAYER },
                onClose = { screen = Screen.PLAYER },
            )
        }
    }
}
