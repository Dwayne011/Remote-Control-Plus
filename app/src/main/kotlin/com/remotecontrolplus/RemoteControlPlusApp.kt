package com.remotecontrolplus

import android.app.Application
import com.remotecontrolplus.data.CredentialStore
import com.remotecontrolplus.data.DeviceRepository
import com.remotecontrolplus.devices.ControllerFactory
import com.remotecontrolplus.discovery.DeviceDiscovery
import com.remotecontrolplus.discovery.NsdDiscovery
import com.remotecontrolplus.discovery.SsdpDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-scoped dependencies; small enough not to need an injection framework. */
class RemoteControlPlusApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val credentials by lazy { CredentialStore(this) }
    val repository by lazy { DeviceRepository(this, credentials) }
    val nsd by lazy { NsdDiscovery(this) }
    val discovery by lazy { DeviceDiscovery(nsd, SsdpDiscovery(this)) }
    val controllers by lazy { ControllerFactory(appScope, nsd) }
}
