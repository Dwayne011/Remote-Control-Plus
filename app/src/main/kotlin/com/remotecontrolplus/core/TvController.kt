package com.remotecontrolplus.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One live connection to one device. Created per device by
 * [com.remotecontrolplus.devices.ControllerFactory]; the UI never sees the
 * protocol underneath.
 */
interface TvController {
    val device: TvDevice
    val capabilities: Capabilities
    val state: StateFlow<ConnectionState>
    val textField: StateFlow<TextFieldState>

    /** True when the device is known to be on, false when known off, null when it cannot tell. */
    val powerState: StateFlow<Boolean?>
        get() = UNKNOWN_POWER

    /** Volume as 0..1 when the device reports it, else null. */
    val volume: StateFlow<Float?>
        get() = NO_VOLUME

    /** Connect using stored credentials. Moves [state] to Connected or PairingRequired. */
    suspend fun connect()

    fun disconnect()

    suspend fun sendKey(key: RemoteKey)

    /**
     * A swipe on the touch pad in one of UP/DOWN/LEFT/RIGHT. Devices with a
     * real trackpad (Apple TV) translate it to a gesture; others treat it as
     * the matching key.
     */
    suspend fun swipe(direction: RemoteKey) = sendKey(direction)

    /** Set the volume to [fraction] in 0..1. Only valid when [Capabilities.volumeSlider]. */
    suspend fun setVolume(fraction: Float) = Unit

    /** Type into whatever the device has focused. Only valid when [Capabilities.textEntry]. */
    suspend fun sendText(text: String)

    companion object {
        private val NO_VOLUME: StateFlow<Float?> = MutableStateFlow(null)
        private val UNKNOWN_POWER: StateFlow<Boolean?> = MutableStateFlow(null)
    }
}

/**
 * A pairing in progress. Devices either show a code the user types here
 * ([needsPin]) or show an on-screen allow prompt and hand back a token.
 */
interface PairingHandle {
    val needsPin: Boolean

    /** What the user should be told while waiting, e.g. "Accept the prompt on your TV". */
    val instruction: String

    /**
     * Finish pairing. [pin] is required when [needsPin]. Returns the credential
     * blob to store for this device.
     */
    suspend fun finish(pin: String?): String

    fun cancel()
}

/** Controllers that need a one-time pairing implement this as well. */
interface Pairable {
    suspend fun beginPairing(): PairingHandle
}

class NotConnectedException : Exception("Not connected to the TV")
class PairingException(message: String) : Exception(message)
