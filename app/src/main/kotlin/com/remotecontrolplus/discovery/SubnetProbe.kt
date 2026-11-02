package com.remotecontrolplus.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import com.remotecontrolplus.core.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Last-resort discovery for TVs that ignore multicast: try to open the
 * remote-control port on every address in the phone's /24 subnet. Samsung
 * answers on 8001, LG on 3000, Roku on 8060.
 */
class SubnetProbe(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    data class Hit(val host: String, val port: Int)

    /** The /24 prefix of the active Wi-Fi interface, e.g. "192.168.68". */
    fun localPrefix(): String? {
        val network = connectivity.activeNetwork ?: return null
        val props: LinkProperties = connectivity.getLinkProperties(network) ?: return null
        val address = props.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress } ?: return null
        return address.hostAddress?.substringBeforeLast('.')
    }

    suspend fun scan(ports: List<Int>, timeoutMs: Int = 400): List<Hit> = withContext(Dispatchers.IO) {
        val prefix = localPrefix()
        if (prefix == null) {
            Diagnostics.log("probe", "no IPv4 address on the active network; skipping subnet probe")
            return@withContext emptyList()
        }
        Diagnostics.log("probe", "probing $prefix.1-254 on ports $ports")
        val gate = Semaphore(64)
        coroutineScope {
            (1..254).flatMap { last ->
                ports.map { port ->
                    async {
                        gate.withPermit {
                            val host = "$prefix.$last"
                            val open = runCatching {
                                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                            }.isSuccess
                            if (open) Hit(host, port) else null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }.also { hits -> Diagnostics.log("probe", "open ports: ${hits.joinToString { "${it.host}:${it.port}" }.ifEmpty { "none" }}") }
    }
}
