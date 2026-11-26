package io.github.hibyremote.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object HiByBluetooth {
    /** RFCOMM service UUID announced by the HiByLink server. */
    val RFCOMM_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val BLE_SERVICE: UUID = UUID.fromString("0000c501-0000-1000-8000-00805f9b34fb")
    val BLE_CHARACTERISTIC: UUID = UUID.fromString("00001001-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * Bluetooth Classic: RFCOMM on the HiByLink service UUID. The app connects with
 * an insecure socket; the player listens with a secure server socket
 * (`listenUsingRfcommWithServiceRecord`), so a secure socket is tried next, then
 * channel 1 as the app does. Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class RfcommTransport(private val device: BluetoothDevice, private val adapter: BluetoothAdapter) : StreamTransport() {
    @Volatile
    private var socket: BluetoothSocket? = null
    override val input: InputStream get() = socket?.inputStream ?: throw IOException("not connected")
    override val output: OutputStream get() = socket?.outputStream ?: throw IOException("not connected")

    override fun open() {
        adapter.cancelDiscovery()
        val attempts = listOf<() -> BluetoothSocket?>(
            { device.createInsecureRfcommSocketToServiceRecord(HiByBluetooth.RFCOMM_UUID) },
            { device.createRfcommSocketToServiceRecord(HiByBluetooth.RFCOMM_UUID) },
            { channelSocket(1) },
        )
        var lastError: IOException? = null
        for (make in attempts) {
            val s = make() ?: continue
            socket = s
            try {
                s.connect()
                return
            } catch (e: IOException) {
                closeQuietly(s)
                lastError = e
            }
        }
        throw IOException("RFCOMM connection failed: ${lastError?.message}", lastError)
    }

    /** The app's fallback: hidden API `createRfcommSocket(channel)`, which may be unavailable. */
    private fun channelSocket(channel: Int): BluetoothSocket? = try {
        device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, channel) as BluetoothSocket
    } catch (_: Exception) {
        null
    }

    private fun closeQuietly(s: BluetoothSocket) {
        try {
            s.close()
        } catch (_: IOException) {
        }
    }

    override fun close() {
        socket?.let { closeQuietly(it) }
    }
}

/**
 * Bluetooth Low Energy: one characteristic 0x1001 (write + indicate) on
 * service 0xC501. Frames are cut into pieces of `mtu - 2` bytes in both
 * directions, each prefixed by `[seq][len]` where seq is 0xFF on the last piece
 * (PROTOCOL.md section 2.2). The player sends indications and waits about
 * 400 ms after each one, so this link is very slow (~45 bytes/s downstream).
 * Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context, private val device: BluetoothDevice) : Transport {
    override val lowBandwidth: Boolean get() = true

    private val events = LinkedBlockingQueue<Any>()
    private val received = LinkedBlockingQueue<ByteArray>()
    private val writeDone = LinkedBlockingQueue<Int>()
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var closed = false

    private object Connected
    private object Disconnected
    private object ServicesDiscovered
    private object DescriptorWritten
    private val EOF = ByteArray(0)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                events.offer(Connected)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                events.offer(Disconnected)
                received.offer(EOF)
                writeDone.offer(-1)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            events.offer(if (status == BluetoothGatt.GATT_SUCCESS) ServicesDiscovered else Disconnected)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            events.offer(DescriptorWritten)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.offer(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == HiByBluetooth.BLE_CHARACTERISTIC) received.offer(value.copyOf())
        }
    }

    private fun await(expected: Any, what: String, timeoutMs: Long = 15000) {
        val ev = events.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw IOException("BLE: timeout while $what")
        if (ev !== expected) throw IOException("BLE: link lost while $what")
    }

    override fun open() {
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("BLE: connectGatt failed")
        gatt = g
        try {
            await(Connected, "connecting")
            if (!g.discoverServices()) throw IOException("BLE: service discovery failed")
            await(ServicesDiscovered, "discovering services")
            val c = g.getService(HiByBluetooth.BLE_SERVICE)?.getCharacteristic(HiByBluetooth.BLE_CHARACTERISTIC)
                ?: throw IOException("BLE: HiByLink service not found on this device")
            characteristic = c
            g.setCharacteristicNotification(c, true)
            // The app never writes the CCCD and the player indicates regardless of
            // its value; writing it is only good manners, so failures are ignored.
            val cccd = c.getDescriptor(HiByBluetooth.CCCD)
            if (cccd != null) {
                val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS) {
                    val ev = events.poll(5, TimeUnit.SECONDS)
                    if (ev === Disconnected) throw IOException("BLE: link lost while enabling indications")
                }
            }
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override fun send(frame: ByteArray) {
        val g = gatt ?: throw IOException("BLE: not connected")
        val c = characteristic ?: throw IOException("BLE: not connected")
        val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        for (piece in BleFraming.chunk(frame)) {
            writeDone.clear()
            var attempts = 0
            while (true) {
                val rc = g.writeCharacteristic(c, piece, writeType)
                if (rc == BluetoothStatusCodes.SUCCESS) break
                if (closed || ++attempts > 50) throw IOException("BLE: write failed ($rc)")
                Thread.sleep(20) // the stack is busy with a previous operation
            }
            val status = writeDone.poll(5, TimeUnit.SECONDS) ?: throw IOException("BLE: write timeout")
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("BLE: write error ($status)")
        }
    }

    override fun receive(sink: (ByteArray, Int) -> Unit) {
        while (!closed) {
            val v = received.take()
            if (v === EOF) return
            // Pieces carry a [seq][len] prefix. A notification that starts with the
            // frame magic is taken as unchunked. The frame reader resynchronizes on
            // the magic, so pieces can be fed in order without reassembly.
            if (v.size >= 4 && v[0] == 0x55.toByte() && v[1] == 0xAA.toByte() && v[2] == 0xA5.toByte()) {
                sink(v, v.size)
            } else if (v.size > 2) {
                val len = minOf((v[1].toInt() and 0xFF), v.size)
                if (len > 2) sink(v.copyOfRange(2, len), len - 2)
            }
        }
    }

    override fun close() {
        closed = true
        received.offer(EOF)
        writeDone.offer(-1)
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (_: Exception) {
            }
        }
        gatt = null
    }
}
