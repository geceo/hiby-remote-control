package io.github.hibyremote.transport

/**
 * BLE framing (PROTOCOL.md section 2.2): frames are cut into pieces of
 * `mtu - 2` bytes, each prefixed by `[seq][len]` where seq is 0xFF on the last
 * piece. Pure code, kept in the shared module so that it is unit-tested.
 */
object BleFraming {
    const val MTU = 20

    fun chunk(frame: ByteArray, mtu: Int = MTU): List<ByteArray> {
        val size = mtu - 2
        val out = ArrayList<ByteArray>()
        var off = 0
        var seq = 0
        while (off < frame.size) {
            val end = minOf(off + size, frame.size)
            val last = end == frame.size
            val piece = ByteArray(end - off + 2)
            piece[0] = (if (last) 0xFF else seq and 0xFF).toByte()
            piece[1] = (end - off + 2).toByte()
            System.arraycopy(frame, off, piece, 2, end - off)
            out.add(piece)
            off = end
            seq++
        }
        return out
    }
}
