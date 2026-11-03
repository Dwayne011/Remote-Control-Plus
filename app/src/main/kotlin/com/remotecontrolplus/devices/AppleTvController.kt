package com.remotecontrolplus.devices

import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.Diagnostics
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val portResolver: suspend (TvDevice, Long) -> Int?,
    private val cachedPort: (TvDevice) -> Int?,
    private val rememberPort: (TvDevice, Int) -> Unit,
) : TvController, Pairable {

    override val capabilities = Capabilities(
        // Apple TV has no mute key; volume routes over HDMI-CEC when the TV supports it.
        keys = Capabilities.ALL_KEYS - RemoteKey.MUTE,
        textEntry = true,
        volumeSlider = true,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _textField = MutableStateFlow(TextFieldState(focused = false, text = ""))
    override val textField: StateFlow<TextFieldState> = _textField.asStateFlow()

    private var remote: AppleTvRemote? = null
    @Volatile private var volumeSupported = false
    private val _volume = MutableStateFlow<Float?>(null)
    override val volume: StateFlow<Float?> = _volume.asStateFlow()

    override suspend fun connect() {
        val credentials = serializedCredentials?.let { runCatching { Credentials.parse(it) }.getOrNull() }
        if (credentials == null) {
            _state.value = ConnectionState.PairingRequired
            return
        }
        _state.value = ConnectionState.Connecting
        // A sleeping Apple TV takes several seconds to wake when contacted
        // over the network, and its Companion port moves across reboots. So:
        // look the port up fresh each attempt (falling back to the last port
        // that worked), and keep trying for about 30 seconds before giving up.
        val lookupTimeouts = listOf(4_000L, 6_000L, 6_000L, 8_000L)
        var lastError: Exception? = null
        for ((index, lookupTimeout) in lookupTimeouts.withIndex()) {
            if (index > 0) {
                Diagnostics.log("appletv", "attempt ${index + 1} of ${lookupTimeouts.size}; the Apple TV may be waking up")
                delay(1_500)
            }
            val port = portResolver(device, lookupTimeout) ?: cachedPort(device) ?: device.port
            try {
                attempt(credentials, port)
                return
            } catch (e: dev.atvremote.protocol.companion.PairingException) {
                Diagnostics.log("appletv", "credentials rejected; pairing required", e)
                _state.value = ConnectionState.PairingRequired
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.log("appletv", "connect to port $port failed", e)
                remote?.close()
                remote = null
                lastError = e
            }
        }
        _state.value = ConnectionState.Error(
            if (lastError is java.net.SocketTimeoutException) "Apple TV not reachable. Is it on the same Wi-Fi?"
            else lastError?.message ?: "Could not connect",
        )
    }

    private suspend fun attempt(credentials: Credentials, port: Int) {
        Diagnostics.log("appletv", "connecting to ${device.host}:$port with stored credentials")
        val r = AppleTvRemote(device.host, port, credentials, scope)
        r.onDisconnect = { cause ->
            if (_state.value is ConnectionState.Connected) {
                _state.value = ConnectionState.Error(cause?.message ?: "Connection lost")
            }
        }
        r.onCapabilities = { caps ->
            volumeSupported = caps.volume
            Diagnostics.log("appletv", "device capabilities: volume=${caps.volume} play=${caps.play}")
            if (!caps.volume) _volume.value = null else scope.launch { refreshVolume() }
        }
        r.onTextFocus = { session ->
            _textField.value = TextFieldState(
                focused = session != null,
                text = session?.textBeforeCursor ?: "",
            )
        }
        remote = r
        r.connect()
        rememberPort(device, port)
        Diagnostics.log("appletv", "connected and session started")
        _state.value = ConnectionState.Connected
    }

    override fun disconnect() {
        remote?.close()
        remote = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey) {
        val r = remote ?: throw NotConnectedException()
        when (key) {
            RemoteKey.POWER_ON -> r.wake()
            RemoteKey.POWER_OFF -> r.sleep()
            RemoteKey.VOLUME_UP -> { r.volumeUp(); refreshVolume() }
            RemoteKey.VOLUME_DOWN -> { r.volumeDown(); refreshVolume() }
            RemoteKey.MUTE -> Unit
            RemoteKey.UP -> r.up()
            RemoteKey.DOWN -> r.down()
            RemoteKey.LEFT -> r.left()
            RemoteKey.RIGHT -> r.right()
            RemoteKey.SELECT -> r.select()
            RemoteKey.BACK -> r.menu()
            RemoteKey.HOME -> r.home()
            RemoteKey.PLAY -> r.play()
            RemoteKey.PAUSE -> r.pause()
            RemoteKey.PREVIOUS -> r.previousTrack()
            RemoteKey.NEXT -> r.nextTrack()
        }
    }

    /** A swipe on the pad becomes a swipe on the Siri Remote's virtual trackpad. */
    override suspend fun swipe(direction: RemoteKey) {
        val r = remote ?: throw NotConnectedException()
        val (from, to) = when (direction) {
            RemoteKey.UP -> (500 to 700) to (500 to 300)
            RemoteKey.DOWN -> (500 to 300) to (500 to 700)
            RemoteKey.LEFT -> (700 to 500) to (300 to 500)
            RemoteKey.RIGHT -> (300 to 500) to (700 to 500)
            else -> return sendKey(direction)
        }
        r.swipe(from.first, from.second, to.first, to.second, durationMs = 120)
    }

    override suspend fun setVolume(fraction: Float) {
        val r = remote ?: throw NotConnectedException()
        r.setVolume(fraction.toDouble())
        refreshVolume()
    }

    private suspend fun refreshVolume() {
        val r = remote ?: return
        if (!volumeSupported) return
        _volume.value = runCatching { r.getVolume()?.toFloat() }.getOrNull()
    }

    override suspend fun sendText(text: String) {
        val r = remote ?: throw NotConnectedException()
        r.sendText(text, clearPrevious = false)
    }

    override suspend fun beginPairing(): PairingHandle {
        disconnect()
        _state.value = ConnectionState.Connecting
        val port = portResolver(device, 6000) ?: cachedPort(device) ?: device.port
        Diagnostics.log("appletv", "pairing: opening ${device.host}:$port (stored port ${device.port})")
        val client = CompanionClient(device.host, port, scope)
        val session = try {
            client.connect()
            Diagnostics.log("appletv", "pairing: TCP connected, sending pair-setup M1")
            client.startPairing(deviceName = "Remote Control Plus").also {
                Diagnostics.log("appletv", "pairing: M2 received, the Apple TV should now show a code")
            }
        } catch (e: Exception) {
            Diagnostics.log("appletv", "pairing: could not start", e)
            client.close()
            _state.value = ConnectionState.Error(e.message ?: "Pairing failed")
            throw PairingException(e.message ?: "Pairing failed")
        }
        return object : PairingHandle {
            override val needsPin = true
            override val instruction = "Enter the 4-digit code shown on your Apple TV"

            override suspend fun finish(pin: String?): String {
                val code = pin?.trim() ?: throw PairingException("A code is required")
                Diagnostics.log("appletv", "pairing: submitting ${code.length}-digit code")
                return try {
                    session.complete(code).serialize().also { Diagnostics.log("appletv", "pairing: complete") }
                } catch (e: Exception) {
                    Diagnostics.log("appletv", "pairing: failed after code entry", e)
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
