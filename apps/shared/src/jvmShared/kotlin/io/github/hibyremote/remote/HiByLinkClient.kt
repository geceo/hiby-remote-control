package io.github.hibyremote.remote

import io.github.hibyremote.protocol.Actions
import io.github.hibyremote.protocol.ArgWriter
import io.github.hibyremote.protocol.FrameReader
import io.github.hibyremote.protocol.Frames
import io.github.hibyremote.protocol.Message
import io.github.hibyremote.protocol.asLong
import io.github.hibyremote.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** A method return: `code >= 0` means OK; [values] are the outputs after the code. */
class Reply(val code: Long, val values: List<Any?>) {
    val ok: Boolean get() = code >= 0
}

/**
 * One HiByLink session over a [Transport]: sends calls, matches returns by
 * message id and forwards signals to [onSignal] (called on the reader thread).
 */
class HiByLinkClient(
    private val transport: Transport,
    private val platform: Platform,
    private val onSignal: (action: Long, values: List<Any?>) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Reply>>()
    private val writeLock = Any()
    private var msgId = FIRST_ID
    private var streamSeq = FIRST_ID

    @Volatile
    var closed = false
        private set

    val lowBandwidth: Boolean get() = transport.lowBandwidth

    /** Opens the transport and starts the reader. Blocking. */
    fun start() {
        transport.open()
        thread(name = "hibylink-rx", isDaemon = true) { readLoop() }
    }

    private fun readLoop() {
        val reader = FrameReader { platform.w(TAG, it) }
        var error: Throwable? = null
        try {
            transport.receive { buf, n ->
                for (m in reader.feed(buf, 0, n)) dispatch(m)
            }
        } catch (e: Exception) {
            error = e
        }
        shutdown()
        onClosed(error)
    }

    private fun dispatch(m: Message) {
        val args = m.args()
        when (m.type) {
            Message.RETURN -> pending.remove(m.id)?.complete(
                Reply(args.firstOrNull().asLong(), args.drop(1)),
            )
            Message.SIGNAL -> if (args.isNotEmpty()) {
                try {
                    onSignal(args[0].asLong(), args.drop(1))
                } catch (e: Exception) {
                    platform.w(TAG, "signal handler failed", e)
                }
            }
        }
    }

    private fun shutdown() {
        closed = true
        transport.close()
        val err = IOException("link closed")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    /** Sends a call without waiting for the return. */
    suspend fun post(action: Long, args: ArgWriter.() -> Unit = {}) {
        send(action, args, null)
    }

    /** Sends a call and waits for its return. */
    suspend fun call(action: Long, timeoutMs: Long = 5000, args: ArgWriter.() -> Unit = {}): Reply {
        val deferred = CompletableDeferred<Reply>()
        val id = send(action, args, deferred)
        // A timeout is reported as an IOException, not as a cancellation, so that
        // callers' error handling sees it.
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw IOException("no reply from the player")
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun send(action: Long, args: ArgWriter.() -> Unit, reply: CompletableDeferred<Reply>?): Int =
        withContext(Dispatchers.IO) {
            if (closed) throw IOException("link closed")
            val w = ArgWriter().u(action).apply(args)
            synchronized(writeLock) {
                val id = msgId
                val seq = streamSeq
                msgId = if (msgId >= 0xFFFF) FIRST_ID else msgId + 1
                streamSeq++
                if (reply != null) pending[id] = reply
                val msg = Message(Message.CALL, id, 1, w.signature, w.body())
                try {
                    for (f in Frames.encode(msg, seq)) transport.send(f)
                } catch (e: IOException) {
                    pending.remove(id)
                    transport.close() // the reader notices and reports the loss
                    throw e
                }
                id
            }
        }

    /** Logs in as the app does: user "Guest", empty password. */
    suspend fun login() {
        val r = call(Actions.CONNECT) { s("Guest"); s(""); i(0) }
        if (!r.ok) throw IOException("the player refused the connection (error ${r.code})")
    }

    fun close() {
        if (closed) return
        thread(isDaemon = true) {
            // Best effort: tell the player, then drop the link.
            try {
                val w = ArgWriter().u(Actions.DISCONNECT)
                synchronized(writeLock) {
                    for (f in Frames.encode(Message(Message.CALL, msgId, 1, w.signature, w.body()), streamSeq)) {
                        transport.send(f)
                    }
                }
            } catch (_: Exception) {
            }
            shutdown()
        }
    }

    companion object {
        private const val TAG = "HiByLink"
        /** The native library starts its counters at 11. */
        private const val FIRST_ID = 11
    }
}
