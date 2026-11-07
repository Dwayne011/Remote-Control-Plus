package com.remotecontrolplus.devices

import com.remotecontrolplus.core.DeviceType
import com.remotecontrolplus.core.TvController
import com.remotecontrolplus.core.TvDevice
import com.remotecontrolplus.discovery.NsdDiscovery
import kotlinx.coroutines.CoroutineScope

/** Builds the right [TvController] for a device from its stored credential. */
class ControllerFactory(
    private val scope: CoroutineScope,
    private val nsd: NsdDiscovery,
    private val credentials: com.remotecontrolplus.data.CredentialStore,
    /** Saves a device whose address changed. */
    private val onMoved: (TvDevice) -> Unit = {},
) {
    fun create(device: TvDevice, credential: String?): TvController = when (device.type) {
        DeviceType.SAMSUNG -> SamsungController(device, credential)
        DeviceType.LG -> LgController(device, credential)
        DeviceType.ROKU -> RokuController(device)
        DeviceType.APPLE_TV -> AppleTvController(
            device = device,
            serializedCredentials = credential,
            airplayCredentials = credentials.get(AppleTvController.airplayKey(device)),
            scope = scope,
            portResolver = { d, timeout -> nsd.resolveCompanionPort(d, timeout) },
            cachedPort = { d -> nsd.cachedCompanionPort(d) },
            rememberPort = { d, port -> nsd.rememberCompanionPort(d, port) },
        )
        DeviceType.GOOGLE_TV -> GoogleTvController(
            device, credential, scope,
            findHost = { d -> nsd.findAndroidTv(d) },
            onMoved = onMoved,
        )
        DeviceType.CHROMECAST -> ChromecastController(device, scope)
    }
}
