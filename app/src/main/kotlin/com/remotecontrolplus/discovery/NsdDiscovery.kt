package com.remotecontrolplus.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.remotecontrolplus.core.DeviceType
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.TvDevice
import dev.atvremote.protocol.discovery.COMPANION_SERVICE_TYPE
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/**
 * mDNS discovery through Android's NsdManager for the devices that advertise
 * themselves that way: Apple TV, Google TV / Android TV, and Cast receivers.
 *
 * Discovery and resolution are separated: services are collected first and
 * resolved one at a time, because overlapping `resolveService` calls fail.
 */
class NsdDiscovery(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun scan(timeoutMs: Long, onFound: (TvDevice) -> Unit) = withContext(Dispatchers.IO) {
        val infos = browse(listOf(COMPANION_SERVICE_TYPE, ANDROID_TV_SERVICE_TYPE, CAST_SERVICE_TYPE), timeoutMs)
        Diagnostics.log("mdns", "browse found ${infos.size} service(s): ${infos.joinToString { it.serviceType.trim('.') + "/" + it.serviceName }}")
        val resolved = infos.mapNotNull { info ->
            withTimeoutOrNull(3000) { resolve(info) }
                ?.also { Diagnostics.log("mdns", "resolved ${it.serviceName} -> ${it.host?.hostAddress}:${it.port}") }
                ?: run { Diagnostics.log("mdns", "could not resolve ${info.serviceName}"); null }
        }

        // A Google TV device shows up as both a Cast receiver and an Android TV
        // remote service. The remote service is the richer one, so when a host
        // offers both it is reported as Google TV only.
        val androidTvHosts = resolved
            .filter { it.serviceType.contains(ANDROID_TV_SERVICE_TYPE) }
            .mapNotNull { it.host?.hostAddress }
            .toSet()

        resolved.mapNotNull { info -> toDevice(info, androidTvHosts) }
            .distinctBy { it.id }
            .forEach(onFound)
    }

    /**
     * The Companion port an Apple TV currently advertises, which changes across
     * reboots. Null when the device is not found within the timeout.
     */
    suspend fun resolveCompanionPort(device: TvDevice): Int? = withContext(Dispatchers.IO) {
        val infos = browse(listOf(COMPANION_SERVICE_TYPE), 3000)
        for (info in infos) {
            val r = withTimeoutOrNull(3000) { resolve(info) } ?: continue
            val id = r.txt("rpMRtID")
            if (id == device.id || r.host?.hostAddress == device.host) {
                Diagnostics.log("mdns", "Apple TV ${device.name} currently advertises Companion port ${r.port}")
                return@withContext r.port
            }
        }
        null
    }

    private suspend fun browse(types: List<String>, timeoutMs: Long): List<NsdServiceInfo> {
        val found = ConcurrentLinkedQueue<NsdServiceInfo>()
        val listeners = types.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.add(serviceInfo) }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }.also { runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
        }
        try {
            delay(timeoutMs)
        } finally {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        }
        return found.distinctBy { it.serviceType + it.serviceName }
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont: CancellableContinuation<NsdServiceInfo?> ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo)
                }
            })
        }

    private fun toDevice(info: NsdServiceInfo, androidTvHosts: Set<String>): TvDevice? {
        val host = info.host?.hostAddress ?: return null
        val type = info.serviceType
        return when {
            type.contains(COMPANION_SERVICE_TYPE) -> {
                // Only Apple TVs; HomePods and Macs advertise Companion too.
                val model = info.txt("rpMd") ?: ""
                if (!model.startsWith("AppleTV")) return null
                TvDevice(
                    id = info.txt("rpMRtID") ?: host,
                    name = info.serviceName,
                    type = DeviceType.APPLE_TV,
                    host = host,
                    port = info.port,
                    model = model,
                )
            }
            type.contains(ANDROID_TV_SERVICE_TYPE) -> TvDevice(
                id = "atv-$host",
                name = info.serviceName,
                type = DeviceType.GOOGLE_TV,
                host = host,
                port = info.port,
            )
            type.contains(CAST_SERVICE_TYPE) -> {
                if (host in androidTvHosts) return null
                TvDevice(
                    id = info.txt("id") ?: "cast-$host",
                    name = info.txt("fn") ?: info.serviceName,
                    type = DeviceType.CHROMECAST,
                    host = host,
                    port = info.port,
                    model = info.txt("md"),
                )
            }
            else -> null
        }
    }

    private fun NsdServiceInfo.txt(key: String): String? = attributes[key]?.toString(Charsets.UTF_8)

    companion object {
        const val ANDROID_TV_SERVICE_TYPE = "_androidtvremote2._tcp"
        const val CAST_SERVICE_TYPE = "_googlecast._tcp"
    }
}
