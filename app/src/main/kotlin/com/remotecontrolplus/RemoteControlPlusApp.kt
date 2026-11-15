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
import kotlinx.coroutines.launch

/** Application-scoped dependencies; small enough not to need an injection framework. */
class RemoteControlPlusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        dev.atvremote.protocol.Log.sink = { Diagnostics.log("atv-wire", it) }
        dev.atvremote.protocol.Log.enabled = true
        Haptics.init(this)
        Diagnostics.log("app", "started")
        registerActivityLifecycleCallbacks(visibility)
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val credentials by lazy { CredentialStore(this) }
    val repository by lazy { DeviceRepository(this, credentials) }
    val nsd by lazy { NsdDiscovery(this) }
    val discovery by lazy { DeviceDiscovery(nsd, SsdpDiscovery(this), SubnetProbe(this)) }
    val controllers by lazy {
        ControllerFactory(appScope, nsd, credentials, onMoved = { moved ->
            appScope.launch { repository.save(moved) }
        })
    }
    val remote by lazy {
        RemoteManager(appScope, repository, credentials, controllers).also { manager ->
            manager.onActivity = { LockScreenRemoteService.noteActivity(this, isVisible) }
        }
    }

    /** Number of this app's activities currently on screen. */
    private val visibleActivities = java.util.concurrent.atomic.AtomicInteger(0)
    val isVisible: Boolean get() = visibleActivities.get() > 0

    private val visibility = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: android.app.Activity) { visibleActivities.incrementAndGet() }
        override fun onActivityStopped(activity: android.app.Activity) { visibleActivities.decrementAndGet() }
        override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
        override fun onActivityResumed(activity: android.app.Activity) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }
}
