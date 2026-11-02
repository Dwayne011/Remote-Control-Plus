package com.remotecontrolplus.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Magic packet sender for TVs that are off but still on the network. */
object WakeOnLan {
    suspend fun send(mac: String, host: String? = null) = withContext(Dispatchers.IO) {
        val bytes = mac.split(":", "-").map { it.toInt(16).toByte() }
        require(bytes.size == 6) { "bad MAC address" }
        val payload = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
        // Broadcast, plus the TV's own address: some routers filter broadcast
        // from Wi-Fi clients but pass a unicast packet to a known neighbour.
        val targets = listOfNotNull("255.255.255.255", host)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in targets) {
                for (port in listOf(9, 7)) {
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(target), port))
                    }
                }
            }
        }
        Diagnostics.log("wol", "magic packet for $mac sent to ${targets.joinToString()} on ports 9 and 7")
    }
}
