package com.remotecontrolplus.core

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

    /** Connect using stored credentials. Moves [state] to Connected or PairingRequired. */
    suspend fun connect()

    fun disconnect()

    suspend fun sendKey(key: RemoteKey)

    /** Type into whatever the device has focused. Only valid when [Capabilities.textEntry]. */
    suspend fun sendText(text: String)
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
