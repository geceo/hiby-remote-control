package io.github.hibyremote.remote

import androidx.compose.ui.graphics.ImageBitmap
import io.github.hibyremote.transport.Transport

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Small persistent key/value store (SharedPreferences on Android, java.util.prefs on desktop). */
interface Settings {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** Everything the shared code needs from the host platform. */
interface Platform {
    val settings: Settings

    fun log(level: LogLevel, tag: String, msg: String, error: Throwable? = null)

    /** Decodes an encoded image (JPEG/PNG); null when the bytes are not an image. */
    fun decodeImage(bytes: ByteArray): ImageBitmap?

    /** Held while discovery listens; Android acquires a MulticastLock. */
    fun acquireMulticast(): AutoCloseable

    /**
     * Transport for a target. The shared code handles [Target.Wifi] itself and
     * calls this for the others (Bluetooth on Android, unsupported on desktop).
     */
    fun transportFor(target: Target): Transport
}

fun Platform.w(tag: String, msg: String, error: Throwable? = null) = log(LogLevel.WARN, tag, msg, error)
fun Platform.i(tag: String, msg: String, error: Throwable? = null) = log(LogLevel.INFO, tag, msg, error)

/** Monotonic clock in milliseconds. */
fun nowMs(): Long = System.nanoTime() / 1_000_000
