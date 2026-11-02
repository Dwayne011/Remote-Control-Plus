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
import com.remotecontrolplus.core.WakeOnLan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG webOS TVs: SSAP (JSON over TLS WebSocket, port 3001).
 *
 * Registration shows a prompt on the TV; accepting returns a `client-key`
 * that is the credential from then on. Navigation keys do not go over the
 * main socket but over a second "pointer input" socket the TV hands out.
 * Reference: PyWebOSTV and LG's Connect SDK.
 */
class LgController(
    override val device: TvDevice,
    private val clientKey: String?,
    private val appName: String = "Remote Control Plus",
) : TvController, Pairable {

    override val capabilities = Capabilities(keys = Capabilities.ALL_KEYS, textEntry = true)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _textField = MutableStateFlow(TextFieldState(focused = false, text = ""))
    override val textField: StateFlow<TextFieldState> = _textField.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var socket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private val requestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var playing = true

    override suspend fun connect() {
        if (clientKey == null) {
            _state.value = ConnectionState.PairingRequired
            return
        }
        _state.value = ConnectionState.Connecting
        try {
            register(clientKey)
            _state.value = ConnectionState.Connected
        } catch (e: PairingException) {
            _state.value = ConnectionState.PairingRequired
        } catch (e: Exception) {
            _state.value = ConnectionState.Error(e.message ?: "Could not connect")
        }
    }

    override fun disconnect() {
        pointerSocket?.close(1000, null)
        pointerSocket = null
        socket?.close(1000, null)
        socket = null
        pending.clear()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey) {
        if (key == RemoteKey.POWER && state.value !is ConnectionState.Connected && device.mac != null) {
            WakeOnLan.send(device.mac)
            return
        }
        when (key) {
            RemoteKey.POWER -> request("ssap://system/turnOff")
            RemoteKey.VOLUME_UP -> request("ssap://audio/volumeUp")
            RemoteKey.VOLUME_DOWN -> request("ssap://audio/volumeDown")
            RemoteKey.MUTE -> {
                val status = request("ssap://audio/getStatus")
                val muted = status["payload"]?.jsonObject?.get("mute")?.jsonPrimitive?.content == "true"
                request("ssap://audio/setMute", buildJsonObject { put("mute", !muted) })
            }
            RemoteKey.UP -> pointerButton("UP")
            RemoteKey.DOWN -> pointerButton("DOWN")
            RemoteKey.LEFT -> pointerButton("LEFT")
            RemoteKey.RIGHT -> pointerButton("RIGHT")
            RemoteKey.SELECT -> pointerButton("ENTER")
            RemoteKey.BACK -> pointerButton("BACK")
            RemoteKey.HOME -> pointerButton("HOME")
            RemoteKey.PLAY_PAUSE -> {
                request(if (playing) "ssap://media.controls/pause" else "ssap://media.controls/play")
                playing = !playing
            }
        }
    }

    override suspend fun sendText(text: String) {
        request(
            "ssap://com.webos.service.ime/insertText",
            buildJsonObject {
                put("text", text)
                put("replace", 0)
            },
        )
    }

    override suspend fun beginPairing(): PairingHandle = object : PairingHandle {
        override val needsPin = false
        override val instruction = "Select Yes on your LG TV"

        override suspend fun finish(pin: String?): String {
            _state.value = ConnectionState.Connecting
            val key = register(null)
            _state.value = ConnectionState.Connected
            return key
        }

        override fun cancel() = disconnect()
    }

    // ------------------------------------------------------------------ SSAP

    private suspend fun register(existingKey: String?): String {
        disconnect()
        val registered = CompletableDeferred<String>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(registerPayload(existingKey).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val id = obj["id"]?.jsonPrimitive?.content
                when (obj["type"]?.jsonPrimitive?.content) {
                    "registered" -> {
                        val key = obj["payload"]?.jsonObject?.get("client-key")?.jsonPrimitive?.content
                        if (key != null) registered.complete(key)
                        else registered.completeExceptionally(PairingException("TV sent no client key"))
                    }
                    "error" -> {
                        val message = obj["error"]?.jsonPrimitive?.content ?: "TV error"
                        if (id == "register_0" || !registered.isCompleted) {
                            registered.completeExceptionally(PairingException(message))
                        } else if (id != null) {
                            pending.remove(id)?.completeExceptionally(Exception(message))
                        }
                    }
                    "response" -> if (id != null) pending.remove(id)?.complete(obj)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                registered.completeExceptionally(t)
                pending.values.forEach { it.completeExceptionally(t) }
                pending.clear()
                if (_state.value is ConnectionState.Connected) {
                    _state.value = ConnectionState.Error(t.message ?: "Connection lost")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (_state.value is ConnectionState.Connected) _state.value = ConnectionState.Disconnected
            }
        }
        socket = TrustAll.okHttp.newWebSocket(
            Request.Builder().url("wss://${device.host}:3001").build(),
            listener,
        )
        return withTimeout(60_000) { registered.await() }
    }

    private suspend fun request(uri: String, payload: JsonObject? = null): JsonObject {
        val s = socket ?: throw NotConnectedException()
        val id = "req_${requestId.getAndIncrement()}"
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val message = buildJsonObject {
            put("type", "request")
            put("id", id)
            put("uri", uri)
            if (payload != null) put("payload", payload)
        }
        if (!s.send(message.toString())) {
            pending.remove(id)
            throw NotConnectedException()
        }
        return try {
            withTimeout(8_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun pointerButton(name: String) {
        val ps = pointerSocket ?: openPointerSocket().also { pointerSocket = it }
        if (!ps.send("type:button\nname:$name\n\n")) {
            pointerSocket = null
            throw NotConnectedException()
        }
    }

    private suspend fun openPointerSocket(): WebSocket {
        val response = request("ssap://com.webos.service.networkinput/getPointerInputSocket")
        val path = response["payload"]?.jsonObject?.get("socketPath")?.jsonPrimitive?.content
            ?: throw Exception("TV did not provide a pointer socket")
        val opened = CompletableDeferred<WebSocket>()
        val ws = TrustAll.okHttp.newWebSocket(
            Request.Builder().url(path).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(webSocket)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.completeExceptionally(t)
                    pointerSocket = null
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    pointerSocket = null
                }
            },
        )
        return withTimeout(5_000) { opened.await() }
    }

    private fun registerPayload(existingKey: String?): JsonObject = buildJsonObject {
        put("type", "register")
        put("id", "register_0")
        putJsonObject("payload") {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            if (existingKey != null) put("client-key", existingKey)
            putJsonObject("manifest") {
                put("manifestVersion", 1)
                put("appVersion", "1.0")
                putJsonObject("signed") {
                    put("appId", "com.remotecontrolplus")
                    put("vendorId", "com.remotecontrolplus")
                    put("created", "20250101")
                    putJsonObject("localizedAppNames") { put("", appName) }
                    putJsonObject("localizedVendorNames") { put("", appName) }
                    putJsonArray("permissions") {
                        listOf(
                            "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
                            "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_NOTIFICATIONS", "SEARCH",
                            "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT", "CONTROL_POWER",
                            "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_UPDATE_INFO",
                            "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME",
                        ).forEach { add(it) }
                    }
                    putJsonArray("serial") { }
                }
                putJsonArray("permissions") {
                    listOf(
                        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
                        "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
                        "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
                        "CONTROL_POWER", "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
                        "READ_NETWORK_STATE", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST",
                        "READ_POWER_STATE", "READ_COUNTRY_INFO", "READ_SETTINGS", "CONTROL_TV_SCREEN",
                        "CONTROL_TV_STANBY", "CONTROL_FAVORITE_GROUP", "CONTROL_USER_INFO", "CHECK_BLUETOOTH_DEVICE",
                        "CONTROL_BLUETOOTH", "CONTROL_TIMER_INFO", "STB_INTERNAL_CONNECTION", "CONTROL_RECORDING",
                        "READ_RECORDING_STATE", "WRITE_RECORDING_LIST", "READ_RECORDING_LIST", "READ_RECORDING_SCHEDULE",
                        "WRITE_RECORDING_SCHEDULE", "READ_STORAGE_DEVICE_LIST", "READ_TV_PROGRAM_INFO",
                        "CONTROL_BOX_CHANNEL", "READ_TV_ACR_AUTH_TOKEN", "READ_TV_CONTENT_STATE", "READ_TV_CURRENT_TIME",
                        "ADD_LAUNCHER_CHANNEL", "SET_CHANNEL_SKIP", "RELEASE_CHANNEL_SKIP", "CONTROL_CHANNEL_BLOCK",
                        "DELETE_SELECT_CHANNEL", "CONTROL_CHANNEL_GROUP", "SCAN_TV_CHANNELS", "CONTROL_TV_POWER",
                        "CONTROL_WOL",
                    ).forEach { add(it) }
                }
            }
        }
    }
}
