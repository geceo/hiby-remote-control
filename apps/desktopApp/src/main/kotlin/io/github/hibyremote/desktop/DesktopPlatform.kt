package io.github.hibyremote.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.hibyremote.remote.LogLevel
import io.github.hibyremote.remote.Platform
import io.github.hibyremote.remote.Settings
import io.github.hibyremote.remote.Target
import io.github.hibyremote.transport.Transport
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.prefs.Preferences

class DesktopPlatform : Platform {
    private val prefs: Preferences = Preferences.userRoot().node(PREFS_NODE)

    override val settings = object : Settings {
        override fun getString(key: String): String? = prefs.get(key, null)
        override fun putString(key: String, value: String) {
            prefs.put(key, value)
            flushQuietly()
        }

        override fun remove(key: String) {
            prefs.remove(key)
            flushQuietly()
        }
    }

    private fun flushQuietly() {
        try {
            prefs.flush()
        } catch (_: Exception) {
        }
    }

    override fun log(level: LogLevel, tag: String, msg: String, error: Throwable?) {
        val time = LocalTime.now().format(TIME)
        System.err.println("$time ${level.name.first()} $tag: $msg")
        error?.printStackTrace()
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? = try {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }

    /** Nothing to acquire on desktop operating systems. */
    override fun acquireMulticast(): AutoCloseable = AutoCloseable { }

    override fun transportFor(target: Target): Transport =
        throw IOException("${target.label} is not supported by the desktop app; use Wi-Fi")

    companion object {
        const val PREFS_NODE = "io/github/hibyremote"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
