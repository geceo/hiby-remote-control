package io.github.hibyremote.remote

import io.github.hibyremote.protocol.Beacon
import io.github.hibyremote.protocol.Beacons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Listens to player beacons: UDP multicast 239.0.0.5:4001 and broadcast :4003.
 * Emits every beacon received; collectors deduplicate.
 */
class WifiDiscovery(private val platform: Platform) {
    fun beacons(): Flow<Beacon> = callbackFlow {
        val lock = try {
            platform.acquireMulticast()
        } catch (e: Exception) {
            platform.w(TAG, "multicast lock unavailable", e)
            null
        }
        val sockets = mutableListOf<DatagramSocket>()

        fun listen(socket: DatagramSocket) {
            sockets += socket
            thread(isDaemon = true, name = "hiby-discovery") {
                val buf = ByteArray(2048)
                while (!socket.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        val ip = p.address?.hostAddress ?: continue
                        Beacons.parse(p.data, p.length, ip)?.let { trySend(it) }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Exception) {
                        platform.w(TAG, "discovery receive failed", e)
                    }
                }
            }
        }

        try {
            val m = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MCAST_PORT))
                joinEverywhere(this)
            }
            listen(m)
        } catch (e: Exception) {
            platform.w(TAG, "multicast discovery unavailable", e)
        }
        try {
            val b = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(BCAST_PORT))
            }
            listen(b)
        } catch (e: Exception) {
            platform.w(TAG, "broadcast discovery unavailable", e)
        }

        awaitClose {
            sockets.forEach { it.close() }
            try {
                lock?.close()
            } catch (_: Exception) {
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Joins the multicast group on every interface that is up, not loopback and
     * multicast-capable (a single default-interface join misses beacons on
     * multi-NIC machines). Falls back to the default join if none succeeded.
     */
    private fun joinEverywhere(socket: MulticastSocket) {
        val group = InetAddress.getByName(MCAST_GROUP)
        var joined = 0
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            emptyList()
        }
        for (ni in interfaces) {
            try {
                if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
                socket.joinGroup(InetSocketAddress(group, 0), ni)
                joined++
            } catch (e: Exception) {
                platform.w(TAG, "multicast join failed on ${ni.name}", e)
            }
        }
        if (joined == 0) {
            @Suppress("DEPRECATION")
            socket.joinGroup(group)
        }
    }

    companion object {
        private const val TAG = "HiByDiscovery"
        const val MCAST_GROUP = "239.0.0.5"
        const val MCAST_PORT = 4001
        const val BCAST_PORT = 4003
    }
}
