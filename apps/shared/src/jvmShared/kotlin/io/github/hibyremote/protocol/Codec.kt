package io.github.hibyremote.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CRC-16/MODBUS (reflected poly 0xA001, init 0xFFFF, no final xor). */
object Crc16 {
    fun modbus(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}

/**
 * Builds the body of a message: values are packed little endian, without
 * alignment, in signature order (see PROTOCOL.md section 4).
 */
class ArgWriter {
    private val sig = StringBuilder()
    private val out = ByteArrayOutputStream()

    val signature: String get() = sig.toString()
    fun body(): ByteArray = out.toByteArray()

    private fun le(value: Long, bytes: Int) {
        for (i in 0 until bytes) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    fun y(v: Int) = apply { sig.append('y'); le(v.toLong(), 1) }
    fun q(v: Int) = apply { sig.append('q'); le(v.toLong(), 2) }
    fun n(v: Int) = apply { sig.append('n'); le(v.toLong(), 2) }
    fun i(v: Int) = apply { sig.append('i'); le(v.toLong(), 4) }
    fun u(v: Long) = apply { sig.append('u'); le(v, 4) }
    fun s(v: String) = apply {
        sig.append('s')
        val raw = v.toByteArray(Charsets.UTF_8)
        le(raw.size + 1L, 4)
        out.write(raw)
        out.write(0)
    }
    fun ay(v: ByteArray) = apply {
        sig.append("ay")
        le(v.size.toLong(), 4)
        out.write(v)
    }
}

/**
 * Decodes a body according to its signature. Integers come back as [Long]
 * (all integer types), strings as [String], `ay` as [ByteArray], other arrays
 * as [List] (an array of structs is a list of lists of fields).
 * Decoding stops at the first malformed value and returns what was decoded.
 */
object ArgReader {
    fun splitSignature(sig: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < sig.length) {
            val end = completeTypeEnd(sig, i)
            out.add(sig.substring(i, end))
            i = end
        }
        return out
    }

    private fun completeTypeEnd(sig: String, start: Int): Int {
        return when (sig[start]) {
            'a' -> completeTypeEnd(sig, start + 1)
            '(' -> {
                var depth = 0
                var i = start
                while (i < sig.length) {
                    if (sig[i] == '(') depth++
                    if (sig[i] == ')') { depth--; if (depth == 0) return i + 1 }
                    i++
                }
                throw IllegalArgumentException("unbalanced signature: $sig")
            }
            else -> start + 1
        }
    }

    fun decode(sig: String, body: ByteArray): List<Any?> {
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val values = ArrayList<Any?>()
        try {
            for (t in splitSignature(sig)) values.add(one(t, buf))
        } catch (_: RuntimeException) {
            // Keep the partial result.
        }
        return values
    }

    private fun count(buf: ByteBuffer): Int {
        val n = buf.int.toLong() and 0xFFFFFFFFL
        // Every element takes at least one byte, so a larger count is corrupt.
        require(n <= buf.remaining()) { "bad count" }
        return n.toInt()
    }

    private fun one(t: String, buf: ByteBuffer): Any? = when {
        t == "y" || t == "b" -> (buf.get().toLong() and 0xFF)
        t == "n" -> buf.short.toLong()
        t == "q" -> (buf.short.toLong() and 0xFFFF)
        t == "i" -> buf.int.toLong()
        t == "u" -> (buf.int.toLong() and 0xFFFFFFFFL)
        t == "x" || t == "t" -> buf.long
        t == "d" -> buf.double
        t == "s" || t == "o" || t == "g" -> {
            val n = count(buf)
            val raw = ByteArray(n)
            buf.get(raw)
            var len = n
            while (len > 0 && raw[len - 1] == 0.toByte()) len--
            String(raw, 0, len, Charsets.UTF_8)
        }
        t == "ay" -> {
            val n = count(buf)
            ByteArray(n).also { buf.get(it) }
        }
        t.startsWith("a(") -> {
            val n = count(buf)
            val fields = splitSignature(t.substring(2, t.length - 1))
            List(n) { fields.map { f -> one(f, buf) } }
        }
        t.startsWith("(") -> splitSignature(t.substring(1, t.length - 1)).map { one(it, buf) }
        t.startsWith("a") -> {
            val n = count(buf)
            val elem = t.substring(1)
            List(n) { one(elem, buf) }
        }
        else -> throw IllegalArgumentException("unsupported type $t")
    }
}

/** Message layer (PROTOCOL.md section 4). */
class Message(
    val type: Int,
    val id: Int,
    val priority: Int,
    val signature: String,
    val body: ByteArray,
) {
    fun packHeader(): ByteArray {
        val sig = signature.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(16 + sig.size + 1).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MSG_MAGIC.toShort())
        buf.put(MSG_ENDIAN.toByte())
        buf.put(type.toByte())
        buf.put(priority.toByte())
        buf.put(1)
        buf.putShort(id.toShort())
        buf.putInt(body.size)
        buf.putInt(sig.size + 1)
        buf.put(sig)
        buf.put(0)
        return buf.array()
    }

