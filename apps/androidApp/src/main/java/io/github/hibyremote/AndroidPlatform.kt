package io.github.hibyremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.hibyremote.remote.LogLevel
import io.github.hibyremote.remote.Platform
import io.github.hibyremote.remote.Settings
import io.github.hibyremote.remote.Target
import io.github.hibyremote.transport.BleTransport
import io.github.hibyremote.transport.RfcommTransport
import io.github.hibyremote.transport.Transport
import java.io.IOException

class AndroidPlatform(context: Context) : Platform {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("remote", Context.MODE_PRIVATE)

    override val settings = object : Settings {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
        override fun remove(key: String) = prefs.edit().remove(key).apply()
    }

    override fun log(level: LogLevel, tag: String, msg: String, error: Throwable?) {
        val priority = when (level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        if (error != null) Log.println(priority, tag, msg + "\n" + Log.getStackTraceString(error))
        else Log.println(priority, tag, msg)
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    override fun acquireMulticast(): AutoCloseable {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("hiby-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        return AutoCloseable { lock?.release() }
    }

    @SuppressLint("MissingPermission")
    override fun transportFor(target: Target): Transport = when (target) {
        is Target.Wifi -> throw IOException("Wi-Fi targets are handled by the shared code")
        is Target.Rfcomm, is Target.Ble -> {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("Bluetooth is not available")
            if (!adapter.isEnabled) throw IOException("Bluetooth is off")
            val address = if (target is Target.Rfcomm) target.address else (target as Target.Ble).address
            val device = adapter.getRemoteDevice(address)
            if (target is Target.Rfcomm) RfcommTransport(device, adapter) else BleTransport(appContext, device)
        }
    }
}
