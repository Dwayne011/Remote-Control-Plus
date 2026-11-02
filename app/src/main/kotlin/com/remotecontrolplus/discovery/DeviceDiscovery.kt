package com.remotecontrolplus.discovery

import com.remotecontrolplus.core.TvDevice
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Runs mDNS and SSDP side by side and emits devices as they are identified. */
class DeviceDiscovery(
    private val nsd: NsdDiscovery,
    private val ssdp: SsdpDiscovery,
) {
    fun scan(timeoutMs: Long = 6000): Flow<TvDevice> = channelFlow {
        coroutineScope {
            launch { runCatching { nsd.scan(timeoutMs) { trySend(it) } } }
            launch { runCatching { ssdp.scan(timeoutMs) { trySend(it) } } }
        }
    }
}
