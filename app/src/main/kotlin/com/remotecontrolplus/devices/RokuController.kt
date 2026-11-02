package com.remotecontrolplus.devices

import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.core.TextFieldState
import com.remotecontrolplus.core.TvController
import com.remotecontrolplus.core.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Roku External Control Protocol: plain HTTP on port 8060, no pairing.
 * Connecting just checks the device answers.
 */
class RokuController(override val device: TvDevice) : TvController {

    override val capabilities = Capabilities(keys = Capabilities.ALL_KEYS, textEntry = true)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _textField = MutableStateFlow(TextFieldState(focused = false, text = ""))
    override val textField: StateFlow<TextFieldState> = _textField.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val base = "http://${device.host}:8060"

    override suspend fun connect() {
        _state.value = ConnectionState.Connecting
        _state.value = try {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("$base/query/device-info").build()).execute().use {
                    if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
                }
            }
            ConnectionState.Connected
        } catch (e: Exception) {
            ConnectionState.Error(e.message ?: "Roku not reachable")
        }
    }

    override fun disconnect() {
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey) {
        val name = when (key) {
            RemoteKey.POWER -> "Power"
            RemoteKey.VOLUME_UP -> "VolumeUp"
            RemoteKey.VOLUME_DOWN -> "VolumeDown"
            RemoteKey.MUTE -> "VolumeMute"
            RemoteKey.UP -> "Up"
            RemoteKey.DOWN -> "Down"
            RemoteKey.LEFT -> "Left"
            RemoteKey.RIGHT -> "Right"
            RemoteKey.SELECT -> "Select"
            RemoteKey.BACK -> "Back"
            RemoteKey.HOME -> "Home"
            RemoteKey.PLAY_PAUSE -> "Play"
        }
        keypress(name)
    }

    override suspend fun sendText(text: String) {
        for (ch in text) keypress("Lit_" + URLEncoder.encode(ch.toString(), "UTF-8"))
    }

    private suspend fun keypress(name: String) = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder()
                .url("$base/keypress/$name")
                .post(ByteArray(0).toRequestBody())
                .build(),
        ).execute().use {
            if (!it.isSuccessful) throw Exception("Roku rejected $name (HTTP ${it.code})")
        }
    }
}
