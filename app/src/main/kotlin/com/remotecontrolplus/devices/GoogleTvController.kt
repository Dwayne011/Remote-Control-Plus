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

    override val capabilities = Capabilities(keys = Capabilities.ALL_KEYS, textEntry = true)

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
            val s = RemoteSession(device.host, id)
            s.connect(scope)
            session = s
            watchers = scope.launch {
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
        session = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey) {
        val s = session ?: throw NotConnectedException()
        val code = when (key) {
            RemoteKey.POWER -> RemoteKeyCode.KEYCODE_POWER
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
            RemoteKey.PLAY_PAUSE -> RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE
        }
        s.sendKey(code)
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
        try {
            pairing.start()
        } catch (e: Exception) {
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
                } catch (e: Exception) {
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