    fun args(): List<Any?> = ArgReader.decode(signature, body)

    companion object {
        const val MSG_MAGIC = 0xA5A5
        const val MSG_ENDIAN = 0x6C
        const val CALL = 1
        const val RETURN = 2
        const val SIGNAL = 4

        fun parse(header: ByteArray, body: ByteArray): Message {
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.short.toInt() and 0xFFFF
            require(magic == MSG_MAGIC) { "bad message magic" }
            buf.get() // endianness
            val type = buf.get().toInt() and 0xFF
            val prio = buf.get().toInt() and 0xFF
            buf.get() // version
            val id = buf.short.toInt() and 0xFFFF
            val bodyLen = buf.int
            val sigLen = buf.int
            require(sigLen in 0..(header.size - 16)) { "bad signature length" }
            var end = 16 + sigLen
            while (end > 16 && header[end - 1] == 0.toByte()) end--
            val sig = String(header, 16, end - 16, Charsets.US_ASCII)
            val b = if (bodyLen in 0 until body.size) body.copyOf(bodyLen) else body
            return Message(type, id, prio, sig, b)
        }
    }
}

/** Link layer (PROTOCOL.md section 3). */
object Frames {
    const val MAGIC = 0x5AA5AA55
    const val VERSION = 0x0100
    const val HEADER = 24
    const val MAX_PAYLOAD = 0x7D8
    const val MAX_FRAME = 0x800

    /** Splits a message into frames: the message header alone in frame 0, then the body. */
    fun encode(msg: Message, streamSeq: Int): List<ByteArray> {
        val chunks = ArrayList<ByteArray>()
        chunks.add(msg.packHeader())
        var off = 0
        while (off < msg.body.size) {
            val end = minOf(off + MAX_PAYLOAD, msg.body.size)
            chunks.add(msg.body.copyOfRange(off, end))
            off = end
        }
        var remaining = chunks.sumOf { it.size + HEADER }
        return chunks.mapIndexed { idx, c ->
            val len = c.size + HEADER
            val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(MAGIC)
            buf.putShort(0)
            buf.putShort(VERSION.toShort())
            buf.putInt(len)
            buf.putInt(idx)
            buf.putInt(streamSeq)
            buf.putInt(remaining)
            buf.put(c)
            val frame = buf.array()
            val crc = Crc16.modbus(frame, 6, len - 6)
            frame[4] = (crc and 0xFF).toByte()
            frame[5] = (crc ushr 8).toByte()
            remaining -= len
            frame
        }
    }
}

/** Reassembles frames from a byte stream into messages. Not thread-safe. */
class FrameReader(private val onError: (String) -> Unit = {}) {
    private var buf = ByteArray(8192)
    private var len = 0
    private val partial = HashMap<Int, MutableList<ByteArray>>()

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Message> {
        ensure(len + length)
        System.arraycopy(data, offset, buf, len, length)
        len += length
        val out = ArrayList<Message>()
        while (true) {
            val i = indexOfMagic()
            if (i < 0) {
                // Keep the last 3 bytes: they may be the start of a magic.
                drop(maxOf(0, len - 3))
                return out
            }
            drop(i)
            if (len < Frames.HEADER) return out
            val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            val crc = bb.getShort(4).toInt() and 0xFFFF
            val ver = bb.getShort(6).toInt() and 0xFFFF
            val flen = bb.getInt(8)
            val seq = bb.getInt(16)
            val remaining = bb.getInt(20)
            if (ver != Frames.VERSION || flen < Frames.HEADER || flen > Frames.MAX_FRAME) {
                drop(4)
                continue
            }
            if (len < flen) return out
            val frame = buf.copyOf(flen)
            drop(flen)
            if (Crc16.modbus(frame, 6, flen - 6) != crc) {
                onError("CRC mismatch, frame dropped")
                continue
            }
            val parts = partial.getOrPut(seq) { ArrayList() }
            parts.add(frame.copyOfRange(Frames.HEADER, flen))
            if (flen == remaining) {
                partial.remove(seq)
                try {
                    val body = ByteArrayOutputStream()
                    for (p in parts.drop(1)) body.write(p)
                    out.add(Message.parse(parts[0], body.toByteArray()))
                } catch (e: RuntimeException) {
                    onError("bad message: ${e.message}")
                }
            }
            if (partial.size > 64) partial.clear() // stale fragments
        }
    }

    private fun indexOfMagic(): Int {
        for (i in 0..len - 4) {
            if (buf[i] == 0x55.toByte() && buf[i + 1] == 0xAA.toByte() &&
                buf[i + 2] == 0xA5.toByte() && buf[i + 3] == 0x5A.toByte()
            ) return i
        }
        return -1
    }

    private fun drop(n: Int) {
        if (n <= 0) return
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }

    private fun ensure(cap: Int) {
        if (cap > buf.size) buf = buf.copyOf(maxOf(cap, buf.size * 2))
    }
}
