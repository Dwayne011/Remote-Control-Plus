package com.remotecontrolplus.devices

import android.util.Base64
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
import com.remotecontrolplus.core.WakeOnLan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Samsung Tizen TVs (2016 onwards): JSON over a TLS WebSocket on port 8002.
 *
 * The first connection makes the TV show an "Allow / Deny" prompt. Allowing
 * returns a token in the `ms.channel.connect` event, which is the credential
 * for every later connection. Reference: samsung-tv-ws-api.
 */
class SamsungController(
    override val device: TvDevice,
    private val token: String?,
    private val appName: String = "Remote Control Plus",
) : TvController, Pairable {

    override val capabilities = Capabilities(keys = Capabilities.ALL_KEYS, textEntry = true)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _textField = MutableStateFlow(TextFieldState(focused = false, text = ""))
    override val textField: StateFlow<TextFieldState> = _textField.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var socket: WebSocket? = null

    /** Samsung has separate play and pause keys; the shared control alternates them. */
    private var playing = true

    override suspend fun connect() {
        if (token == null) {
            _state.value = ConnectionState.PairingRequired
            return
        }
        _state.value = ConnectionState.Connecting
        try {
            open(token)
            _state.value = ConnectionState.Connected
        } catch (e: PairingException) {
            _state.value = ConnectionState.PairingRequired
        } catch (e: Exception) {
            _state.value = ConnectionState.Error(e.message ?: "Could not connect")
        }
    }

    override fun disconnect() {
        socket?.close(1000, null)
        socket = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey) {
        if (key == RemoteKey.POWER && state.value !is ConnectionState.Connected && device.mac != null) {
            // Off TVs are not listening; a magic packet is the only way in.
            WakeOnLan.send(device.mac)
            return
        }
        val code = when (key) {
            RemoteKey.POWER -> "KEY_POWER"
            RemoteKey.VOLUME_UP -> "KEY_VOLUP"
            RemoteKey.VOLUME_DOWN -> "KEY_VOLDOWN"
            RemoteKey.MUTE -> "KEY_MUTE"
            RemoteKey.UP -> "KEY_UP"
            RemoteKey.DOWN -> "KEY_DOWN"
            RemoteKey.LEFT -> "KEY_LEFT"
            RemoteKey.RIGHT -> "KEY_RIGHT"
            RemoteKey.SELECT -> "KEY_ENTER"
            RemoteKey.BACK -> "KEY_RETURN"
            RemoteKey.HOME -> "KEY_HOME"
            RemoteKey.PLAY_PAUSE -> (if (playing) "KEY_PAUSE" else "KEY_PLAY").also { playing = !playing }
        }
        send(
            buildJsonObject {
                put("method", "ms.remote.control")
                putJsonObject("params") {
                    put("Cmd", "Click")
                    put("DataOfCmd", code)
                    put("Option", "false")
                    put("TypeOfRemote", "SendRemoteKey")
                }
            }.toString(),
        )
    }

    override suspend fun sendText(text: String) {
        send(
            buildJsonObject {
                put("method", "ms.remote.control")
                putJsonObject("params") {
                    put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
                    put("DataOfCmd", "base64")
                    put("TypeOfRemote", "SendInputString")
                }
            }.toString(),
        )
        send(
            buildJsonObject {
                put("method", "ms.remote.control")
                putJsonObject("params") {
                    put("TypeOfRemote", "SendInputEnd")
                }
            }.toString(),
        )
    }

    override suspend fun beginPairing(): PairingHandle = object : PairingHandle {
        override val needsPin = false
        override val instruction = "Select Allow on your Samsung TV"

        override suspend fun finish(pin: String?): String {
            _state.value = ConnectionState.Connecting
            val newToken = open(null)
            _state.value = ConnectionState.Connected
            return newToken
        }

        override fun cancel() = disconnect()
    }

    private fun send(text: String) {
        val s = socket ?: throw NotConnectedException()
        if (!s.send(text)) throw NotConnectedException()
    }

    /**
     * Open the remote-control channel. Returns the token the TV issues (the
     * stored one when it is still valid). Throws [PairingException] when the
     * TV rejects or the user denies the prompt.
     */
    private suspend fun open(existingToken: String?): String {
        socket?.close(1000, null)
        val name = Base64.encodeToString(appName.toByteArray(), Base64.NO_WRAP)
        val url = buildString {
            append("wss://${device.host}:8002/api/v2/channels/samsung.remote.control?name=$name")
            if (existingToken != null) append("&token=$existingToken")
        }
        val connected = CompletableDeferred<String>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (obj["event"]?.jsonPrimitive?.content) {
                    "ms.channel.connect" -> {
                        val issued = obj["data"]?.jsonObject?.get("token")?.jsonPrimitive?.content
                        connected.complete(issued ?: existingToken ?: "")
                    }
                    "ms.channel.unauthorized" -> connected.completeExceptionally(
                        PairingException("The TV denied the connection"),
                    )
                    "ms.channel.timeOut" -> connected.completeExceptionally(
                        PairingException("The TV did not answer the pairing prompt in time"),
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.completeExceptionally(t)
                if (_state.value is ConnectionState.Connected) {
                    _state.value = ConnectionState.Error(t.message ?: "Connection lost")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (_state.value is ConnectionState.Connected) _state.value = ConnectionState.Disconnected
            }
        }
        socket = TrustAll.okHttp.newWebSocket(Request.Builder().url(url).build(), listener)
        // The pairing prompt has a 30 second window on the TV.
        return withTimeout(35_000) { connected.await() }
    }
}
