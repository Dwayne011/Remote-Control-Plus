package com.remotecontrolplus.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * After a failed connection, test a handful of ports every device of that type
 * always has open, so the log shows whether the address still belongs to the
 * TV and whether anything on the way is turning connections away.
 *
 *  - open on some ports, refused on the remote port: it is the TV, and only
 *    that service is closed (TV asleep, service restarting, port changed).
 *  - refused on every port: something answers at that address but is not the
 *    TV, or something between rejects all connections.
 *  - timeout on every port: nothing answers there (TV off, moved, or blocked).
 */
object NetworkCheck {

    private val PORTS = mapOf(
        DeviceType.APPLE_TV to listOf(7000 to "AirPlay", 3689 to "media sharing", 5000 to "AirPlay audio"),
        DeviceType.SAMSUNG to listOf(8001 to "remote (plain)", 8002 to "remote (secure)", 9197 to "device info", 7676 to "screen mirroring"),
        DeviceType.LG to listOf(3000 to "remote (plain)", 3001 to "remote (secure)", 1925 to "device info"),
        DeviceType.ROKU to listOf(8060 to "remote"),
        DeviceType.GOOGLE_TV to listOf(6466 to "remote", 6467 to "pairing", 8009 to "Cast", 8008 to "Cast info"),
        DeviceType.CHROMECAST to listOf(8009 to "Cast", 8008 to "Cast info"),
    )

    suspend fun run(device: TvDevice) = withContext(Dispatchers.IO) {
        val host = device.host
        val ports = PORTS[device.type].orEmpty()
        Diagnostics.log("netcheck", "checking ${device.name} at $host; this phone is ${localAddresses()}")
        val reachable = runCatching { InetAddress.getByName(host).isReachable(1500) }.getOrDefault(false)
        val results = coroutineScope {
            ports.map { (port, label) -> async { Triple(port, label, probe(host, port)) } }.awaitAll()
        }
        results.forEach { (port, label, result) -> Diagnostics.log("netcheck", "  $host:$port ($label): $result") }
        val outcomes = results.map { it.third.substringBefore(' ') }.toSet()
        val verdict = when {
            "open" in outcomes -> "the TV is at this address; only its remote service is not accepting connections"
            outcomes == setOf("refused") -> "something at this address refuses every port; it may not be the TV any more, or something is rejecting connections"
            outcomes == setOf("timeout") -> "nothing answers at this address on any port"
            else -> "mixed results"
        }
        Diagnostics.log("netcheck", "  ping ${if (reachable) "answered" else "no answer"}; $verdict")
    }

    private fun probe(host: String, port: Int): String {
        val started = System.currentTimeMillis()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
            "open (${System.currentTimeMillis() - started}ms)"
        } catch (e: SocketTimeoutException) {
            "timeout"
        } catch (e: ConnectException) {
            val ms = System.currentTimeMillis() - started
            if (e.message?.contains("ECONNREFUSED") == true || e.message?.contains("refused", true) == true) {
                "refused (${ms}ms)"
            } else {
                "failed (${e.message?.substringAfterLast(": ")})"
            }
        } catch (e: Exception) {
            "failed (${e.javaClass.simpleName}: ${e.message})"
        }
    }

    private fun localAddresses(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.interfaceAddresses.filter { it.address is java.net.Inet4Address }.map { "${nif.name} ${it.address.hostAddress}/${it.networkPrefixLength}" } }
            .joinToString()
    }.getOrDefault("unknown")
}
