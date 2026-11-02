package com.remotecontrolplus

import android.app.Application
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.Haptics
import com.remotecontrolplus.data.CredentialStore
import com.remotecontrolplus.data.DeviceRepository
import com.remotecontrolplus.data.RemoteManager
import com.remotecontrolplus.devices.ControllerFactory
import com.remotecontrolplus.discovery.DeviceDiscovery
import com.remotecontrolplus.discovery.NsdDiscovery
import com.remotecontrolplus.discovery.SsdpDiscovery
import com.remotecontrolplus.discovery.SubnetProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-scoped dependencies; small enough not to need an injection framework. */
class RemoteControlPlusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        dev.atvremote.protocol.Log.sink = { Diagnostics.log("atv-wire", it) }
        dev.atvremote.protocol.Log.enabled = true
        Haptics.init(this)
        Diagnostics.log("app", "started")
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val credentials by lazy { CredentialStore(this) }
    val repository by lazy { DeviceRepository(this, credentials) }
    val nsd by lazy { NsdDiscovery(this) }
    val discovery by lazy { DeviceDiscovery(nsd, SsdpDiscovery(this), SubnetProbe(this)) }
    val controllers by lazy { ControllerFactory(appScope, nsd) }
    val remote by lazy { RemoteManager(appScope, repository, credentials, controllers) }
}
