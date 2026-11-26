package io.github.hibyremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconTest {
    private fun parse(json: String, ip: String = "192.168.1.50") =
        Beacons.parse(json.toByteArray(Charsets.UTF_8), json.toByteArray(Charsets.UTF_8).size, ip)

    @Test
    fun jsonWithNameAndAddress() {
        val b = parse("""{"name":"HiBy R6","address":"192.168.1.20:4002"}""")
        assertEquals(Beacon("HiBy R6", "192.168.1.20", 4002), b)
    }

    @Test
    fun jsonFallsBackToDeviceNameAndSourceIp() {
        val b = parse("""{ "deviceName" : "Player", "other": 3 }""")
        assertEquals(Beacon("Player", "192.168.1.50", Beacons.DEFAULT_PORT), b)
    }

    @Test
    fun jsonEscapesAndKeyInsideValueIsIgnored() {
        val b = parse("""{"note":"\"name\":\"fake\"","name":"A \"quoted\" é name","address":"10.0.0.2"}""")
        assertEquals(Beacon("A \"quoted\" é name", "10.0.0.2", Beacons.DEFAULT_PORT), b)
    }

    @Test
    fun nonBeaconDataIsRejected() {
        assertNull(parse("hello"))
        assertNull(parse(""))
    }

    @Test
    fun binaryBeacon() {
        val data = ByteArray(334)
        "HLBM".toByteArray().copyInto(data)
        "192.168.1.30:4001".toByteArray().copyInto(data, 14)
        "Binary player".toByteArray().copyInto(data, 78)
        assertEquals(Beacon("Binary player", "192.168.1.30", 4001), Beacons.parse(data, data.size, "1.2.3.4"))
    }
}
