package com.remotecontrolplus.core

import kotlinx.serialization.Serializable

/** The kinds of device this app can control. */
@Serializable
enum class DeviceType(val label: String) {
    SAMSUNG("Samsung TV"),
    LG("LG TV"),
    ROKU("Roku"),
    APPLE_TV("Apple TV"),
    GOOGLE_TV("Google TV"),
    CHROMECAST("Chromecast"),
}

/**
 * A TV as the app knows it: enough to reconnect later. Credentials (pairing
 * tokens, keys) are kept separately in [com.remotecontrolplus.data.CredentialStore].
 */
@Serializable
data class TvDevice(
    /** Stable identifier: a protocol-level id when the device advertises one, else host. */
    val id: String,
    val name: String,
    val type: DeviceType,
    val host: String,
    val port: Int,
    val model: String? = null,
    /** MAC address, when known, so standby devices can be woken with Wake-on-LAN. */
    val mac: String? = null,
)

/** The controls shared across every device type. */
enum class RemoteKey {
    POWER,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    SELECT,
    BACK,
    HOME,
    PLAY_PAUSE,
}

/** What a particular device can do, so the UI hides what would do nothing. */
data class Capabilities(
    val keys: Set<RemoteKey>,
    val textEntry: Boolean,
    /** Shown under the power button when it does something other than toggle power. */
    val powerHint: String? = null,
) {
    companion object {
        val ALL_KEYS: Set<RemoteKey> = RemoteKey.entries.toSet()
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    /** The device needs to be paired (again) before it will accept commands. */
    data object PairingRequired : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/** Text the device reports as currently in its focused field, when it does. */
data class TextFieldState(val focused: Boolean, val text: String)
