package com.remotecontrolplus.devices

import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.NotConnectedException
import com.remotecontrolplus.core.Pairable
import com.remotecontrolplus.core.PairingException
import com.remotecontrolplus.core.PairingHandle
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.core.TextFieldState
import com.remotecontrolplus.core.TvController
import com.remotecontrolplus.core.TvDevice
import dev.atvremote.protocol.companion.AppleTvRemote
import dev.atvremote.protocol.companion.Button
import dev.atvremote.protocol.companion.CompanionClient
import dev.atvremote.protocol.hap.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Apple TV over Companion Link, via the vendored protocol module.
 *
 * The Companion port is ephemeral: the Apple TV changes it across reboots,
 * so [portResolver] re-discovers it over mDNS before each connection and
 * the stored port is only a fallback.
 */
class AppleTvController(
    override val device: TvDevice,
    private val serializedCredentials: String?,
    private val scope: CoroutineScope,
    private val portResolver: suspend (TvDevice) -> Int?,
) : TvController, Pairable {

    override val capabilities = Capabilities(
        // Apple TV has no mute key; volume routes over HDMI-CEC when the TV supports it.
        keys = Capabilities.ALL_KEYS - RemoteKey.MUTE,
        textEntry = true,
        powerHint = "Sleep / wake",
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _textField = MutableStateFlow(TextFieldState(focused = false, text = ""))
    override val textField: StateFlow<TextFieldState> = _textField.asStateFlow()

    private var remote: AppleTvRemote? = null
    private var asleep = false

    override suspend fun connect() {
        val credentials = serializedCredentials?.let { runCatching { Credentials.parse(it) }.getOrNull() }
        if (credentials == null) {
            _state.value = ConnectionState.PairingRequired
            return
        }
        _state.value = ConnectionState.Connecting
        try {
            val port = portResolver(device) ?: device.port
            val r = AppleTvRemote(device.host, port, credentials, scope)
            r.onDisconnect = { cause ->
                if (_state.value is ConnectionState.Connected) {
                    _state.value = ConnectionState.Error(cause?.message ?: "Connection lost")
                }
            }
            r.onTextFocus = { session ->
                _textField.value = TextFieldState(
                    focused = session != null,
                    text = session?.textBeforeCursor ?: "",
                )
            }
            r.connect()
            remote = r
            _state.value = ConnectionState.Connected
        } catch (e: dev.atvremote.protocol.companion.PairingException) {
            _state.value = ConnectionState.PairingRequired
        } catch (e: Exception) {
            _state.value = ConnectionState.Error(e.message ?: "Could not connect")
        }
    }

    override fun disconnect() {
        remote?.close()
        remote = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey) {
        val r = remote ?: throw NotConnectedException()
        when (key) {
            RemoteKey.POWER -> {
                r.press(if (asleep) Button.WAKE else Button.SLEEP)
                asleep = !asleep
            }
            RemoteKey.VOLUME_UP -> r.volumeUp()
            RemoteKey.VOLUME_DOWN -> r.volumeDown()
            RemoteKey.MUTE -> Unit
            RemoteKey.UP -> r.up()
            RemoteKey.DOWN -> r.down()
            RemoteKey.LEFT -> r.left()
            RemoteKey.RIGHT -> r.right()
            RemoteKey.SELECT -> r.select()
            RemoteKey.BACK -> r.menu()
            RemoteKey.HOME -> r.home()
            RemoteKey.PLAY_PAUSE -> r.playPause()
        }
    }

    override suspend fun sendText(text: String) {
        val r = remote ?: throw NotConnectedException()
        r.sendText(text, clearPrevious = false)
    }

    override suspend fun beginPairing(): PairingHandle {
        disconnect()
        _state.value = ConnectionState.Connecting
        val port = portResolver(device) ?: device.port
        val client = CompanionClient(device.host, port, scope)
        client.connect()
        val session = try {
            client.startPairing(deviceName = "Remote Control Plus")
        } catch (e: Exception) {
            client.close()
            _state.value = ConnectionState.Error(e.message ?: "Pairing failed")
            throw PairingException(e.message ?: "Pairing failed")
        }
        return object : PairingHandle {
            override val needsPin = true
            override val instruction = "Enter the 4-digit code shown on your Apple TV"

            override suspend fun finish(pin: String?): String {
                val code = pin?.trim() ?: throw PairingException("A code is required")
                return try {
                    session.complete(code).serialize()
                } catch (e: Exception) {
                    throw PairingException(e.message ?: "The Apple TV rejected the code")
                } finally {
                    client.close()
                    _state.value = ConnectionState.Disconnected
                }
            }

            override fun cancel() {
                client.close()
                _state.value = ConnectionState.Disconnected
            }
        }
    }
}
