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
import dev.amrakshay.atvremote.protocol.ClientIdentity
import dev.amrakshay.atvremote.protocol.PairingSession
import dev.amrakshay.atvremote.protocol.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import remote.Remotemessage.RemoteKeyCode

/**
 * Chromecast with Google TV, Google TV sets and other Android TV devices,
 * over the Android TV Remote protocol v2 (vendored module).
 *
 * The credential is a self-signed client certificate the TV learns to trust
 * during pairing; it is stored as PEM.
 */
class GoogleTvController(
    override val device: TvDevice,
    private val serializedIdentity: String?,
    private val scope: CoroutineScope,
) : TvController, Pairable {

    @Serializable
    private data class StoredIdentity(val certificatePem: String, val privateKeyPem: String)

    override val capabilities = Capabilities(keys = Capabilities.ALL_KEYS, textEntry = true, volumeSlider = true)

    private val _powerState = MutableStateFlow<Boolean?>(null)
    override val powerState: StateFlow<Boolean?> = _powerState.asStateFlow()

    private val _volume = MutableStateFlow<Float?>(null)
    override val volume: StateFlow<Float?> = _volume.asStateFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _textField = MutableStateFlow(TextFieldState(focused = false, text = ""))
    override val textField: StateFlow<TextFieldState> = _textField.asStateFlow()

    private var session: RemoteSession? = null
    private var watchers: Job? = null

    private fun identity(): ClientIdentity? = serializedIdentity?.let {
        runCatching {
            val stored = Json.decodeFromString<StoredIdentity>(it)
            ClientIdentity.fromPem(stored.certificatePem, stored.privateKeyPem)
        }.getOrNull()
    }

    override suspend fun connect() {
        val id = identity()
        if (id == null) {
            _state.value = ConnectionState.PairingRequired
            return
        }
        _state.value = ConnectionState.Connecting
        try {
            Diagnostics.log("googletv", "connecting to ${device.host}:6466 with stored certificate")
            val s = RemoteSession(device.host, id)
            s.connect(scope)
            Diagnostics.log("googletv", "connected; TV reported ${s.device.value}")
            session = s
            watchers = scope.launch {
                launch { s.volume.collect { v -> _volume.value = v?.fraction } }
                launch { s.isOn.collect { on -> _powerState.value = on } }
                launch {
                    s.disconnects.collect { reason ->
                        if (_state.value is ConnectionState.Connected) {
                            _state.value = ConnectionState.Error(reason)
                        }
                    }
                }
                launch {
                    combine(s.imeReady, s.textField) { ready, text -> TextFieldState(ready, text) }
                        .collect { _textField.value = it }
                }
            }
            _state.value = ConnectionState.Connected
        } catch (e: Exception) {
            // The TV drops the TLS handshake outright when it does not know our
            // certificate, which is how "needs pairing" presents itself.
            val message = e.message ?: ""
            Diagnostics.log("googletv", "connect failed", e)
            _state.value = if (e is javax.net.ssl.SSLException || "handshake" in message.lowercase()) {
                ConnectionState.PairingRequired
            } else {
                ConnectionState.Error(message.ifEmpty { "Could not connect" })
            }
        }
    }

    override fun disconnect() {
        watchers?.cancel()
        watchers = null
        session?.close()
        _powerState.value = null
        session = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey) {
        val s = session ?: throw NotConnectedException()
        val code = when (key) {
            // The TV reports whether it is on, so On/Off can share the toggle key safely.
            RemoteKey.POWER_ON -> if (s.isOn.value) return else RemoteKeyCode.KEYCODE_POWER
            RemoteKey.POWER_OFF -> if (!s.isOn.value) return else RemoteKeyCode.KEYCODE_POWER
            RemoteKey.VOLUME_UP -> RemoteKeyCode.KEYCODE_VOLUME_UP
            RemoteKey.VOLUME_DOWN -> RemoteKeyCode.KEYCODE_VOLUME_DOWN
            RemoteKey.MUTE -> RemoteKeyCode.KEYCODE_VOLUME_MUTE
            RemoteKey.UP -> RemoteKeyCode.KEYCODE_DPAD_UP
            RemoteKey.DOWN -> RemoteKeyCode.KEYCODE_DPAD_DOWN
            RemoteKey.LEFT -> RemoteKeyCode.KEYCODE_DPAD_LEFT
            RemoteKey.RIGHT -> RemoteKeyCode.KEYCODE_DPAD_RIGHT
            RemoteKey.SELECT -> RemoteKeyCode.KEYCODE_DPAD_CENTER
            RemoteKey.BACK -> RemoteKeyCode.KEYCODE_BACK
            RemoteKey.HOME -> RemoteKeyCode.KEYCODE_HOME
            RemoteKey.PLAY -> RemoteKeyCode.KEYCODE_MEDIA_PLAY
            RemoteKey.PAUSE -> RemoteKeyCode.KEYCODE_MEDIA_PAUSE
            RemoteKey.PREVIOUS -> RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS
            RemoteKey.NEXT -> RemoteKeyCode.KEYCODE_MEDIA_NEXT
            RemoteKey.ASSISTANT -> RemoteKeyCode.KEYCODE_ASSIST
        }
        s.sendKey(code)
    }

    /** No absolute volume command exists; step towards the target with key presses. */
    override suspend fun setVolume(fraction: Float) {
        val s = session ?: throw NotConnectedException()
        val info = s.volume.value ?: return
        val target = (fraction * info.max).toInt().coerceIn(0, info.max)
        val steps = target - info.level
        val code = if (steps > 0) RemoteKeyCode.KEYCODE_VOLUME_UP else RemoteKeyCode.KEYCODE_VOLUME_DOWN
        repeat(kotlin.math.abs(steps).coerceAtMost(30)) {
            s.sendKey(code)
            kotlinx.coroutines.delay(60)
        }
    }

    override suspend fun sendText(text: String) {
        val s = session ?: throw NotConnectedException()
        s.sendText(text)
    }

    override suspend fun beginPairing(): PairingHandle {
        disconnect()
        _state.value = ConnectionState.Connecting
        val id = identity() ?: ClientIdentity.generate("remotecontrolplus")
        val pairing = PairingSession(device.host, id, clientName = "Remote Control Plus")
        Diagnostics.log("googletv", "pairing: opening ${device.host}:6467")
        try {
            pairing.start()
            Diagnostics.log("googletv", "pairing: handshake done, the TV should now show a code")
        } catch (e: Exception) {
            Diagnostics.log("googletv", "pairing: could not start", e)
            pairing.close()
            _state.value = ConnectionState.Error(e.message ?: "Pairing failed")
            throw PairingException(e.message ?: "Pairing failed")
        }
        return object : PairingHandle {
            override val needsPin = true
            override val instruction = "Enter the code shown on your TV"

            override suspend fun finish(pin: String?): String {
                val code = pin?.trim() ?: throw PairingException("A code is required")
                try {
                    pairing.finish(code)
                    Diagnostics.log("googletv", "pairing: complete")
                } catch (e: Exception) {
                    Diagnostics.log("googletv", "pairing: failed after code entry", e)
                    throw PairingException(e.message ?: "The TV rejected the code")
                } finally {
                    pairing.close()
                    _state.value = ConnectionState.Disconnected
                }
                return Json.encodeToString(
                    StoredIdentity.serializer(),
                    StoredIdentity(id.certificatePem(), id.privateKeyPem()),
                )
            }

            override fun cancel() {
                pairing.close()
                _state.value = ConnectionState.Disconnected
            }
        }
    }
}
