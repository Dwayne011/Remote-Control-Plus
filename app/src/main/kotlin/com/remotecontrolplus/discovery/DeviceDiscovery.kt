package com.remotecontrolplus.discovery

import com.remotecontrolplus.core.DeviceType
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs mDNS and SSDP side by side and emits devices as they are identified.
 * When those finish, any Samsung, LG or Roku port found by probing the
 * subnet that was not already reported is described and emitted too.
 */
class DeviceDiscovery(
    private val nsd: NsdDiscovery,
    private val ssdp: SsdpDiscovery,
    private val probe: SubnetProbe,
) {
    fun scan(timeoutMs: Long = 6000): Flow<TvDevice> = channelFlow {
        Diagnostics.log("scan", "starting discovery (${timeoutMs}ms)")
        val reported = HashSet<String>()
        fun report(device: TvDevice) {
            if (reported.add("${device.host}|${device.type}")) trySend(device)
        }
        coroutineScope {
            launch { runCatching { nsd.scan(timeoutMs, ::report) }.onFailure { Diagnostics.log("mdns", "scan failed", it) } }
            launch { runCatching { ssdp.scan(timeoutMs, ::report) }.onFailure { Diagnostics.log("ssdp", "scan failed", it) } }
        }
        val hits = runCatching { probe.scan(listOf(8001, 3000, 8060)) }
            .onFailure { Diagnostics.log("probe", "subnet probe failed", it) }
            .getOrDefault(emptyList())
        withContext(Dispatchers.IO) {
            for (hit in hits) {
                val type = when (hit.port) {
                    8001 -> DeviceType.SAMSUNG
                    3000 -> DeviceType.LG
                    8060 -> DeviceType.ROKU
                    else -> continue
                }
                if ("${hit.host}|$type" in reported) continue
                val device = runCatching { ssdp.describe(hit.host, type) }
                    .onFailure { Diagnostics.log("probe", "${hit.host}:${hit.port} open but ${type.label} description failed", it) }
                    .getOrNull() ?: continue
                Diagnostics.log("probe", "found ${device.type.label} '${device.name}' at ${device.host} by port probe")
                report(device)
            }
        }
        Diagnostics.log("scan", "discovery finished: ${reported.size} device(s)")
    }
}
