package io.github.hibyremote.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import io.github.hibyremote.transport.HiByBluetooth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class BtDevice(val address: String, val name: String)

/** Lists paired devices and scans for BLE players. Callers must hold the Bluetooth permissions. */
@SuppressLint("MissingPermission")
class BluetoothDevices(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun bonded(): List<BtDevice> = try {
        adapter?.bondedDevices.orEmpty()
            .map { BtDevice(it.address, it.name ?: it.address) }
            .sortedBy { it.name.lowercase() }
    } catch (_: SecurityException) {
        emptyList()
    }

    /** Devices advertising the HiByLink BLE service. */
    fun scanBle(): Flow<BtDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = result.scanRecord?.deviceName ?: d.name ?: d.address
                trySend(BtDevice(d.address, name))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed ($errorCode)"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HiByBluetooth.BLE_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        awaitClose {
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
        }
    }
}
