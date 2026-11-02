package com.remotecontrolplus.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Magic packet sender for TVs that are off but still on the network. */
object WakeOnLan {
    suspend fun send(mac: String) = withContext(Dispatchers.IO) {
        val bytes = mac.split(":", "-").map { it.toInt(16).toByte() }
        require(bytes.size == 6) { "bad MAC address" }
        val payload = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (port in listOf(9, 7)) {
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), port))
            }
        }
    }
}
