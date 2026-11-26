package io.github.hibyremote.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.hibyremote.protocol.Beacon
import io.github.hibyremote.protocol.Beacons
import io.github.hibyremote.remote.LinkState
import io.github.hibyremote.remote.PlayerState
import io.github.hibyremote.remote.Platform
import io.github.hibyremote.remote.RemoteController
import io.github.hibyremote.remote.Target
import io.github.hibyremote.remote.WifiDiscovery

/**
 * Extra player sources (the Bluetooth section on Android). A composable that
 * may hold state and returns the list content to append to the connect screen.
 */
typealias ConnectExtras = @Composable (onConnect: (Target) -> Unit, snackbar: SnackbarHostState) -> (LazyListScope.() -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    controller: RemoteController,
    platform: Platform,
    state: PlayerState,
    snackbar: SnackbarHostState,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onConnect: (Target) -> Unit,
    extraSources: ConnectExtras? = null,
) {
    val beacons = remember { mutableStateMapOf<String, Beacon>() }
    LaunchedEffect(Unit) {
        WifiDiscovery(platform).beacons().collect { beacons["${it.host}:${it.port}"] = it }
    }

    val extras = extraSources?.invoke(onConnect, snackbar)

    var manual by rememberSaveable { mutableStateOf((state.target as? Target.Wifi)?.host ?: "") }
    fun connectManual() {
        val text = manual.trim()
        if (text.isEmpty()) return
        val host = text.substringBefore(':')
        val port = text.substringAfter(':', "").toIntOrNull() ?: Beacons.DEFAULT_PORT
        onConnect(Target.Wifi(host, port, host))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose a player") },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            val current = state.target
            if (current != null) {
                item {
                    ListItem(
                        headlineContent = { Text(current.name) },
                        supportingContent = {
                            Text(
                                when (state.link) {
                                    LinkState.CONNECTED -> "Connected · ${current.label}"
                                    LinkState.CONNECTING -> "Connecting… · ${current.label}"
                                    LinkState.IDLE -> state.error ?: "Not connected · ${current.label}"
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.Speaker, null) },
                        trailingContent = {
                            Row {
                                if (state.link == LinkState.IDLE) {
                                    TextButton(onClick = { onConnect(current) }) { Text("Connect") }
                                } else {
                                    TextButton(onClick = { controller.disconnect() }) { Text("Disconnect") }
                                }
                                TextButton(onClick = { controller.forget() }) { Text("Forget") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }

            item { SectionHeader("Wi-Fi") }
            if (beacons.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Looking for players on this network. Enable HiByLink on the player.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            items(beacons.values.sortedBy { it.name.lowercase() }, key = { "w:${it.host}:${it.port}" }) { b ->
                ListItem(
                    modifier = Modifier.clickableRow { onConnect(Target.Wifi(b.host, b.port, b.name)) },
                    headlineContent = { Text(b.name) },
                    supportingContent = { Text(if (b.port == Beacons.DEFAULT_PORT) b.host else "${b.host}:${b.port}") },
                    leadingContent = { Icon(Icons.Rounded.Wifi, null) },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        modifier = Modifier.weight(1f).trackTextFocus(),
                        singleLine = true,
                        label = { Text("IP address[:port]") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { connectManual() }),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { connectManual() }, enabled = manual.isNotBlank()) { Text("Connect") }
                }
            }

            extras?.invoke(this)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
