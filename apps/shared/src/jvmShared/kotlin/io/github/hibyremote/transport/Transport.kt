package io.github.hibyremote.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A byte pipe to the player. Implementations are used from background threads only. */
interface Transport : Closeable {
    /** True for the low-bandwidth BLE link: covers are not requested on it. */
    val lowBandwidth: Boolean get() = false

    /** Opens the link. Blocking. */
    @Throws(IOException::class)
    fun open()

    /** Sends one link-layer frame. Blocking; callers serialize their writes. */
    @Throws(IOException::class)
    fun send(frame: ByteArray)

    /** Reads until the link closes, passing each received block to [sink]. Blocking. */
    @Throws(IOException::class)
    fun receive(sink: (ByteArray, Int) -> Unit)
}

/** Shared implementation for stream sockets (TCP, RFCOMM). */
abstract class StreamTransport : Transport {
    protected abstract val input: InputStream
    protected abstract val output: OutputStream

    override fun send(frame: ByteArray) {
        output.write(frame)
        output.flush()
    }

    override fun receive(sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(16384)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            if (n > 0) sink(buf, n)
        }
    }
}

/** WiFi: TCP to `host:4001`. */
class TcpTransport(private val host: String, private val port: Int) : StreamTransport() {
    private val socket = Socket()
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()

    override fun open() {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(host, port), 5000)
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
