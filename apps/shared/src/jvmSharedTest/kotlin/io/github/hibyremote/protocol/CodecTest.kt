package io.github.hibyremote.protocol

import io.github.hibyremote.transport.BleFraming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Reference frames produced by hibylink.py (see PROTOCOL.md section 7).
    private val connectFrames = listOf(
        "55aaa55ae05f00012d000000000000000b0000005c000000a5a56c0101010b0017000000050000007573736900",
        "55aaa55a00fc00012f000000010000000b0000002f000000030000c506000000477565737400010000000000000000",
    )
    private val volumeSignalFrames = listOf(
        "55aaa55a52fd00012b000000000000006300000048000000a5a56c04010105000500000003000000757900",
        "55aaa55aae6b00011d00000001000000630000001d000000051102c52a",
    )

    @Test
    fun crcMatchesModbusCheckValue() {
        assertEquals(0x4B37, Crc16.modbus("123456789".toByteArray()))
    }

    @Test
    fun connectCallMatchesReference() {
        val w = ArgWriter().u(Actions.CONNECT).s("Guest").s("").i(0)
        assertEquals("ussi", w.signature)
        val frames = Frames.encode(Message(Message.CALL, 11, 1, w.signature, w.body()), 11)
        assertEquals(connectFrames.size, frames.size)
        frames.zip(connectFrames).forEach { (f, ref) -> assertArrayEquals(hex(ref), f) }
    }

    @Test
    fun readerDecodesSignalSplitAcrossReads() {
        val stream = volumeSignalFrames.joinToString("").let(::hex)
        val reader = FrameReader()
        val out = ArrayList<Message>()
        // Feed one byte at a time, with garbage in front, to exercise resynchronization.
        out += reader.feed(byteArrayOf(1, 2, 3))
        for (b in stream) out += reader.feed(byteArrayOf(b))
        assertEquals(1, out.size)
        val m = out[0]
        assertEquals(Message.SIGNAL, m.type)
        assertEquals("uy", m.signature)
        assertEquals(listOf(Actions.VOLUME, 42L), m.args())
    }

    @Test
    fun largeBodyIsFragmentedAndReassembled() {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        val w = ArgWriter().u(Actions.COVER).ay(payload)
        val frames = Frames.encode(Message(Message.SIGNAL, 7, 1, w.signature, w.body()), 20)
        assertEquals(4, frames.size) // header + 3 body chunks of <= 2008 bytes
        assertTrue(frames.all { it.size <= Frames.MAX_FRAME })
        val reader = FrameReader()
        val msgs = frames.flatMap { reader.feed(it) }
        assertEquals(1, msgs.size)
        val args = msgs[0].args()
        assertEquals(Actions.COVER, args[0])
        assertArrayEquals(payload, args[1] as ByteArray)
    }

    @Test
    fun decodesStructArrays() {
        // a(ssuu): two list entries
        val w = ArgWriter().i(0)
        val body = w.body() + ArgWriter().u(2).s("Album A").s("uuid-a").u(0).u(12)
            .s("Album B").s("uuid-b").u(1).u(3).body()
        val values = ArgReader.decode("ia(ssuu)", body)
        assertEquals(0L, values[0])
        val lists = Decode.lists(values[1])
        assertEquals(listOf(ListEntry("Album A", "uuid-a", 0, 12), ListEntry("Album B", "uuid-b", 1, 3)), lists)
    }

    @Test
    fun decodesMetaInfo() {
        val w = ArgWriter().u(1)
        listOf("Title", "Artist", "Album", "2001", "Jazz", "/sd/a.flac", "").forEach { w.s(it) }
        w.u(245_000).u(1000).u(900).u(44100).q(3).y(16).y(2).y(0)
        val values = ArgReader.decode("a(sssssssuuuuqyyy)", w.body())
        val meta = Decode.meta(values[0])
        assertNotNull(meta)
        assertEquals("Title", meta!!.title)
        assertEquals(44100L, meta.sampleRate)
        assertEquals(245_000L, meta.durationMs)
    }

    @Test
    fun splitsSignatures() {
        assertEquals(listOf("u", "a(ssu)", "y", "ay", "a(s(yy))"), ArgReader.splitSignature("ua(ssu)yaya(s(yy))"))
    }

    @Test
    fun parsesBinaryBeacon() {
        val b = ByteArray(334)
        "HLBM".toByteArray().copyInto(b, 0)
        "192.168.1.42:4005".toByteArray().copyInto(b, 14)
        "HiBy R6".toByteArray().copyInto(b, 78)
        val beacon = Beacons.parse(b, b.size, "10.0.0.1")
        assertEquals(Beacon("HiBy R6", "192.168.1.42", 4005), beacon)
    }

    @Test
    fun bleChunking() {
        val frame = ByteArray(40) { it.toByte() }
        val pieces = BleFraming.chunk(frame)
        assertEquals(3, pieces.size)
        assertEquals(0, pieces[0][0].toInt())
        assertEquals(20, pieces[0][1].toInt())
        assertEquals(1, pieces[1][0].toInt())
        assertEquals(0xFF.toByte(), pieces[2][0])
        assertEquals(6, pieces[2][1].toInt())
        assertArrayEquals(frame, pieces.flatMap { it.drop(2) }.toByteArray())
    }

    @Test
    fun libraryChildUris() {
        assertEquals("2#1@[05]Kind of Blue", LibraryUri.child(LibraryUri.ALBUMS, ListEntry("Kind of Blue", "", 0, 5)))
        assertEquals("3#0@[03]Miles@[05]", LibraryUri.child(LibraryUri.ARTISTS, ListEntry("Miles", "", 0, 5)))
        assertEquals("4#1@[03]Miles@[05]KoB", LibraryUri.child("3#0@[03]Miles@[05]", ListEntry("KoB", "", 0, 5)))
        assertEquals("2#1@[02]Mine", LibraryUri.child(LibraryUri.PLAYLISTS, ListEntry("Mine", "", 0, 5)))
        assertEquals("2#0@[06]/sd", LibraryUri.child(LibraryUri.FILES_ROOT, ListEntry("/sd", "64-10", 0, 0)))
        assertEquals("2#0@[06]/sd/x", LibraryUri.child("2#0@[06]/sd", ListEntry("x", "/sd/x", 0, 0)))
        assertEquals("3#0@[03]Miles@[05]", LibraryUri.child("3#0@[07]mi@[03]", ListEntry("Miles", "", 0, 0)))
        assertEquals("2#1@[05]KoB", LibraryUri.child("3#0@[07]ko@[05]", ListEntry("KoB", "", 0, 0)))
        assertEquals("2#1@[03]Miles", LibraryUri.allSongsOf("3#0@[03]Miles@[05]"))
    }
}
