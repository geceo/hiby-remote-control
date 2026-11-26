package io.github.hibyremote.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.hibyremote.remote.BluetoothDevices
import io.github.hibyremote.remote.BtDevice
import io.github.hibyremote.remote.Target
import kotlinx.coroutines.withTimeoutOrNull

private val BT_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)

/** The Bluetooth part of the connect screen, plugged in through `ConnectScreen`'s `extraSources` slot. */
@Composable
fun bluetoothSources(onConnect: (Target) -> Unit, snackbar: SnackbarHostState): LazyListScope.() -> Unit {
    val context = LocalContext.current
    val bt = remember { BluetoothDevices(context) }
    fun hasBtPermission() = BT_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var btGranted by remember { mutableStateOf(hasBtPermission()) }
    var btEnabled by remember { mutableStateOf(bt.enabled) }
    var bonded by remember { mutableStateOf(emptyList<BtDevice>()) }
    val bleFound = remember { mutableStateListOf<BtDevice>() }
    var scanning by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        btGranted = hasBtPermission()
        btEnabled = bt.enabled
        if (btGranted && btEnabled) bonded = bt.bonded()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        btGranted = hasBtPermission()
        if (btGranted) bonded = bt.bonded()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btEnabled = bt.enabled
        if (btGranted && btEnabled) bonded = bt.bonded()
    }
    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        try {
            withTimeoutOrNull(15_000) {
                bt.scanBle().collect { d -> if (bleFound.none { it.address == d.address }) bleFound.add(d) }
            }
        } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: "BLE scan failed")
        }
        scanning = false
    }

    return {
        item { SectionHeader("Bluetooth") }
        when {
            !bt.available -> item { Hint("This phone has no Bluetooth.") }
            !btGranted -> item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Bluetooth access is needed to list and connect to players.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    FilledTonalButton(onClick = { permissionLauncher.launch(BT_PERMISSIONS) }) {
                        Text("Allow Bluetooth access")
                    }
                }
            }
            !btEnabled -> item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Bluetooth is off.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    FilledTonalButton(onClick = {
                        enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }) { Text("Turn on Bluetooth") }
                }
            }
            else -> {
                item {
                    Text(
                        "Paired devices (Bluetooth Classic). Pair the player in the system settings first.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (bonded.isEmpty()) item { Hint("No paired device.") }
                items(bonded, key = { "c:${it.address}" }) { d ->
                    ListItem(
                        modifier = Modifier.clickableRow { onConnect(Target.Rfcomm(d.address, d.name)) },
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text(d.address) },
                        leadingContent = { Icon(Icons.Rounded.Bluetooth, null) },
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(onClick = { bleFound.clear(); scanning = true }, enabled = !scanning) {
                            Text(if (scanning) "Scanning…" else "Scan for Bluetooth LE players")
                        }
                        if (scanning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                items(bleFound, key = { "b:${it.address}" }) { d ->
                    ListItem(
                        modifier = Modifier.clickableRow { onConnect(Target.Ble(d.address, d.name)) },
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text("Bluetooth LE · ${d.address}") },
                        leadingContent = { Icon(Icons.Rounded.BluetoothSearching, null) },
                    )
                }
            }
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
